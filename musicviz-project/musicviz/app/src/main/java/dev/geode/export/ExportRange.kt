package dev.geode.export

/**
 * The slice of a track to render, in milliseconds from its start.
 *
 * Getting a fifteen-second clip out of this app used to cost a full-song render
 * — minutes of GPU time and a few hundred megabytes — followed by a second pass
 * through the Studio to trim it down and re-encode. Two renders and two files
 * for the one thing people most want to post.
 *
 * The rendered clip is rebased to zero: a range starting at 1:30 begins at 0:00
 * in the file it produces. Visual features are still sampled at the source time,
 * so the drop renders as the drop.
 */
data class ExportRange(
    val startMs: Long,
    val durationMs: Long,
) {
    init {
        require(startMs >= 0) { "startMs must not be negative, was $startMs" }
        require(durationMs > 0) { "durationMs must be positive, was $durationMs" }
    }

    val endMs: Long get() = startMs + durationMs

    companion object {
        /**
         * Shortest clip worth rendering. Below about a second there is no
         * musical content to see and the encoder's own keyframe interval starts
         * to dominate the file.
         */
        const val MIN_DURATION_MS: Long = 1_000

        /**
         * Clamps a requested range into a track, or returns null when the whole
         * track is wanted or the request cannot be honoured.
         *
         * Null rather than a range covering everything, because "no range" is
         * what the exporter's existing whole-track path takes — and that path
         * skips the seek entirely, which is both faster and the behaviour every
         * pre-existing export had.
         */
        @Suppress("ReturnCount")
        fun of(
            startMs: Long,
            endMs: Long,
            trackDurationMs: Long,
        ): ExportRange? {
            if (trackDurationMs <= 0) return null
            val start = startMs.coerceIn(0, trackDurationMs)
            val end = endMs.coerceIn(0, trackDurationMs)
            if (end - start < MIN_DURATION_MS) return null
            if (start == 0L && end >= trackDurationMs) return null
            return ExportRange(start, end - start)
        }
    }
}
