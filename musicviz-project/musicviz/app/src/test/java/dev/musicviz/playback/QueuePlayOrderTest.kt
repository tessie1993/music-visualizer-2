package dev.musicviz.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the queue panel is allowed to say will play next.
 *
 * `mediaItemCount` and `getMediaItemAt` walk the timeline - the order items
 * were added - and the queue panel, "Up next", and `queueTitles()` (whose KDoc
 * already claimed "in play order") all listed exactly that. With shuffle on
 * they therefore named tracks that would not play next. The louder half of the
 * same defect was in the mutations: `removeQueueItem`, `moveQueueItem` and
 * `playQueueIndex` passed the displayed position straight back to the player as
 * a timeline index, so removing the third row of a shuffled queue removed
 * whatever happened to sit third in the timeline.
 *
 * ExoPlayer does not publish the shuffle permutation as a list - it is only
 * reachable by walking `getFirstWindowIndex`/`getNextWindowIndex` - so the walk
 * takes those as functions and is tested here without a player, the same split
 * [QueueOpsTest] uses and for the same reason: it is the only way to state the
 * degenerate cases without a device.
 */
class QueuePlayOrderTest {
    /** A shuffle permutation as ExoPlayer exposes one: successor by index. */
    private fun successor(order: List<Int>): (Int) -> Int =
        { i ->
            val at = order.indexOf(i)
            if (at < 0 || at == order.lastIndex) INDEX_UNSET else order[at + 1]
        }

    private companion object {
        /** `androidx.media3.common.C.INDEX_UNSET`, spelled out to stay pure JVM. */
        const val INDEX_UNSET = -1
    }

    @Test
    fun `an unshuffled queue plays in timeline order`() {
        val order = listOf(0, 1, 2, 3, 4)
        assertEquals(order, QueueOps.playOrder(count = 5, first = 0, next = successor(order)))
    }

    @Test
    fun `a shuffled queue reports the order it will actually play`() {
        // The defect: this used to come back 0,1,2,3,4 whatever the shuffle was.
        val shuffled = listOf(3, 0, 4, 1, 2)
        assertEquals(shuffled, QueueOps.playOrder(count = 5, first = 3, next = successor(shuffled)))
    }

    @Test
    fun `an empty queue has no play order`() {
        // getFirstWindowIndex reports INDEX_UNSET, which is -1 and not a position.
        assertEquals(emptyList<Int>(), QueueOps.playOrder(count = 0, first = INDEX_UNSET) { INDEX_UNSET })
        assertEquals(emptyList<Int>(), QueueOps.playOrder(count = 4, first = INDEX_UNSET) { INDEX_UNSET })
    }

    @Test
    fun `a first window outside the queue is refused rather than indexed`() {
        assertEquals(emptyList<Int>(), QueueOps.playOrder(count = 3, first = 7) { INDEX_UNSET })
    }

    @Test
    fun `a single item queue is its own play order`() {
        assertEquals(listOf(0), QueueOps.playOrder(count = 1, first = 0) { INDEX_UNSET })
    }

    @Test
    fun `a cycling successor is truncated instead of hanging the caller`() {
        // REPEAT_MODE_ALL makes getNextWindowIndex cycle forever. The walk is
        // always called with REPEAT_MODE_OFF, and this is what protects the UI
        // thread if a future caller forgets: a truncated list, not a freeze.
        val cycle = { i: Int -> (i + 1) % 3 }
        assertEquals(listOf(0, 1, 2), QueueOps.playOrder(count = 3, first = 0, next = cycle))
    }

    @Test
    fun `a successor that stalls does not repeat an index forever`() {
        assertEquals(listOf(2, 2, 2), QueueOps.playOrder(count = 3, first = 2) { 2 })
    }

    @Test
    fun `a displayed row maps to the timeline index the player expects`() {
        val shuffled = listOf(3, 0, 4, 1, 2)
        // Row 0 of a shuffled queue is timeline item 3 - removing row 0 as if it
        // were timeline 0 is how the wrong track got removed.
        assertEquals(3, QueueOps.timelineIndexOf(shuffled, 0))
        assertEquals(4, QueueOps.timelineIndexOf(shuffled, 2))
        assertEquals(2, QueueOps.timelineIndexOf(shuffled, 4))
    }

    @Test
    fun `a row past the end of the queue maps to nothing`() {
        // The gesture races a queue the player may already have mutated, so this
        // has to be a refusal rather than an exception.
        assertEquals(-1, QueueOps.timelineIndexOf(listOf(3, 0, 4), 3))
        assertEquals(-1, QueueOps.timelineIndexOf(listOf(3, 0, 4), -1))
        assertEquals(-1, QueueOps.timelineIndexOf(emptyList(), 0))
    }
}
