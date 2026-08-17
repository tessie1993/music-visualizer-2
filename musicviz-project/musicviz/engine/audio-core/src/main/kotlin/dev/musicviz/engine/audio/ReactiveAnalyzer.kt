package dev.musicviz.engine.audio

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

    /** Band-limited onset channels; see [kick]. */
    private val drumPickers = Array(DRUM_CHANNELS) { OnsetPeakPicker(hopRateHz, refractorySeconds = 0.05f) }
    private val drumFlux = Array(DRUM_CHANNELS) { SuperFlux(bandCount) }
    private val drumBands = Array(DRUM_CHANNELS) { FloatArray(bandCount) }
    private val drumRange = IntArray(DRUM_CHANNELS * 2)

    /** Smoothed band levels, the array a scene reads. Valid after [analyze]. */
    val bands: FloatArray = FloatArray(bandCount)
    private val smoothingState = FloatArray(bandCount)

    /** Sample rate of the audio being analyzed; rebuilds the band tables when set. */
    var sampleRateHz: Int = sampleRateHz
        set(value) {
            if (value != field) {
                field = value
                logBands.sampleRateHz = value
                rebuildDrumRanges()
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
            for (p in drumPickers) p.sensitivity = value
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

    /** 0..1 while the adaptive range is still learning this track's dynamics. */
    val warmup: Float get() = range.warmup

    private var levelPeak = 0f

    init {
        rebuildDrumRanges()
    }

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
            silenceOutputs()
            return
        }

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
        beat = grid.step(tempo.periodFrames, tempo.confidence, isOnset)
        beatPhase = grid.phase
        beatStrength = if (beat) picker.strength else 0f

        stepDrums()
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
        for (p in drumPickers) p.reset()
        for (f in drumFlux) f.reset()
        smoothingState.fill(0f)
        bands.fill(0f)
        levelPeak = 0f
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
        macroEnergy = 0f
        kick = 0f
        snare = 0f
        hat = 0f
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

    private fun stepDrums() {
        for (c in 0 until DRUM_CHANNELS) {
            val from = drumRange[c * 2]
            val to = drumRange[c * 2 + 1]
            val slice = drumBands[c]
            // Zero outside the channel's range so the shared-width SuperFlux
            // sees only this channel's bands.
            for (b in 0 until bandCount) slice[b] = if (b in from..to) whitened[b] else 0f
            val fired = drumPickers[c].accept(drumFlux[c].next(slice))
            val impulse = if (fired) drumPickers[c].strength else 0f
            when (c) {
                0 -> kick = impulse
                1 -> snare = impulse
                else -> hat = impulse
            }
        }
    }

    /**
     * Maps the three drum channels onto band indices by frequency, so they
     * follow the band layout instead of assuming it — a 16 kHz mic capture and
     * a 48 kHz file put the same frequency in very different bands.
     */
    private fun rebuildDrumRanges() {
        val edges = floatArrayOf(30f, 120f, 120f, 900f, 4_000f, 16_000f)
        for (c in 0 until DRUM_CHANNELS) {
            drumRange[c * 2] = bandContaining(edges[c * 2])
            drumRange[c * 2 + 1] = bandContaining(edges[c * 2 + 1])
        }
    }

    private fun bandContaining(hz: Float): Int {
        for (b in 0 until bandCount) if (hz <= logBands.upperHz(b)) return b
        return bandCount - 1
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
        /** Kick, snare and hat band-activity channels. */
        private const val DRUM_CHANNELS = 3

        /**
         * How far above the peak-picking threshold [onset] saturates. The
         * continuous readout should still be climbing when the discrete
         * detector fires, or it would read 1 on every onset and grade nothing.
         */
        private const val ONSET_HEADROOM = 2f

        /** Memory of [macroEnergy]'s reference peak. */
        private const val MACRO_PEAK_SECONDS = 20f

        /**
         * Window RMS below which the input is silence rather than quiet music.
         * About -100 dBFS: under the noise floor of any real master, above the
         * denormals a decoder can leave behind.
         */
        private const val SILENCE_RMS = 1e-5f
    }
}
