package dev.musicviz.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueOpsTest {
    // ---- insertNextIndex ----

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
