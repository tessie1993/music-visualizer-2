package dev.musicviz

import androidx.test.core.app.ApplicationProvider
import dev.musicviz.ui.FavouritesStore
import dev.musicviz.ui.HistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The listening history behind the Player (resume, shuffle-all, most-played)
 * and favourites: play counts, real listening time, and the v1 -> v2 history
 * file migration. Robolectric only for a Context to write files and
 * preferences with.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MusicVizApp::class)
class ListeningHistoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val historyFile get() = java.io.File(context.filesDir, "history.json")

    @Before
    fun clean() {
        historyFile.delete()
        context.getSharedPreferences("musicviz-favourites", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `recently played is ordered by the plays the user actually made`() {
        val store = HistoryStore(context)
        store.recordPlay("a", "A")
        store.recordPlay("b", "B")
        store.recordPlay("c", "C")
        // Two plays inside one millisecond tick must not reorder: the stamp is
        // nudged strictly past the newest rather than set to "now".
        assertEquals(listOf("c", "b", "a"), store.recentlyPlayed().map { it.uri })
    }

    @Test
    fun `most played counts starts, not time`() {
        val store = HistoryStore(context)
        repeat(3) { store.recordPlay("a", "A") }
        store.recordPlay("b", "B")
        store.addListenTime("b", 10 * 60_000L)
        assertEquals(listOf("a", "b"), store.mostPlayed().map { it.uri })
    }

    @Test
    fun `listening time accumulates per track and breaks most-played ties`() {
        val store = HistoryStore(context)
        store.recordPlay("a", "A")
        store.addListenTime("a", 120_000L)
        store.addListenTime("a", 60_000L)
        store.recordPlay("b", "B")
        store.addListenTime("b", 30_000L)
        assertEquals(180_000L, store.entryFor("a")?.listenedMs)
        assertEquals(30_000L, store.entryFor("b")?.listenedMs)
        // Same start count: real listening time is the tie-breaker.
        assertEquals(listOf("a", "b"), store.mostPlayed().map { it.uri })
    }

    @Test
    fun `a blank artist never erases one an earlier play learned`() {
        val store = HistoryStore(context)
        store.recordPlay("a", "A", "Aphex Twin")
        store.recordPlay("a", "A", "")
        assertEquals("Aphex Twin", store.entryFor("a")?.artist)
    }

    @Test
    fun `the v1 history file is still read`() {
        // v1 wrote a bare array; losing it on upgrade would empty Home.
        historyFile.writeText("""[{"uri":"a","last":100,"count":7,"title":"A"}]""")
        val store = HistoryStore(context)
        assertEquals(7, store.entryFor("a")?.playCount)
        assertEquals("A", store.entryFor("a")?.title)
        assertEquals(0L, store.entryFor("a")?.listenedMs)
    }

    @Test
    fun `listening time survives a flush and reload`() {
        HistoryStore(context).apply {
            recordPlay("a", "A", "Artist")
            addListenTime("a", 45_000L)
            flush()
            // The write is queued onto the store's writer thread rather than
            // done where flush() is called - it is called from the player's
            // 500 ms poll and from track transitions, both on the main thread.
            // Teardown is the one place that has to wait for it.
            awaitWrites()
        }
        assertEquals(45_000L, HistoryStore(context).entryFor("a")?.listenedMs)
    }

    @Test
    fun `a play is readable immediately and durable once the writer catches up`() {
        // Moving the file write off the main thread must not make the store's
        // own answers lag behind: Home reads the numbers back from the same
        // event that recorded them.
        val store = HistoryStore(context)
        store.recordPlay("a", "A", "Artist")
        assertEquals(1, store.entryFor("a")?.playCount)
        store.awaitWrites()
        assertEquals(1, HistoryStore(context).entryFor("a")?.playCount)
    }

    @Test
    fun `a burst of plays is coalesced but the last one still lands`() {
        // recordPlay fires once per track transition, so once per tap while
        // skipping a queue. The writes coalesce; what must not happen is the
        // final state being the one that got folded away.
        val store = HistoryStore(context)
        repeat(50) { store.recordPlay("uri://$it", "T$it") }
        store.awaitWrites()
        val reloaded = HistoryStore(context)
        assertEquals(50, reloaded.recentlyPlayed(100).size)
        assertEquals("uri://49", reloaded.recentlyPlayed(1).single().uri)
    }

    @Test
    fun `favourites round-trip and put re-marks at the front`() {
        FavouritesStore(context).apply {
            toggle("a")
            toggle("b")
            assertTrue(isFavourite("a"))
            assertEquals(listOf("b", "a"), all())
            // Re-marking must move it, not leave it where a LinkedHashSet
            // would have kept the original key.
            toggle("a")
            toggle("a")
            assertEquals(listOf("a", "b"), all())
        }
        val reloaded = FavouritesStore(context)
        assertEquals(listOf("a", "b"), reloaded.all())
        assertFalse(reloaded.isFavourite("c"))
        assertEquals(2, reloaded.size)
    }
}
