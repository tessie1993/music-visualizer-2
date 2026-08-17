package dev.geode.playback

/**
 * Index arithmetic for the playback queue, kept free of ExoPlayer so the
 * headless suite can exercise it (the same split as
 * [dev.geode.analysis.PlaybackMath]).
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

    /**
     * The queue's item indices in the order they will actually PLAY.
     *
     * `mediaItemCount` and `getMediaItemAt` walk the TIMELINE - the order items
     * were added - and that stops being the play order the moment shuffle is
     * on. Listing the timeline as the queue therefore named tracks that would
     * not play next, in the queue panel and in "Up next" alike, and it was the
     * quieter half of the defect: the mutations handed those same positions
     * back to the player as timeline indices, so removing the third row of a
     * shuffled queue removed whatever happened to sit third in the timeline.
     *
     * [first] and [next] are `Timeline.getFirstWindowIndex` and
     * `getNextWindowIndex`, which is all the shuffle permutation is reachable
     * through - ExoPlayer owns the order and does not publish it as a list.
     * Passing them in keeps the walk testable without a player.
     *
     * Two guards, both for real states rather than defensiveness:
     *  - [next] must be called with `REPEAT_MODE_OFF` whatever the player's own
     *    repeat mode is, because this walk terminates on `INDEX_UNSET` and
     *    `REPEAT_MODE_ALL` would cycle forever. The [count] ceiling below makes
     *    that a truncated list instead of a hung UI thread if a caller forgets.
     *  - an empty or single-item queue reports `INDEX_UNSET` as its first
     *    window, which is -1 and not a position.
     */
    fun playOrder(
        count: Int,
        first: Int,
        next: (Int) -> Int,
    ): List<Int> {
        if (count <= 0 || first < 0 || first >= count) return emptyList()
        val order = ArrayList<Int>(count)
        var i = first
        // Bounded by count: a permutation visits each index once, so anything
        // longer means `next` is cycling and the honest answer is what we have.
        while (i in 0 until count && order.size < count) {
            order += i
            i = next(i)
        }
        return order
    }

    /**
     * The timeline index a displayed queue position refers to, or -1.
     *
     * Every player mutation - remove, move, seek-to-row - takes a timeline
     * index, and every gesture produces a position in the list the user is
     * looking at. While the two orders coincided that distinction cost nothing,
     * which is exactly why the mutation sites passed one for the other.
     */
    fun timelineIndexOf(
        playOrder: List<Int>,
        displayedIndex: Int,
    ): Int = playOrder.getOrElse(displayedIndex) { -1 }
}
