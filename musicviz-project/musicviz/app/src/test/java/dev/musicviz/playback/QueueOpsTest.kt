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

    // ---- move ----

    @Test
    fun move_reorders_forward_and_backward() {
        val q = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), QueueOps.move(q, 0, 2))
        assertEquals(listOf("a", "d", "b", "c"), QueueOps.move(q, 3, 1))
    }

    @Test
    fun move_clamps_the_target_and_ignores_a_bad_source() {
        val q = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), QueueOps.move(q, 0, 99))
        assertEquals(listOf("c", "a", "b"), QueueOps.move(q, 2, -5))
        assertEquals(q, QueueOps.move(q, 7, 0))
        assertEquals(q, QueueOps.move(q, 1, 1))
    }

    @Test
    fun move_on_an_empty_list_is_a_no_op() {
        assertEquals(emptyList<String>(), QueueOps.move(emptyList<String>(), 0, 0))
    }

    // ---- indexAfterRemoval ----

    @Test
    fun removing_before_the_playing_item_shifts_it_down() {
        assertEquals(2, QueueOps.indexAfterRemoval(currentIndex = 3, removedIndex = 1, sizeBefore = 5))
    }

    @Test
    fun removing_after_the_playing_item_leaves_it_alone() {
        assertEquals(3, QueueOps.indexAfterRemoval(currentIndex = 3, removedIndex = 4, sizeBefore = 5))
    }

    @Test
    fun removing_the_playing_item_keeps_the_slot_so_the_next_track_plays() {
        assertEquals(2, QueueOps.indexAfterRemoval(currentIndex = 2, removedIndex = 2, sizeBefore = 5))
    }

    @Test
    fun removing_the_playing_last_item_clamps_onto_the_new_last() {
        assertEquals(3, QueueOps.indexAfterRemoval(currentIndex = 4, removedIndex = 4, sizeBefore = 5))
    }

    @Test
    fun emptying_the_queue_reports_no_index() {
        assertEquals(-1, QueueOps.indexAfterRemoval(currentIndex = 0, removedIndex = 0, sizeBefore = 1))
    }

    @Test
    fun removing_out_of_range_leaves_the_index_untouched() {
        assertEquals(2, QueueOps.indexAfterRemoval(currentIndex = 2, removedIndex = 9, sizeBefore = 5))
    }

    // ---- indexAfterMove ----

    @Test
    fun moving_the_playing_item_follows_it() {
        assertEquals(3, QueueOps.indexAfterMove(currentIndex = 1, from = 1, to = 3, size = 5))
    }

    @Test
    fun moving_an_item_across_the_playing_one_shifts_the_playing_index() {
        // "a" (before) jumps past the playing "c": c slides down one.
        assertEquals(1, QueueOps.indexAfterMove(currentIndex = 2, from = 0, to = 3, size = 5))
        // "e" (after) jumps in front of the playing "c": c slides up one.
        assertEquals(3, QueueOps.indexAfterMove(currentIndex = 2, from = 4, to = 1, size = 5))
    }

    @Test
    fun moving_entirely_on_one_side_leaves_the_playing_index_alone() {
        assertEquals(3, QueueOps.indexAfterMove(currentIndex = 3, from = 0, to = 1, size = 5))
        assertEquals(1, QueueOps.indexAfterMove(currentIndex = 1, from = 3, to = 4, size = 5))
    }

    @Test
    fun a_no_op_or_invalid_move_leaves_the_playing_index_alone() {
        assertEquals(2, QueueOps.indexAfterMove(currentIndex = 2, from = 1, to = 1, size = 5))
        assertEquals(2, QueueOps.indexAfterMove(currentIndex = 2, from = 9, to = 0, size = 5))
    }

    /**
     * The index bookkeeping must agree with the list the user sees: whatever
     * [indexAfterMove] reports has to name the same track in [move]'s output.
     */
    @Test
    fun index_bookkeeping_agrees_with_the_reordered_list() {
        val q = listOf("a", "b", "c", "d", "e")
        for (current in q.indices) {
            for (from in q.indices) {
                for (to in q.indices) {
                    val moved = QueueOps.move(q, from, to)
                    val idx = QueueOps.indexAfterMove(current, from, to, q.size)
                    assertEquals(
                        "playing=$current from=$from to=$to",
                        q[current],
                        moved[idx],
                    )
                }
            }
        }
    }
}
