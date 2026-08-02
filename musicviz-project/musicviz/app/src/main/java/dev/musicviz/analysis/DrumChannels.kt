package dev.musicviz.analysis

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Three band-limited onset channels - kick, snare and hat - from the same
 * per-frame band spectra [FeatureExtractor] already computes.
 *
 * ## Why this exists
 *
 * [PulseTracker] produces ONE onset stream. It is a good one - tempo-locked,
 * graded, budgeted - but it answers "something was hit", and a visual that
 * wants "the kick shakes the camera, the hat sparkles the particles" cannot be
 * built from it. The information needed to tell those apart is already present
 * and already free: a kick and a hi-hat occupy disjoint parts of the spectrum,
 * and the spectrum arrives here 60 times a second.
 *
 * So this is the same positive-spectral-flux measurement [FeatureExtractor]
 * makes across the whole spectrum, made three times over three disjoint band
 * ranges instead, each with its own rolling threshold and its own refractory.
 * The cost is three more passes over a 64-float array per frame.
 *
 * ## What it publishes
 *
 * Graded ONE-FRAME impulses in 0..1, zero between hits - deliberately the same
 * contract as [AudioFeatures.beatStrength] and [AudioFeatures.transient], so a
 * consumer builds its own envelope and two consumers wanting different decay
 * rates do not fight. A value is the hit's strength relative to that channel's
 * own recent dynamics, so a track mixed quiet still reaches 1 on its hardest
 * kick.
 *
 * ## What it is not
 *
 * Not source separation, and not a classifier. A bass guitar note lands in the
 * kick channel; a crash lands in the hat channel and so does vocal sibilance;
 * a rimshot may land in both snare and hat. These are BAND ACTIVITY channels
 * named after what usually dominates them, which is all a visual needs and all
 * that is affordable at 60 Hz on a phone. Anything stronger means a real
 * classifier or an NMF decomposition, which is a different project.
 *
 * Pure JVM, allocation-free after construction, stateful and ordered: [step]
 * must be fed every frame, in order, like [FeatureExtractor.BeatGate.accept].
 */
