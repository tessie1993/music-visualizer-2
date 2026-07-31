package dev.musicviz.playback

/**
 * Index arithmetic for the playback queue, kept free of ExoPlayer so the
 * headless suite can exercise it (same split as [dev.musicviz.analysis.PlaybackMath]).
 *
 * Every function here is total: out-of-range input clamps or returns the input
 * unchanged rather than throwing, because the callers are UI gestures racing a
 * queue that the player may have mutated underneath them.
 */
object QueueOps {
    /**
     * Where a "play next" insertion goes: directly after the playing item.
     *
     * [currentIndex] is [androidx.media3.common.C.INDEX_UNSET] (-1) on an empty
     * player, which must land at 0 and not -1+1 by luck, so the result is
     * clamped into 0..[size] — [size] itself is valid, meaning "append".
     */
    fun insertNextIndex(
        currentIndex: Int,
        size: Int,
    ): Int {
        if (size <= 0) return 0
        return (currentIndex + 1).coerceIn(0, size)
    }

    /**
     * Moves the item at [from] to [to] and returns the new list. A [from]
     * outside the list is a no-op; [to] is clamped to the last valid slot.
     */
    fun <T> move(
        items: List<T>,
        from: Int,
        to: Int,
    ): List<T> {
        if (from !in items.indices) return items
        val out = items.toMutableList()
        val target = to.coerceIn(0, out.size - 1)
        if (target == from) return items
        out.add(target, out.removeAt(from))
        return out
    }

    /**
     * The queue index that should be playing after the item at [removedIndex]
     * is dropped, given the item at [currentIndex] is playing now.
     *
     * Removing the playing item keeps the same slot, which is the next track
     * once the list closes up — except at the tail, where it clamps back onto
     * the new last item. Returns -1 when the queue empties.
     */
    fun indexAfterRemoval(
        currentIndex: Int,
        removedIndex: Int,
        sizeBefore: Int,
    ): Int {
        val sizeAfter = sizeBefore - 1
        if (sizeAfter <= 0) return -1
        if (removedIndex !in 0 until sizeBefore) return currentIndex
        return when {
            removedIndex < currentIndex -> (currentIndex - 1).coerceAtLeast(0)
            removedIndex > currentIndex -> currentIndex
            else -> currentIndex.coerceAtMost(sizeAfter - 1)
        }
    }

    /**
     * The index that stays on the same *item* after a move, for a queue whose
     * playing position is [currentIndex]. Used to keep "now playing" pinned to
     * the track the user is hearing while they reorder around (or under) it.
     */
    fun indexAfterMove(
        currentIndex: Int,
        from: Int,
        to: Int,
        size: Int,
    ): Int {
        if (from !in 0 until size) return currentIndex
        val target = to.coerceIn(0, size - 1)
        if (target == from) return currentIndex
        return when {
            currentIndex == from -> target
            // The playing item shifts one slot toward the vacated position
            // whenever the moved item crosses it.
            from < currentIndex && target >= currentIndex -> currentIndex - 1
            from > currentIndex && target <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
    }
}
