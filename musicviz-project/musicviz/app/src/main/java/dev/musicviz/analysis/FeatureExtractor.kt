package dev.musicviz.analysis

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Derives musical features from per-frame band spectra: RMS proxy, band
 * energy groups, spectral-flux onsets, beat pulses and a BPM estimate via
 * autocorrelation of the onset envelope. Pure JVM; one instance per worker.
 *
 * The beat decision itself lives in [PulseTracker] (candidate gating in
 * [BeatGate], tempo-phase locking and strength grading above it), so that the
 * live path and the offline/cached path can run byte-identical code over the
 * same onset curve.
 */
class FeatureExtractor(
    private val bandCount: Int = 64,
    private val hopRateHz: Float = 60f,
    historySeconds: Float = HISTORY_SECONDS,
) {
    private val prevBands = FloatArray(bandCount)

    /**
     * Per-band weights for the onset/beat flux. Beats are tracked from kick
     * and snare energy (bass + low mids); hi-hats, cymbals and vocal sibilance
     * live in the upper bands and were previously counted at full weight,
     * which made the beat gate fire on every 16th-note hat - the visible
     * result was constant flicker of flash/pulse/strobe on busy tracks. This
     * mirrors how DJ software (e.g. rekordbox) weights onset detection.
     */
    private val fluxWeights =
        FloatArray(bandCount) { i ->
            when {
                i < bandCount / 8 -> 1f
                i < bandCount / 4 -> 0.8f
                i < bandCount / 2 -> 0.3f
                else -> 0.1f
            }
        }

    /** Tempo-phase-locked pulse tracking over the gated onset stream; owns
     *  the rolling flux history and the BPM autocorrelation. */
    private val tracker = PulseTracker(hopRateHz, historySeconds)

    /**
     * Beat sensitivity in sigmas over mean flux; higher = fewer, surer beats.
     * Clamped by callers to [SIGMA_MIN]..[SIGMA_MAX].
     */
    var beatThresholdSigma: Float
        get() = tracker.gate.beatThresholdSigma
        set(value) {
            tracker.gate.beatThresholdSigma = value
        }

    /**
     * Refractory window: no second beat may fire until this many milliseconds
     * have passed. This is the lever that actually tames slow tracks - a high
     * sigma alone cannot, because sigma is measured against the track's own
     * flux history, so a quiet ballad re-normalises and keeps firing on
     * sustained pads, reverb tails and vibrato. Capping the rate directly
     * ("at most one flash per 700 ms") is what a listener perceives.
     * Clamped by callers to [INTERVAL_MS_MIN]..[INTERVAL_MS_MAX].
     */
    var beatMinIntervalMs: Float
        get() = tracker.gate.beatMinIntervalMs
        set(value) {
            tracker.gate.beatMinIntervalMs = value
        }

    /** Extra smoothing for the treble group, which is jumpy on hi-hats and was
     *  a flicker source when driving flash/strobe-style params. */
    private var trebleSmooth = 0f
    private var bpmSmoothed = 0f

    fun extract(
        bands: FloatArray,
        waveform: FloatArray,
        sampleRateHz: Int,
    ): AudioFeatures {
        var flux = 0f
        var sum = 0f
        var sumSq = 0f
        var weighted = 0f
        for (i in 0 until bandCount) {
            val v = bands[i]
            flux += max(0f, v - prevBands[i]) * fluxWeights[i]
            prevBands[i] = v
            sum += v
            sumSq += v * v
            weighted += v * i
        }
        val centroid = if (sum > 1e-6f) weighted / (sum * bandCount) else 0f
        val rms = sqrt(sumSq / bandCount)

        // The sigma threshold and refractory window (both user-tunable via
        // Settings > Visuals & Analysis) produce beat CANDIDATES; the tracker
        // then holds them against a tempo-phase grid so that only candidates
        // landing where the music's pulse predicts them - or unmistakable
        // accents - fire, each graded by how hard it actually hit. That is
        // what turned "every transient flashes at full strength" into a beat
        // stream a scene can follow; see [PulseTracker] for the model.
        tracker.step(flux, rms)
        val mean = tracker.gate.fluxMean
        val std = tracker.gate.fluxStd

        val bpm = tracker.bpmEstimate
        if (bpm > 0f) bpmSmoothed = if (bpmSmoothed == 0f) bpm else bpmSmoothed + (bpm - bpmSmoothed) * 0.1f

        return AudioFeatures(
            bands = bands.copyOf(),
            waveform = waveform.copyOf(),
            rms = rms,
            bass = groupEnergy(bands, 0, bandCount / 8),
            mid = groupEnergy(bands, bandCount / 8, bandCount / 2),
            treble = smoothTreble(groupEnergy(bands, bandCount / 2, bandCount)),
            onset = if (std > 1e-6f) ((flux - mean) / (3f * std)).coerceIn(0f, 1f) else 0f,
            beat = tracker.beat,
            bpm = bpmSmoothed,
            centroid = centroid,
            flux = flux,
            beatStrength = tracker.strength,
            beatPhase = tracker.phase,
            pulseConfidence = tracker.confidence,
            macroEnergy = tracker.energy,
        )
    }

    private fun smoothTreble(raw: Float): Float {
        val a = if (raw > trebleSmooth) 0.35f else 0.10f
        trebleSmooth += (raw - trebleSmooth) * a
        return trebleSmooth
    }

    private fun groupEnergy(
        bands: FloatArray,
        from: Int,
        to: Int,
    ): Float {
        var acc = 0f
        for (i in from until to) acc += bands[i]
        return acc / (to - from)
    }

    /**
     * The beat CANDIDATE decision - sigma threshold plus refractory window -
     * split out of [FeatureExtractor] so that exactly one implementation
     * exists. [PulseTracker] drives it frame by frame (live) or replays it
     * over the onset curve stored by [AnalysisCache] (see [decideBeats]).
     * Same curve + same settings => same beats, which is what keeps an
     * exported video in step with what the user just watched during playback.
     *
     * Stateful and ordered: [accept] must be fed every frame, in order.
     */
    class BeatGate(
        private val hopRateHz: Float,
        historySeconds: Float = HISTORY_SECONDS,
    ) {
        private val historySize = (hopRateHz * historySeconds).toInt().coerceAtLeast(1)
        private val fluxHistory = FloatArray(historySize)
        private var historyIndex = 0
        private var historyFilled = 0
        private var framesSinceBeat = 100

        /** See [FeatureExtractor.beatThresholdSigma]. Volatile: the Settings slider writes it off-thread. */
        @Volatile
        var beatThresholdSigma: Float = SIGMA_DEFAULT

        /** See [FeatureExtractor.beatMinIntervalMs]. Volatile: the Settings slider writes it off-thread. */
        @Volatile
        var beatMinIntervalMs: Float = INTERVAL_MS_DEFAULT

        /** Mean flux over the history window as of the last [accept]. */
        var fluxMean: Float = 0f
            private set

        /** Standard deviation of the flux history as of the last [accept]. */
        var fluxStd: Float = 0f
            private set

        /** Number of frames currently held in the history window. */
        val filled: Int get() = historyFilled

        /** Capacity of the history window, in frames. */
        val size: Int get() = historySize

        /** Feeds one frame's flux and returns whether it is a beat. */
        fun accept(flux: Float): Boolean {
            fluxHistory[historyIndex] = flux
            historyIndex = (historyIndex + 1) % historySize
            historyFilled = minOf(historyFilled + 1, historySize)
            updateStats()
            val threshold = fluxMean + beatThresholdSigma * fluxStd
            val refractoryFrames = (hopRateHz * beatMinIntervalMs / 1000f).roundToInt().coerceAtLeast(1)
            val isBeat = flux > threshold && flux > FLUX_FLOOR && framesSinceBeat > refractoryFrames
            framesSinceBeat = if (isBeat) 0 else framesSinceBeat + 1
            return isBeat
        }

        /** History sample [i] in chronological order (0 = oldest retained). */
        fun chronological(i: Int): Float {
            val start = if (historyFilled < historySize) 0 else historyIndex
            return fluxHistory[(start + i) % historySize]
        }

        private fun updateStats() {
            if (historyFilled == 0) {
                fluxMean = 0f
                fluxStd = 0f
                return
            }
            var mean = 0f
            for (i in 0 until historyFilled) mean += fluxHistory[i]
            mean /= historyFilled
            var variance = 0f
            for (i in 0 until historyFilled) {
                val d = fluxHistory[i] - mean
                variance += d * d
            }
            fluxMean = mean
            fluxStd = sqrt(variance / historyFilled)
        }
    }

    /**
     * Single source of truth for the beat-detection bounds. [AnalysisEngine]
     * clamps to these and the Settings sliders use them as their value range,
     * so the slider can never silently saturate against a tighter clamp.
     */
    companion object {
        /** Most sensitive usable threshold; below this the gate fires on noise. */
        const val SIGMA_MIN = 1.5f

        /**
         * Least sensitive threshold. 6 sigma is the practical ceiling, not an
         * arbitrary one: with a 6 s flux history at ~60 Hz (~375 samples), a
         * beat occurring every ~0.9 s leaves p ~= 1/56 of the window above the
         * baseline, and the largest z-score such a sample can reach is
         * sqrt((1 - p) / p) ~= 7.4. Real onsets spread over several frames and
         * land well under that, so anything past ~6 would mean "never fire" for
         * most material - a mute switch rather than a sensitivity control.
         */
        const val SIGMA_MAX = 6f

        /** Ships-with default; unchanged from before the range was widened. */
        const val SIGMA_DEFAULT = 2.5f

        /** 200 ms refractory = 300 BPM ceiling; fast enough for drum & bass. */
        const val INTERVAL_MS_MIN = 200f

        /** 1200 ms = 50 BPM ceiling, below the slowest common ballad tempo. */
        const val INTERVAL_MS_MAX = 1200f

        /** 333 ms (180 BPM ceiling): the value hard-coded before it was tunable. */
        const val INTERVAL_MS_DEFAULT = 1000f / 3f

        /** "Slow track" preset: strict threshold, at most ~85 flashes/minute. */
        const val SLOW_SIGMA = 4.5f

        /** "Slow track" preset partner value for [beatMinIntervalMs]. */
        const val SLOW_INTERVAL_MS = 700f

        /** Length of the rolling flux window the sigma threshold is measured against. */
        const val HISTORY_SECONDS = 6f

        /** Absolute floor under the sigma gate: silence must never flag a beat. */
        const val FLUX_FLOOR = 0.02f

        /**
         * Re-decides the beat flags for a whole onset curve - the offline
         * counterpart of the live per-frame path, used when a cached timeline
         * is read back at the user's *current* sensitivity. [flux] must be the
         * per-frame values in order, [hopRateHz] the rate they were produced
         * at (the refractory, the history window and the tempo grid are all
         * measured in frames). Settings are clamped exactly as
         * [AnalysisEngine] clamps them, so live and offline cannot drift
         * apart at the extremes.
         *
         * Runs the full [PulseTracker], not just the raw gate: the flags it
         * returns are the tempo-gated beats live analysis shows. The beat
         * decision is independent of the rms curve (energy only grades
         * strength), so flux alone reproduces it exactly; callers that also
         * need the graded strength/phase/energy curves should use
         * [PulseTracker.decidePulse] with the stored rms directly.
         */
        fun decideBeats(
            flux: FloatArray,
            hopRateHz: Float,
            beatThresholdSigma: Float,
            beatMinIntervalMs: Float,
            historySeconds: Float = HISTORY_SECONDS,
        ): BooleanArray =
            PulseTracker
                .decidePulse(
                    flux,
                    FloatArray(0),
                    hopRateHz,
                    beatThresholdSigma,
                    beatMinIntervalMs,
                    historySeconds,
                ).beat
    }
}
