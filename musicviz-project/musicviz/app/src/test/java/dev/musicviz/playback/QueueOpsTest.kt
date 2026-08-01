package dev.musicviz.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where "play next" puts a track.
 *
 * The arithmetic is one line, and it is one line because every interesting
 * input is a degenerate one: an empty player reports its current index as
 * [androidx.media3.common.C.INDEX_UNSET], which is -1 and not a position, and
 * the answer for "after the last item" is the size itself, which is not a
 * position either. Both are ordinary states a user reaches - queue nothing yet
 * and long-press "play next", or do it while the last track plays - and both
 * would be an IndexOutOfBoundsException from ExoPlayer if the clamp were
 * dropped. Testing it away from the player is the only way to state that
 * without a device.
 */
class QueueOpsTest {
    @Test
    fun play_next_lands_after_the_playing_item() {
        assertEquals(3, QueueOps.insertNextIndex(currentIndex = 2, size = 8))
    }

    @Test
    fun play_next_on_an_empty_queue_is_the_first_slot() {
        // C.INDEX_UNSET is -1; -1 + 1 = 0 by luck, but size 0 must pin it too.
        assertEquals(0, QueueOps.insertNextIndex(currentIndex = -1, size = 0))
        assertEquals(0, QueueOps.insertNextIndex(currentIndex = 4, size = 0))
    }

    @Test
    fun play_next_while_playing_the_last_item_appends() {
        assertEquals(3, QueueOps.insertNextIndex(currentIndex = 2, size = 3))
    }

    @Test
    fun play_next_never_returns_past_the_end() {
        assertEquals(3, QueueOps.insertNextIndex(currentIndex = 99, size = 3))
    }
}
