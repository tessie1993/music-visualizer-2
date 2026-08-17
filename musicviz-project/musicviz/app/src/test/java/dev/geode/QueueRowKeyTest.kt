package dev.geode

import dev.geode.ui.QueueTrack
import dev.geode.ui.queueRowKeys
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Row identity in the queue panel. The key used to be `"$index:$uri"`, which
 * names the SLOT rather than the entry: removing row 0 renamed every row
 * below it, so remembered item state and removal animations stuck to
 * positions instead of tracks. These pin the replacement - a row's key
 * survives removals and reorders of OTHER entries, and only a literal
 * duplicate of the same track needs a suffix.
 */
class QueueRowKeyTest {
    private fun queueOf(vararg uris: String) = uris.map { QueueTrack(it) }

    @Test
    fun `a queue without repeats keys rows by their uri alone`() {
        assertEquals(listOf("a", "b", "c"), queueRowKeys(queueOf("a", "b", "c")))
    }

    @Test
    fun `removing an entry leaves every other key unchanged`() {
        // The old index-mixed key failed exactly this: dropping the head
        // renamed the whole tail.
        val before = queueRowKeys(queueOf("a", "b", "c"))
        val after = queueRowKeys(queueOf("b", "c"))
        assertEquals(before.drop(1), after)
    }

    @Test
    fun `reordering permutes the keys without renaming any`() {
        assertEquals(
            queueRowKeys(queueOf("a", "b", "c")).sorted(),
            queueRowKeys(queueOf("c", "a", "b")).sorted(),
        )
    }

    @Test
    fun `the same track queued twice still gets distinct keys`() {
        // LazyColumn throws on a duplicate key, so this is a crash guard as
        // much as an identity rule.
        val keys = queueRowKeys(queueOf("a", "b", "a", "a"))
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `metadata plays no part in identity`() {
        // Title/artist can be filled in later by the library join; a row must
        // not lose its state when they arrive.
        assertEquals(
            queueRowKeys(queueOf("a", "b")),
            queueRowKeys(listOf(QueueTrack("a", "Title", "Artist"), QueueTrack("b"))),
        )
    }
}
