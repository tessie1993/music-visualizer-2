package dev.musicviz.render

/**
 * The last gate before a full-frame flash reaches the screen: at most
 * [VisualSafety.WCAG_FLASHES_PER_SECOND] of them in any rolling second.
 *
 * [VisualSafety] bounds how BIG a flash may be, and `strobeHz` bounds the
 * strobe's own oscillator, but neither bounds how OFTEN the beat flash fires -
 * its rate is the track's. `beatMinIntervalMs` asks the analyzer for a floor,
 * which is a request made upstream of four things that can change the answer:
 * a double-time detection, a tempo ramp, a cached beat grid re-decided at
 * different settings, and a 200 BPM track. This counts what is about to be
 * drawn instead, one frame before it is drawn.
 *
 * Time is an argument rather than a clock, and the state is a fixed ring, so
 * the live renderer and the exporter fed the same beat grid produce the same
 * gains - the parity MASTER_PLAN §10.3 requires of anything shaping a frame.
 */
class FlashBudget(
    private val maxPerSecond: Float = VisualSafety.WCAG_FLASHES_PER_SECOND,
) {
    private val edges = FloatArray(CAPACITY)
    private var head = 0
    private var count = 0
    private var above = false
    private var lastTime = Float.NEGATIVE_INFINITY

    /** Drops the history. Call when the visual session or the clock restarts. */
    fun reset() {
        head = 0
        count = 0
        above = false
        lastTime = Float.NEGATIVE_INFINITY
    }

    /**
     * The multiplier to apply to this frame's flash amount, given the
     * full-frame luminance swing it would otherwise produce.
     *
     * Rising edges past [RISK_THRESHOLD] are what get counted: a held bright
     * level is one event, not one per frame, and a sub-threshold impulse is
     * not a flash at all. Once the second's budget is spent the excess is
     * scaled below the threshold rather than cut to zero, because a cut to
     * black is itself the full-frame change being limited.
     */
    fun gainFor(
        timeSeconds: Float,
        impulse: Float,
    ): Float {
        // uTime wraps, so a backwards step is normal rather than exceptional.
        if (timeSeconds < lastTime) reset()
        lastTime = timeSeconds

        val risky = impulse > RISK_THRESHOLD
        val rising = risky && !above
        above = risky
        dropOlderThan(timeSeconds - WINDOW_SECONDS)
        if (!risky) return 1f
        if (rising && count < maxPerSecond) {
            record(timeSeconds)
            return 1f
        }
        if (!rising) return 1f
        return (RISK_THRESHOLD * SUPPRESSED_SCALE / impulse).coerceIn(MIN_GAIN, 1f)
    }

    private fun dropOlderThan(cutoff: Float) {
        while (count > 0 && edges[(head - count + CAPACITY) % CAPACITY] <= cutoff) count--
    }

    private fun record(timeSeconds: Float) {
        edges[head] = timeSeconds
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    companion object {
        /**
         * The full-frame luminance swing at which an impulse starts counting
         * as a flash. WCAG's general threshold is a relative-luminance change
         * of 10% of full scale over a large area; this is deliberately below
         * it, because the swing here is estimated from the parameters rather
         * than measured off the frame.
         */
        const val RISK_THRESHOLD = 0.08f

        /** How far below the threshold a suppressed impulse is pushed. */
        private const val SUPPRESSED_SCALE = 0.6f

        /** Floor on the gain, so suppression never becomes its own hard cut. */
        private const val MIN_GAIN = 0.05f

        private const val WINDOW_SECONDS = 1f

        /** Ring size. Larger than any per-second budget this will ever carry. */
        private const val CAPACITY = 16
    }
}
