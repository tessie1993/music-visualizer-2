package dev.musicviz.render

/**
 * When a rotating visual is due to change.
 *
 * The visual playlist and Random mode ask the same question with different
 * floors, and used to answer it with two copies of this arithmetic. Kept free
 * of Android so the headless suite can pin the behaviour that is otherwise
 * only observable by watching a track play for a minute.
 */
object SwitchTiming {
    /**
     * How big a beat counts as a "strong musical moment" to switch on.
     *
     * Track-relative by construction — [dev.musicviz.analysis.AudioFeatures.beatImpulse]
     * folds in the macro-energy envelope — so this means "one of this song's
     * bigger hits", not an absolute loudness that quiet masters never reach.
     */
    const val STRONG_MOMENT_IMPULSE = 0.6f

    /**
     * Whether the visual should change now.
     *
     * On a plain timer this is just the interval. On [onStrongMoment] the
     * switch waits for a big beat instead of a stopwatch, but only after a
     * minimum dwell so the visuals do not flicker between two hits — and it
     * gives up and switches anyway at twice the interval, so a quiet passage
     * with no big beats in it still rotates.
     *
     * @param minDwellFloorMs the shortest dwell this caller will accept; the
     *   effective dwell is the larger of it and half the interval, so a long
     *   interval scales the dwell up with it.
     */
    fun isDue(
        elapsedMs: Long,
        intervalMs: Long,
        onStrongMoment: Boolean,
        beatImpulse: Float,
        minDwellFloorMs: Long,
    ): Boolean {
        if (!onStrongMoment) return elapsedMs >= intervalMs
        val minDwell = maxOf(minDwellFloorMs, intervalMs / 2)
        return (elapsedMs >= minDwell && beatImpulse >= STRONG_MOMENT_IMPULSE) ||
            elapsedMs >= intervalMs * 2
    }
}
