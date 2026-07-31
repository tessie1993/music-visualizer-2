package dev.musicviz.analysis

/**
 * Trims an export to a whole number of BARS, so the clip loops.
 *
 * A clip cut at an arbitrary second restarts mid-bar. Every platform that
 * autoplays short video loops it, and a loop that lands off the grid is
 * audible immediately - the beat stumbles once per repeat. Cutting on a bar
 * boundary instead means the last beat runs into the first and the seam
 * disappears, which is the whole difference between a clip that can be posted
 * and one that cannot.
 *
 * Bars, not beats: a beat-length cut lands on the grid but can still start the
 * loop on the "wrong" beat of the bar, which is just as audible in anything
 * with a backbeat.
 */
object BarTrim {
    /** Beats per bar assumed. Four covers essentially all popular music. */
    const val BEATS_PER_BAR = 4

    /**
     * Tempo range the trim will act on.
     *
     * Outside it the detected BPM is more likely wrong than unusual - a
     * half/double-time error, or a track with no steady pulse at all - and
     * trimming to a bar length derived from a wrong tempo would cut the clip
     * somewhere arbitrary while claiming it was musical. The honest answer
     * there is to leave the duration alone.
     */
    const val MIN_BPM = 50f
    const val MAX_BPM = 220f

    /** Microseconds in one bar at [bpm], or null when the tempo is unusable. */
    fun barDurationUs(bpm: Float): Long? {
        if (!bpm.isFinite() || bpm < MIN_BPM || bpm > MAX_BPM) return null
        return (60_000_000.0 * BEATS_PER_BAR / bpm).toLong()
    }

    /**
     * [durationUs] rounded DOWN to a whole number of bars at [bpm].
     *
     * Down rather than to the nearest: rounding up would extend the clip past
     * the audio it has, which means a render ending in silence - the one
     * outcome worse than a seam.
     *
     * Returns [durationUs] unchanged when the tempo is unusable or the source
     * is shorter than a single bar, because a clip trimmed to nothing is not a
     * loop-safe clip.
     */
    fun trimToBars(
        durationUs: Long,
        bpm: Float,
    ): Long {
        if (durationUs <= 0L) return durationUs
        val bar = barDurationUs(bpm) ?: return durationUs
        val bars = durationUs / bar
        if (bars < 1) return durationUs
        return bars * bar
    }

    /** How much [trimToBars] would cut, for the dialog to show up front. */
    fun trimmedAwayUs(
        durationUs: Long,
        bpm: Float,
    ): Long = durationUs - trimToBars(durationUs, bpm)
}
