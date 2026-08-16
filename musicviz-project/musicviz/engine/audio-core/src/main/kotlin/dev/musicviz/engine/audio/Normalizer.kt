package dev.musicviz.engine.audio

/**
 * One raw feature value turned into a range a preset can bind to.
 *
 * MASTER_PLAN §5.5 names exactly three modes and this is the closed set of
 * them: [FixedRange] for repeatable presets, [AdaptiveRange] for live material
 * of unknown level, [CenteredRange] for bipolar modulation. A feature
 * definition picks one; nothing picks "some smoothing".
 *
 * ## The value and its validity are two slots, not one
 *
 * [normalize] returns the number and [validity] describes it, matching §5.4's
 * ABI where the validity mask is its own slot. Returning a pair instead would
 * allocate once per feature per hop, and §14's budget for the analysis path is
 * zero steady-state allocation — so the contract is that [validity] describes
 * the most recent [normalize] on this instance, and nothing else touches it.
 *
 * Every implementation rests at zero: a silent frame returns `0f` with
 * [FeatureValidity.Silent], whatever the mode. A consumer that ignores
 * validity therefore still falls to rest rather than freezing or spiking.
 */
sealed interface Normalizer {
    /** How much the last [normalize] result is worth. [FeatureValidity.Warmup] before the first one. */
    val validity: FeatureValidity

    /**
     * Maps [raw] into this normalizer's range.
     *
     * [activity] is the frame-wide decision from [SilenceGate], passed in
     * rather than inferred: a silent frame must not train an adaptive scale,
     * and a feature like centroid cannot tell silence from a low reading on
     * its own.
     *
     * [raw] must be finite — every descriptor upstream guarantees it, and the
     * guards that make that true live where the division happens rather than
     * here. A NaN arriving is an upstream bug, and laundering it into a
     * plausible number here would hide it rather than fix it.
     */
    fun normalize(
        raw: Float,
        activity: FrameActivity,
    ): Float

    /** Forgets everything learned. §5.5's session reset: a new track, a seek, a new source. */
    fun reset()
}
