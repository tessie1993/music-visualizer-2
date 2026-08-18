package dev.geode.engine.audio

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The audio-reactive analysis graph, as one node.
 *
 * Window → spectrum → tilt-corrected log bands → adaptive range → the band
 * levels a scene rides; and, on the same spectrum, whitening → SuperFlux →
 * peak picking → resonator bank → beat grid for the rhythm channels.
 *
 * ## Why this exists as one class
 *
 * The pieces below are each independently testable and independently correct,
 * but a caller wiring them by hand has to know the order, which buffers are
 * linear and which are dB, which nodes want `dt` and which want frames, and
 * which of them must be reset together. That knowledge is the module, so it
 * lives here rather than in `:app`. Callers get raw samples in and a frame of
 * bounded, loudness-independent values out.
 *
 * ## The contract that was broken before
 *
 * Every output on the 0..1 scale genuinely uses that scale, on any material at
 * any master level. That is not a nicety: every scene in the app multiplies
 * these by a drive parameter and expects a signal that spans its range, and
 * the legacy path delivered 0.02..0.08 on ordinary music and exactly 0 on
 * quiet masters. `ReactiveAnalyzerTest` asserts the range and the
 * level-independence directly, because they are the product requirement.
 *
 * Reuses preallocated buffers throughout and allocates nothing per frame, as
 * the real-time path requires. Not thread-safe: one instance per worker.
 */
