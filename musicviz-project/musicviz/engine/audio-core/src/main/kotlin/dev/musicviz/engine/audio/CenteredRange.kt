package dev.musicviz.engine.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * §5.5's centered mode: a bipolar standardized value for modulation, `-1..1`.
 *
 * "Is this louder or quieter than it has been lately, and by how much" — which
 * is the question a modulation target asks. A unipolar range cannot answer it:
 * bound a pitch offset or a rotation rate to [AdaptiveRange] and the effect
 * only ever pushes one way, so it has to be biased back by hand in every
 * preset that uses it. Zero here means "as usual", and the sign carries the
 * direction.
 *
 * ## Standardized, so the scale comes from the feature's own spread
 *
 * The value is `(raw - mean) / (deviations × deviation)`, clamped. Both the
 * mean and the deviation are exponentially weighted over [ADAPT_SECONDS], and
 * the deviation is the mean absolute deviation of the prediction error — for
 * gaussian material `MAD = 0.798 σ`, so the default two deviations is about
 * 1.6 σ and roughly the middle 89% of frames land inside `-1..1`.
 *
 * Mean absolute deviation rather than a running variance because it needs no
 * square root per hop and is the less outlier-sensitive of the two. It is not
 * outlier-*bounded* the way [AdaptiveRange] is — a large enough frame does
 * move the mean — which is the honest limit of standardizing anything. The
 * clamp is what keeps that from leaving the range.
 *
 * Material with no spread at all reads exactly zero: nothing deviates from its
 * own average, so there is nothing to modulate with.
 */
class CenteredRange(
    hopRateHz: Float,
    /**
     * The smallest deviation worth dividing by, in the feature's own units.
     * See [AdaptiveRange.minimumSpan]; the same argument applies, and here it
     * is what makes a dead-constant feature read zero rather than divide by
     * one.
     */
    val minimumDeviation: Float,
    private val deviations: Float = DEVIATIONS,
    adaptSeconds: Float = ADAPT_SECONDS,
    warmupSeconds: Float = WARMUP_SECONDS,
) : Normalizer {
    init {
        require(hopRateHz > 0f) { "hopRateHz must be positive, was $hopRateHz" }
        require(minimumDeviation > 0f && minimumDeviation.isFinite()) {
            "minimumDeviation must be positive, was $minimumDeviation"
        }
        require(deviations > 0f) { "deviations must be positive, was $deviations" }
        require(adaptSeconds > 0f) { "adaptSeconds must be positive, was $adaptSeconds" }
        require(warmupSeconds >= 0f) { "warmupSeconds must not be negative, was $warmupSeconds" }
    }

    /** One-pole coefficient: 63% of a step reached after [adaptSeconds]. */
    private val coefficient = 1f - exp(-1f / (hopRateHz * adaptSeconds))
    private val warmupFrames = (warmupSeconds * hopRateHz).roundToInt().coerceAtLeast(1)

    private var mean = 0f
    private var deviation = 0f
    private var soundingFrames = 0

    override var validity: FeatureValidity = FeatureValidity.Warmup
        private set

    /** What the feature has been averaging, in its own units. */
    val trackedMean: Float get() = mean

    /** Mean absolute deviation about [trackedMean], in the feature's own units. */
    val trackedDeviation: Float get() = deviation

    override fun normalize(
        raw: Float,
        activity: FrameActivity,
    ): Float {
        when (activity) {
            FrameActivity.Silent -> {
                validity = FeatureValidity.Silent
                return 0f
            }
            FrameActivity.Sounding -> Unit
        }
        // Seeded before the error is taken, so the first frame of a session is
        // exactly zero — it has no average to be above or below yet, and a
        // reset that let it read full scale would fire every modulation bound
        // to this feature on the first frame of every track.
        if (soundingFrames == 0) mean = raw
        // Against the mean as it stood before this frame, so the deviation
        // measures how far off the running average actually was rather than
        // how far off it is once it has already moved to meet this value.
        val error = raw - mean
        if (soundingFrames > 0) {
            mean += error * coefficient
            deviation += (abs(error) - deviation) * coefficient
        }
        // Stops at the warmup length; see AdaptiveRange for why a counter
        // that kept going would strand the feature in warmup eventually.
        if (soundingFrames < warmupFrames) soundingFrames++
        validity = if (soundingFrames >= warmupFrames) FeatureValidity.Valid else FeatureValidity.Warmup
        return (error / (deviations * maxOf(deviation, minimumDeviation))).coerceIn(-1f, 1f)
    }

    override fun reset() {
        mean = 0f
        deviation = 0f
        soundingFrames = 0
        validity = FeatureValidity.Warmup
    }

    companion object {
        /** Deviations mapping to full scale. Two of them is about 1.6 σ on gaussian material. */
        const val DEVIATIONS = 2f

        /** Long enough to average over a phrase rather than a bar. */
        const val ADAPT_SECONDS = 3f

        /** Long enough that the deviation is a spread and not one interval. */
        const val WARMUP_SECONDS = 1.0f
    }
}
