package dev.musicviz.engine.audio

import kotlin.math.roundToInt

/**
 * §5.5's adaptive live mode: a causal, robust floor and ceiling tracked from
 * the material itself, mapped to `0..1`.
 *
 * The mode for live audio of unknown level — a phone mic, a quiet master, a
 * loudness-war master — where no fixed range fits all of them and a preset
 * authored against one would read flat or clipped against the next.
 *
 * ## Robust means bounded influence, and that is a property, not an adjective
 *
 * The bounds are online quantile estimates, not followers of the running
 * min and max. Each frame moves a bound by one step, up or down, and the
 * step depends on the range already tracked — never on how far away the new
 * value is. So a single frame of any magnitude, a decoder glitch or a
 * full-scale click, moves the ceiling by at most one step; a max-follower
 * would move it all the way and leave every later frame reading near zero
 * until it released. `AdaptiveRangeTest` measures exactly that.
 *
 * The asymmetry §5.5 asks for falls out of which quantile is being tracked
 * rather than being tuned separately: the ceiling sits at `1 - tailFraction`,
 * so it rises in steps 19× larger than it falls, and the floor mirrors it. Fast
 * attack, slow release, from one number.
 *
 * At rest the mapping puts the material's [tailFraction] and
 * `1 - tailFraction` quantiles at 0 and 1, so the loudest and quietest few
 * percent clip instead of compressing everything between them.
 *
 * ## Silence does not train
 *
 * A silent frame returns to rest and changes nothing. Training on silence
 * would drag the floor down through a rest or a gap between tracks, and the
 * first sound afterwards would read full scale against it. That is observably
 * what the app's `PulseTracker.EnergyFollower` does — its rolling peak decays
 * through a silence, so the next sound of any level reads near 1 — which suits
 * a track-relative energy envelope and would be a false ceiling here.
 *
 * Silence therefore never releases the range, and [reset] is what handles a
 * genuinely new source. §5.5 pairs the two for this reason; using one without
 * the other leaves either a stale scale or a scale that forgets during rests.
 */
class AdaptiveRange(
    hopRateHz: Float,
    /**
     * The narrowest range worth stretching to `0..1`, in the feature's own
     * units — an RMS in `0..1` and a centroid in hertz do not share one.
     *
     * Required rather than defaulted because no default can be right for both,
     * and a wrong one shows up only on material with no dynamics, where it
     * decides whether the output rests near zero or flickers full scale.
     */
    val minimumSpan: Float,
    private val tailFraction: Float = TAIL_FRACTION,
    adaptSeconds: Float = ADAPT_SECONDS,
    warmupSeconds: Float = WARMUP_SECONDS,
) : Normalizer {
    init {
        require(hopRateHz > 0f) { "hopRateHz must be positive, was $hopRateHz" }
        require(minimumSpan > 0f && minimumSpan.isFinite()) { "minimumSpan must be positive, was $minimumSpan" }
        require(tailFraction > 0f && tailFraction < 0.5f) { "tailFraction must be inside 0..0.5, was $tailFraction" }
        require(adaptSeconds > 0f) { "adaptSeconds must be positive, was $adaptSeconds" }
        require(warmupSeconds >= 0f) { "warmupSeconds must not be negative, was $warmupSeconds" }
    }

    /** Fraction of the tracked range a bound may move in one frame. */
    private val stepFraction = (1f / hopRateHz) / adaptSeconds
    private val warmupFrames = (warmupSeconds * hopRateHz).roundToInt().coerceAtLeast(1)

    private var low = 0f
    private var high = 0f
    private var soundingFrames = 0

    override var validity: FeatureValidity = FeatureValidity.Warmup
        private set

    /**
     * The tracked lower bound. Above [trackedCeiling] only on material with no
     * dynamic range at all, where the two quantiles coincide and both dither
     * around it; [minimumSpan] is what keeps the mapping finite there.
     */
    val trackedFloor: Float get() = low

    /** The tracked upper bound. See [trackedFloor] for the degenerate case. */
    val trackedCeiling: Float get() = high

    override fun normalize(
        raw: Float,
        activity: FrameActivity,
    ): Float {
        when (activity) {
            FrameActivity.Silent -> {
                validity = FeatureValidity.Silent
                return 0f
            }
            FrameActivity.Sounding -> train(raw)
        }
        return ((raw - low) / maxOf(high - low, minimumSpan)).coerceIn(0f, 1f)
    }

    private fun train(raw: Float) {
        when {
            soundingFrames == 0 -> {
                low = raw
                high = raw
            }
            soundingFrames < warmupFrames -> {
                // Plain extremes while warming up. The tracker below moves by a
                // fraction of the range it already holds, so from a zero-width
                // seed it would crawl; starting wide and letting the quantiles
                // draw it in also means nothing clips before the range is known.
                if (raw < low) low = raw
                if (raw > high) high = raw
            }
            else -> {
                val step = maxOf(high - low, minimumSpan) * stepFraction
                high += if (raw > high) step * (1f - tailFraction) else -step * tailFraction
                low += if (raw < low) -step * (1f - tailFraction) else step * tailFraction
            }
        }
        soundingFrames++
        validity = if (soundingFrames >= warmupFrames) FeatureValidity.Valid else FeatureValidity.Warmup
    }

    override fun reset() {
        low = 0f
        high = 0f
        soundingFrames = 0
        validity = FeatureValidity.Warmup
    }

    companion object {
        /**
         * Where the bounds sit: the 5th and 95th percentiles of the material.
         *
         * Also the attack/release ratio, since a bound rises with weight
         * `1 - tailFraction` and falls with weight `tailFraction`: 19:1 here.
         */
        const val TAIL_FRACTION = 0.05f

        /**
         * Seconds a bound would need to cross the whole tracked range at one
         * full step per frame — so about this long for a bound to rise across
         * it, and about twenty times as long to fall back.
         */
        const val ADAPT_SECONDS = 1.5f

        /** Long enough for a phrase, so the extremes are a range and not one note. */
        const val WARMUP_SECONDS = 1.0f
    }
}
