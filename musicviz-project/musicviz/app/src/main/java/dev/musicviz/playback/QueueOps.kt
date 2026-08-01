package dev.musicviz.playback

/**
 * Index arithmetic for the playback queue, kept free of ExoPlayer so the
 * headless suite can exercise it (the same split as
 * [dev.musicviz.analysis.PlaybackMath]).
 *
 * Every function here is total: out-of-range input clamps or comes back
 * unchanged rather than throwing, because the callers are UI gestures racing a
 * queue the player may have mutated underneath them - and now also racing a
 * notification, a lock screen and a Bluetooth button, none of which take turns.
 */
object QueueOps {
    /**
     * Where a "play next" insertion goes: directly after the playing item.
     *
     * [currentIndex] is [androidx.media3.common.C.INDEX_UNSET] (-1) on an empty
     * player, which has to land at 0 rather than at -1+1 by luck, so the result
     * is clamped into 0..[size] - [size] itself is a valid answer and means
     * "append".
     */
    fun insertNextIndex(
        currentIndex: Int,
        size: Int,
    ): Int {
        if (size <= 0) return 0
        return (currentIndex + 1).coerceIn(0, size)
    }
}