class DrumChannels(
    private val bandCount: Int = 64,
    private val hopRateHz: Float = 60f,
    sampleRateHz: Int = 48_000,
    historySeconds: Float = HISTORY_SECONDS,
) {
    private val prevBands = FloatArray(bandCount)

    /**
     * The three ranges, as half-open band indices.
     *
     * Derived from frequencies rather than from fractions of [bandCount],
     * because the band spacing is logarithmic between [FftProcessor.MIN_FREQ_HZ]
     * and Nyquist - so a fixed fraction of the array means a different band of
     * the SPECTRUM at 16 kHz (a mic capture) than at 48 kHz (a file). Fractions
     * were the first version of this and they silently pointed the hat channel
     * at 2 kHz on mic input.
     *
     * The gaps between the ranges are deliberate. Adjacent channels sharing a
     * band would make one hit register on two channels, which is exactly the
     * thing a consumer is trying to distinguish; a few hundred Hz of no-man's
     * land buys separation far more cheaply than any post-hoc de-correlation.
     */
    private val kickRange = BandSpan(sampleRateHz, KICK_LO_HZ, KICK_HI_HZ)
    private val snareRange = BandSpan(sampleRateHz, SNARE_LO_HZ, SNARE_HI_HZ)
    private val hatRange = BandSpan(sampleRateHz, HAT_LO_HZ, HAT_HI_HZ)

    /**
     * A half-open band span. Deliberately not an `IntRange`: the ranges here
     * are half-open and `IntRange` is inclusive, so the two would disagree
     * about the top band the first time anyone iterated one.
     */
    private inner class BandSpan(
        sampleRateHz: Int,
        loHz: Float,
        hiHz: Float,
    ) {
        val from = bandIndexForHz(loHz, sampleRateHz, bandCount)

        /** Exclusive, and at least one band wide even if the rate squeezes the
         *  span to nothing, so a channel is never silently dead. */
        val until = maxOf(bandIndexForHz(hiHz, sampleRateHz, bandCount), from + 1)

        operator fun contains(band: Int) = band >= from && band < until
    }

    /**
     * Per-channel refractory windows, in milliseconds. A kick cannot
     * physically repeat as fast as a hat can, and letting it try is how a
     * sustained bass note turns into a machine-gun trigger. 16th notes at
     * 180 BPM are 83 ms apart, which is what the hat window has to clear.
     */
    private val kick = Channel(KICK_REFRACTORY_MS, historySeconds)
    private val snare = Channel(SNARE_REFRACTORY_MS, historySeconds)
    private val hat = Channel(HAT_REFRACTORY_MS, historySeconds)

    /** Graded 0..1 impulse for this frame; 0 on frames with no hit. */
    var kickImpulse: Float = 0f
        private set

    var snareImpulse: Float = 0f
        private set

    var hatImpulse: Float = 0f
        private set

    /** See [FeatureExtractor.reset] - forgets one piece of audio, not settings. */
    fun reset() {
        java.util.Arrays.fill(prevBands, 0f)
        kick.reset()
        snare.reset()
        hat.reset()
        kickImpulse = 0f
        snareImpulse = 0f
        hatImpulse = 0f
    }

    /**
     * Feeds one frame's band spectrum. [bands] is read, never retained.
     *
     * The previous-frame copy is taken ONCE for all three channels rather than
     * per channel, so the three ranges see the same difference and a band
     * cannot be charged to two channels at different times.
     */
    fun step(bands: FloatArray) {
        var kf = 0f
        var sf = 0f
        var hf = 0f
        for (i in 0 until bandCount) {
            val v = bands[i]
            val rise = v - prevBands[i]
            prevBands[i] = v
            if (rise <= 0f) continue
            if (i in kickRange) kf += rise
            if (i in snareRange) sf += rise
            if (i in hatRange) hf += rise
        }
        kickImpulse = kick.accept(kf)
        snareImpulse = snare.accept(sf)
        hatImpulse = hat.accept(hf)
    }

    /**
     * One channel: a rolling mean/std window over its own flux, a sigma gate,
     * a refractory countdown and a linear grade above the threshold.
     *
     * Structurally [FeatureExtractor.BeatGate] with the grading of
     * [PulseTracker.grade] folded in, kept separate rather than reused because
     * BeatGate carries the two user-facing Settings sliders and these channels
     * are deliberately not user-tunable - three more sensitivity sliders would
     * be three more ways to make the visuals stop responding.
     */
    private inner class Channel(
        refractoryMs: Float,
        historySeconds: Float,
    ) {
        private val historySize = (hopRateHz * historySeconds).toInt().coerceAtLeast(1)
        private val history = FloatArray(historySize)
        private val refractoryFrames = (hopRateHz * refractoryMs / 1000f).roundToInt().coerceAtLeast(1)
        private var index = 0
        private var filled = 0
        private var sinceHit = Int.MAX_VALUE / 2

        fun reset() {
            java.util.Arrays.fill(history, 0f)
            index = 0
            filled = 0
            sinceHit = Int.MAX_VALUE / 2
        }

        fun accept(flux: Float): Float {
            history[index] = flux
            index = (index + 1) % historySize
            filled = minOf(filled + 1, historySize)

            var mean = 0f
            for (i in 0 until filled) mean += history[i]
            mean /= filled
            var variance = 0f
            for (i in 0 until filled) {
                val d = history[i] - mean
                variance += d * d
            }
            val std = sqrt(variance / filled)

            // The absolute floor is what keeps silence and room tone silent:
            // std collapses toward zero on a quiet passage, so a z-score alone
            // would happily fire on dither.
            val z = if (std > 1e-6f) (flux - mean) / std else 0f
            val hit = flux > FLUX_FLOOR && z > SIGMA && sinceHit > refractoryFrames
            sinceHit = if (hit) 0 else sinceHit + 1
            if (!hit) return 0f
            val t = ((z - SIGMA) / STRENGTH_SPAN_SIGMA).coerceIn(0f, 1f)
            return STRENGTH_FLOOR + (1f - STRENGTH_FLOOR) * t
        }
    }

    companion object {
        /** Kick fundamental and first harmonic; above this is bass-guitar territory. */
        const val KICK_LO_HZ = 40f
        const val KICK_HI_HZ = 130f

        /** Snare body and the low half of its noise burst. */
        const val SNARE_LO_HZ = 200f
        const val SNARE_HI_HZ = 1_800f

        /** Hats, cymbal wash and sibilance. */
        const val HAT_LO_HZ = 5_000f
        const val HAT_HI_HZ = 16_000f

        /** 16th notes at 180 BPM are 83 ms apart - the hat has to clear that. */
        const val KICK_REFRACTORY_MS = 110f
        const val SNARE_REFRACTORY_MS = 95f
        const val HAT_REFRACTORY_MS = 55f

        /**
         * Shorter than [FeatureExtractor.HISTORY_SECONDS]. That window sizes a
         * threshold for a decision the user tunes and must be stable over a
         * whole section; these are per-instrument and should follow a fill.
         */
        const val HISTORY_SECONDS = 3f

        /** Sigmas over the channel's own rolling mean before a hit is declared. */
        const val SIGMA = 2.2f

        /** Sigmas above [SIGMA] at which a hit grades to full strength. */
        const val STRENGTH_SPAN_SIGMA = 2.5f

        /** A hit that only just cleared the gate still reads as a hit. */
        const val STRENGTH_FLOOR = 0.35f

        /** Absolute floor under the sigma gate; silence must never fire. */
        const val FLUX_FLOOR = 0.01f

        /**
         * Band index containing [hz], under the log spacing [FftProcessor]
         * uses. Clamped to the array, so a frequency above Nyquist lands on
         * the last band rather than off the end.
         */
        fun bandIndexForHz(
            hz: Float,
            sampleRateHz: Int,
            bandCount: Int,
            minFreqHz: Float = FftProcessor.MIN_FREQ_HZ,
        ): Int {
            val nyquist = sampleRateHz / 2f
            if (nyquist <= minFreqHz) return 0
            val span = ln(nyquist / minFreqHz)
            val at = ln(hz.coerceAtLeast(minFreqHz) / minFreqHz)
            return (bandCount * at / span).toInt().coerceIn(0, bandCount)
        }
    }
}