class ReactiveAnalyzer(
    val bandCount: Int = 64,
    val fftSize: Int = 2048,
    sampleRateHz: Int = 48_000,
    /** Analysis frames per second; the rhythm nodes measure time in frames. */
    private val hopRateHz: Float = 62.5f,
) {
    private val window = WindowTable(fftSize, WindowShape.HANN)
    private val windowed = FloatArray(fftSize)
    private val spectrum = Spectrum(fftSize)
    private val logBands = LogBands(bandCount, fftSize, sampleRateHz)

    private val bandPower = FloatArray(bandCount)
    private val bandDb = FloatArray(bandCount)
    private val bandNormalized = FloatArray(bandCount)
    private val whitened = FloatArray(bandCount)

    private val range = AdaptiveRange(bandCount)
    private val whitening = AdaptiveWhitening(bandCount)
    private val flux = SuperFlux(bandCount)
    private val picker = OnsetPeakPicker(hopRateHz)
    private val tempo = TempoTracker(hopRateHz)
    private val grid = BeatGrid()
    private val stability = TempoStability(hopRateHz)
    private val bar = BarTracker()

    /** Band-limited onset channels; see [kick]. */
    private var drums = DrumChannels(bandCount, hopRateHz, sampleRateHz)

    private val structure = StructureTracker(bandCount, hopRateHz)
    private val harmonic = HarmonicBalance(fftSize / 2 + 1, hopRateHz)

    /** Smoothed band levels, the array a scene reads. Valid after [analyze]. */
    val bands: FloatArray = FloatArray(bandCount)
    private val smoothingState = FloatArray(bandCount)

    /** Sample rate of the audio being analyzed; rebuilds the band tables when set. */
    var sampleRateHz: Int = sampleRateHz
        set(value) {
            if (value != field) {
                field = value
                logBands.sampleRateHz = value
                drums = DrumChannels(bandCount, hopRateHz, value).also { it.sensitivity = picker.sensitivity }
            }
        }

    /** Attack time of the band smoothing, in seconds; the reactivity control. */
    @Volatile
    var attackSeconds: Float = 0.02f

    /** Release time of the band smoothing, in seconds. */
    @Volatile
    var releaseSeconds: Float = 0.15f

    /** See [OnsetPeakPicker.sensitivity]. */
    var sensitivity: Float
        get() = picker.sensitivity
        set(value) {
            picker.sensitivity = value
            drums.sensitivity = value
        }

    /** See [OnsetPeakPicker.refractorySeconds]. */
    var refractoryMs: Float
        get() = picker.refractorySeconds * 1000f
        set(value) {
            picker.refractorySeconds = value / 1000f
        }

    // ---- outputs, valid after each [analyze] ----

    /** True RMS of the analysis window, 0..1 — the instantaneous level. */
    var rms: Float = 0f
        private set

    /** Mean normalized level of the bottom eighth of the spectrum, 0..1. */
    var bass: Float = 0f
        private set

    /** Mean normalized level of the lower middle of the spectrum, 0..1. */
    var mid: Float = 0f
        private set

    /** Mean normalized level of the top half of the spectrum, 0..1. */
    var treble: Float = 0f
        private set

    /** Spectral centroid mapped onto 0..1 across the analyzed span. */
    var centroid: Float = 0f
        private set

    /** Raw SuperFlux onset evidence for this frame; the curve rhythm rides. */
    var fluxValue: Float = 0f
        private set

    /** Continuous onset strength, 0..1 — how far the flux stands above its threshold. */
    var onset: Float = 0f
        private set

    /** Whether this frame is a beat. */
    var beat: Boolean = false
        private set

    /** Graded weight of this frame's beat, 0..1; 0 off beats. */
    var beatStrength: Float = 0f
        private set

    /** Graded impulse for EVERY onset, including off-grid ones. */
    var transient: Float = 0f
        private set

    /** Position within the tracked beat, 0 on the beat rising to 1. */
    var beatPhase: Float = 0f
        private set

    /** Confidence in the beat grid, 0..1. */
    var pulseConfidence: Float = 0f
        private set

    /** Tempo in BPM, 0 until the resonator bank settles. */
    var bpm: Float = 0f
        private set

    /** Whether the tempo estimate has STAYED PUT, 0..1; see [TempoStability]. */
    var tempoStability: Float = 0f
        private set

    /** Position within the tracked 4/4 bar, 0 on the downbeat rising to 1. */
    var barPhase: Float = 0f
        private set

    /** Which beat of the bar the grid is in, 0..3; see [BarTracker]. */
    var beatInBar: Int = 0
        private set

    /** Whether this frame's beat starts the bar. */
    var downbeat: Boolean = false
        private set

    /** How clearly one bar position keeps winning, 0..1; see [BarTracker]. */
    var downbeatConfidence: Float = 0f
        private set

    /** Track-relative macro-dynamics, 0..1: this moment against the recent peak. */
    var macroEnergy: Float = 0f
        private set

    /**
     * Low-band onset impulse, 0..1 — kick range. These are band-activity
     * channels named after what usually dominates them, not a drum classifier.
     */
    var kick: Float = 0f
        private set

    /** Mid-band onset impulse; see [kick]. */
    var snare: Float = 0f
        private set

    /** High-band onset impulse; see [kick]. */
    var hat: Float = 0f
        private set

    /** How much the band profile is changing right now, 0..1; see [StructureTracker]. */
    var novelty: Float = 0f
        private set

    /** Whether this frame crossed a section boundary; see [StructureTracker]. */
    var sectionBoundary: Boolean = false
        private set

    /** EXPERIMENTAL: sustained energy rise, 0..1; see [StructureTracker]. */
    var buildup: Float = 0f
        private set

    /** EXPERIMENTAL: buildup-dip-slam event; see [StructureTracker]. */
    var drop: Boolean = false
        private set

    /** EXPERIMENTAL: energy returning after a long quiet; see [StructureTracker]. */
    var arrival: Boolean = false
        private set

    /** Harmonic against percussive, 0..1; see [HarmonicBalance]. */
    var harmonicity: Float = HarmonicBalance.UNDECIDED
        private set

    /** 0..1 while the adaptive range is still learning this track's dynamics. */
    val warmup: Float get() = range.warmup

    private var levelPeak = 0f
    private var lastFrameSilent = true
    private var silentSeconds = 0f

    /**
     * Analyzes one window of mono samples, advancing every stateful node by
     * [dtSeconds].
     *
     * [samples] must hold at least [fftSize] values and is not modified.
     */
    fun analyze(
        samples: FloatArray,
        dtSeconds: Float,
    ) {
        require(samples.size >= fftSize) { "need $fftSize samples, got ${samples.size}" }

        rms = rmsOf(samples)
        if (rms < SILENCE_RMS) {
            // Silent, so nothing is happening and nothing should learn. Returning
            // without advancing the adaptive nodes is what lets the visuals pick
            // up where they left off after a gap between tracks, rather than
            // spending the first bar re-learning the range from nothing.
            lastFrameSilent = true
            // Stability is the one rhythm output that must MOVE through
            // sustained silence: its contract is that the next track does
            // not inherit this one's certainty. But a window-level rest is
            // MUSICAL - a sparse kick pattern is silent between its hits -
            // so the drain waits out rest-length gaps before starting. The
            // bar and beat state hold throughout: the grid is not advancing,
            // and a bar phase that drifted on its own would be an invention.
            silentSeconds += dtSeconds
            if (silentSeconds > STABILITY_SILENCE_HOLD_SECONDS) {
                stability.step(0f)
                tempoStability = stability.value
            }
            silenceOutputs()
            return
        }

        lastFrameSilent = false
        silentSeconds = 0f
        window.applyInto(samples, 0, windowed)
        spectrum.compute(windowed)

        logBands.energy(spectrum.magnitudes, bandPower)
        toDb(bandPower, bandDb)
        range.normalize(bandDb, dtSeconds, bandNormalized)
        smooth(bandNormalized, dtSeconds)

        bass = mean(bands, 0, bandCount / 8)
        mid = mean(bands, bandCount / 8, bandCount / 2)
        treble = mean(bands, bandCount / 2, bandCount)
        centroid = normalizedCentroid()
        macroEnergy = macroEnergyOf(rms, dtSeconds)

        whitening.whiten(bandPower, dtSeconds, whitened)
        fluxValue = flux.next(whitened)
        val isOnset = picker.accept(fluxValue)
        onset = (fluxValue / max(picker.threshold * ONSET_HEADROOM, 1e-9f)).coerceIn(0f, 1f)
        transient = picker.strength

        tempo.step(fluxValue)
        bpm = tempo.bpm
        pulseConfidence = tempo.confidence
        stability.step(tempo.bpm)
        tempoStability = stability.value
        beat = grid.step(tempo.periodFrames, tempo.confidence, isOnset)
        beatPhase = grid.phase
        beatStrength = if (beat) picker.strength else 0f

        drums.step(whitened)
        kick = drums.kick
        snare = drums.snare
        hat = drums.hat

        // After the drums: the bar's accent evidence is the hit's own graded
        // strength plus the low band, because "which beat is one" is mostly a
        // question the kick answers.
        bar.step(
            phase = grid.phase,
            beat = beat,
            locked = grid.locked,
            accent = if (beat) picker.strength + KICK_ACCENT_WEIGHT * drums.kick else 0f,
        )
        barPhase = bar.barPhase
        beatInBar = bar.beatInBar
        downbeat = bar.downbeat
        downbeatConfidence = bar.confidence

        structure.step(bands, rms, onset)
        novelty = structure.novelty
        sectionBoundary = structure.sectionBoundary
        buildup = structure.buildup
        drop = structure.drop
        arrival = structure.arrival
        harmonic.step(spectrum.magnitudes)
        harmonicity = harmonic.balance
    }

    /**
     * Forgets everything that belongs to one piece of audio — learned ranges,
     * peak profiles, the flux history, the tempo bank and the beat grid — while
     * keeping the user's settings.
     *
     * Call on any discontinuity: a track change or a seek. Every node here
     * models one continuous piece of music, and carried across a boundary those
     * models are actively wrong rather than merely stale.
     */
    fun reset() {
        range.reset()
        whitening.reset()
        flux.reset()
        picker.reset()
        tempo.reset()
        grid.reset()
        stability.reset()
        bar.reset()
        structure.reset()
        harmonic.reset()
        drums.reset()
        smoothingState.fill(0f)
        bands.fill(0f)
        levelPeak = 0f
        silentSeconds = 0f
        rms = 0f
        bass = 0f
        mid = 0f
        treble = 0f
        centroid = 0f
        fluxValue = 0f
        onset = 0f
        beat = false
        beatStrength = 0f
        transient = 0f
        beatPhase = 0f
        pulseConfidence = 0f
        bpm = 0f
        tempoStability = 0f
        barPhase = 0f
        beatInBar = 0
        downbeat = false
        downbeatConfidence = 0f
        novelty = 0f
        sectionBoundary = false
        buildup = 0f
        drop = false
        arrival = false
        harmonicity = HarmonicBalance.UNDECIDED
        macroEnergy = 0f
        kick = 0f
        snare = 0f
        hat = 0f
    }

    /**
     * Writes the half magnitude spectrum of the last analyzed frame into [out],
     * scaled so a full-scale tone reads near 1.
     *
     * [out] must hold `fftSize / 2` values — DC through the bin below Nyquist,
     * with DC forced to zero because it carries offset rather than music. That
     * is the layout and the scale the pitch nodes ([dev.geode] `Chromagram`
     * and `KeyDetector`) were written against, and their silence thresholds are
     * absolute, so handing them this node's natural unscaled magnitudes would
     * quietly disable those thresholds.
     *
     * Reads as silence after a silent frame rather than serving the last loud
     * spectrum, which would leave a harmony reading frozen on the note the
     * track ended on.
     */
    fun spectrumInto(out: FloatArray): FloatArray {
        require(out.size == fftSize / 2) { "expected ${fftSize / 2} bins, got ${out.size}" }
        if (lastFrameSilent) {
            out.fill(0f)
            return out
        }
        val scale = 2f / fftSize
        out[0] = 0f
        for (k in 1 until fftSize / 2) out[k] = spectrum.magnitudes[k] * scale
        return out
    }

    /** Publishes a wholly-still frame, leaving every learned model untouched. */
    private fun silenceOutputs() {
        bands.fill(0f)
        smoothingState.fill(0f)
        bass = 0f
        mid = 0f
        treble = 0f
        centroid = 0f
        fluxValue = 0f
        onset = 0f
        beat = false
        beatStrength = 0f
        transient = 0f
        downbeat = false
        sectionBoundary = false
        drop = false
        arrival = false
        macroEnergy = 0f
        kick = 0f
        snare = 0f
        hat = 0f
    }

    private fun toDb(
        power: FloatArray,
        out: FloatArray,
    ) {
        for (b in 0 until bandCount) {
            val p = power[b]
            out[b] =
                if (p <= 0f) {
                    AdaptiveRange.SILENCE_DB
                } else {
                    max(10f * kotlin.math.log10(p), AdaptiveRange.SILENCE_DB)
                }
        }
    }

    /**
     * Asymmetric per-band smoothing, fast up and slow down, with coefficients
     * derived from `dt` for the reason [Envelope] documents. Inlined over the
     * array rather than sixty-four [Envelope] instances: this runs every frame
     * over every band, and the object hop per band was measurable.
     */
    private fun smooth(
        source: FloatArray,
        dtSeconds: Float,
    ) {
        if (dtSeconds <= 0f) {
            source.copyInto(bands)
            return
        }
        val attack = if (attackSeconds <= 0f) 1f else (1f - exp(-dtSeconds / attackSeconds)).coerceIn(0f, 1f)
        val release = if (releaseSeconds <= 0f) 1f else (1f - exp(-dtSeconds / releaseSeconds)).coerceIn(0f, 1f)
        for (b in 0 until bandCount) {
            val target = source[b]
            val k = if (target > smoothingState[b]) attack else release
            smoothingState[b] += (target - smoothingState[b]) * k
            bands[b] = smoothingState[b]
        }
    }

    private fun rmsOf(samples: FloatArray): Float {
        var acc = 0.0
        for (i in 0 until fftSize) {
            val v = samples[i]
            acc += v.toDouble() * v
        }
        return sqrt(acc / fftSize).toFloat().coerceIn(0f, 1f)
    }

    /**
     * The centroid on the same log axis the bands use, so it reads as "how
     * bright" on a 0..1 scale rather than as a frequency a scene would have to
     * know the sample rate to interpret.
     */
    private fun normalizedCentroid(): Float {
        var weight = 0f
        var weighted = 0f
        for (b in 0 until bandCount) {
            weight += bands[b]
            weighted += bands[b] * b
        }
        return if (weight <= 1e-6f) 0f else (weighted / weight / (bandCount - 1)).coerceIn(0f, 1f)
    }

    /**
     * This moment against the loudest the track has recently been — the "arc of
     * the song" signal, where [rms] is the instantaneous level. Fast to follow
     * a rise, slow to forget, so a verse sits low under the chorus that
     * preceded it instead of re-normalizing itself back to full.
     */
    private fun macroEnergyOf(
        level: Float,
        dtSeconds: Float,
    ): Float {
        val decay = if (dtSeconds <= 0f) 1f else exp(-dtSeconds / MACRO_PEAK_SECONDS)
        levelPeak = max(level, levelPeak * decay)
        return if (levelPeak <= 1e-6f) 0f else (level / levelPeak).coerceIn(0f, 1f)
    }

    private fun mean(
        values: FloatArray,
        from: Int,
        to: Int,
    ): Float {
        var acc = 0f
        for (i in from until to) acc += values[i]
        return acc / (to - from)
    }

    companion object {
        /**
         * How far above the peak-picking threshold [onset] saturates. The
         * continuous readout should still be climbing when the discrete
         * detector fires, or it would read 1 on every onset and grade nothing.
         */
        private const val ONSET_HEADROOM = 2f

        /** Memory of [macroEnergy]'s reference peak. */
        private const val MACRO_PEAK_SECONDS = 20f

        /** Low-band weight in the bar's accent evidence; see the [bar] step. */
        private const val KICK_ACCENT_WEIGHT = 0.5f

        /**
         * Continuous silence before tempo stability starts draining. Long
         * enough that a rest, a breakdown bar or a sparse kick pattern's
         * gaps hold their certainty; short enough that a real between-track
         * gap arrives at the next track with nothing inherited.
         */
        private const val STABILITY_SILENCE_HOLD_SECONDS = 2f

        /**
         * Window RMS below which the input is silence rather than quiet music.
         * About -100 dBFS: under the noise floor of any real master, above the
         * denormals a decoder can leave behind.
         */
        private const val SILENCE_RMS = 1e-5f
    }
}
