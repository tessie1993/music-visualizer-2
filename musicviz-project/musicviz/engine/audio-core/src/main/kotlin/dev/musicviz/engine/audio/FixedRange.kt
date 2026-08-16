package dev.musicviz.engine.audio

/**
 * §5.5's fixed mode: a documented physical or musical range, mapped to `0..1`.
 *
 * The mode for repeatable presets. Nothing here adapts, so the same audio
 * always produces the same number — which is what lets a preset authored on
 * one track look the same on another, and what lets an offline render match a
 * live one sample for sample.
 *
 * The app already normalizes this way in `FftProcessor`, against a −72 dBFS
 * floor built into the class. Pulling the range out to the call site is the
 * change: a range that belongs to the feature can be documented in the ABI,
 * varied per feature, and read back by a preset editor, none of which is true
 * of a constant buried in a processor.
 *
 * Values outside `[min, max]` clamp rather than extrapolate. A preset binding
 * cannot usefully consume 1.4, and clamping is the behaviour the range's
 * documentation can actually promise.
 */
class FixedRange(
    val min: Float,
    val max: Float,
) : Normalizer {
    init {
        require(min.isFinite() && max.isFinite()) { "the range must be finite, was $min..$max" }
        require(max > min) { "max ($max) must be above min ($min)" }
    }

    private val span = max - min

    override var validity: FeatureValidity = FeatureValidity.Warmup
        private set

    override fun normalize(
        raw: Float,
        activity: FrameActivity,
    ): Float =
        when (activity) {
            FrameActivity.Silent -> {
                validity = FeatureValidity.Silent
                0f
            }
            FrameActivity.Sounding -> {
                validity = FeatureValidity.Valid
                ((raw - min) / span).coerceIn(0f, 1f)
            }
        }

    /**
     * Only clears [validity]: there is no learned state to forget, which is
     * the whole point of the mode.
     */
    override fun reset() {
        validity = FeatureValidity.Warmup
    }
}
