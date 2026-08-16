package dev.musicviz.engine.audio

/**
 * How much the value beside it is worth this frame.
 *
 * MASTER_PLAN §5.4 gives validity its own ABI slot, separate from the value,
 * and this is that slot's type. The separation is the point: a feature that
 * signals "I do not know" by returning zero is indistinguishable from one that
 * measured zero, and a shader cannot tell the difference either.
 *
 * All three are singletons, so carrying one costs nothing per hop.
 */
sealed interface FeatureValidity {
    /**
     * No value has been produced yet, or not enough sounding frames have gone
     * by for an adaptive scale to mean anything. A value is still produced —
     * the best available — so a consumer that ignores validity degrades rather
     * than breaks.
     */
    data object Warmup : FeatureValidity

    /**
     * The frame was silent, so the value is the feature's rest position rather
     * than a measurement. Every normalizer here rests at zero.
     */
    data object Silent : FeatureValidity

    /** The value means what it says. */
    data object Valid : FeatureValidity
}
