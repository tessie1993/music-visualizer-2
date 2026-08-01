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
 * The numbers behind Home: play counts, real listening time, per-day totals,
 * and the v1 -> v2 history file migration. Robolectric only for a Context to
 * write files and preferences with.
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
    fun `listening time accumulates per track and per artist`() {
        val store = HistoryStore(context)
        store.recordPlay("a", "A", "Aphex Twin")
        store.addListenTime("a", 120_000L)
        store.recordPlay("b", "B", "Aphex Twin")
        store.addListenTime("b", 60_000L)
        store.recordPlay("c", "C", "Someone Else")
        store.addListenTime("c", 30_000L)
        val stats = store.stats()
        assertEquals(210_000L, stats.totalListenedMs)
        assertEquals("Aphex Twin", stats.topArtist)
        assertEquals(180_000L, stats.topArtistMs)
        assertEquals(3, stats.totalPlays)
    }

    @Test
    fun `the week has seven days with today last`() {
        val store = HistoryStore(context)
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000
        store.recordPlay("a", "A")
        store.addListenTime("a", 1_000L, now)
        store.addListenTime("a", 5_000L, now - 2 * day)
        val week = store.stats(now).week
        assertEquals(HistoryStore.WEEK_DAYS, week.size)
        assertEquals(1_000L, week.last())
        assertEquals(5_000L, week[HistoryStore.WEEK_DAYS - 3])
        assertEquals(6_000L, store.stats(now).weekListenedMs)
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
        }
        assertEquals(45_000L, HistoryStore(context).entryFor("a")?.listenedMs)
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
