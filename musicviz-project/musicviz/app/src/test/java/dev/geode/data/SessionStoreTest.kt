package dev.geode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The queue and position that survive process death.
 *
 * Resumption used to rebuild one track at 0:00 from the play history, so a
 * listener forty minutes into a mix lost the mix and the forty minutes every
 * time Android reclaimed the process overnight. What matters here is that a
 * round trip is exact, and that the failure modes cannot make things worse than
 * the cold start they replace — a session file half-written by a process being
 * killed is precisely the situation this feature exists for.
 *
 * Pinned to SDK 34 for the reason the rest of the suite is: Robolectric 4.14
 * ships no SDK 36 image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun store() = SessionStore(context)

    private fun file() = File(context.filesDir, "session.json")

    private fun tracks(n: Int) =
        (0 until n).map {
            SessionStore.SavedTrack("content://audio/$it", "Track $it", "Artist $it")
        }

    @Test
    fun `nothing saved reads as nothing`() {
        file().delete()
        assertNull(store().load())
    }

    @Test
    fun `a session round-trips exactly`() {
        val saved = SessionStore.Saved(tracks(40), index = 17, positionMs = 92_500)
        assertTrue(store().save(saved))

        val loaded = store().load()
        assertNotNull(loaded)
        assertEquals(40, loaded!!.tracks.size)
        assertEquals(17, loaded.index)
        assertEquals(92_500L, loaded.positionMs)
        assertEquals("content://audio/17", loaded.tracks[17].uri)
        assertEquals("Track 17", loaded.tracks[17].title)
        assertEquals("Artist 17", loaded.tracks[17].artist)
    }

    /**
     * Titles ride along with the uris because resumption runs before the
     * library is scanned — System UI asks straight after a reboot — and a
     * notification reading "Unknown" until a scan finishes is the failure the
     * redundancy buys off.
     */
    @Test
    fun `metadata survives so a cold resume can be labelled`() {
        store().save(SessionStore.Saved(tracks(3), index = 1, positionMs = 0))
        val loaded = store().load()!!
        assertTrue(loaded.tracks.all { it.title.isNotBlank() && it.artist.isNotBlank() })
    }

    @Test
    fun `a full queue round-trips`() {
        val big = SessionStore.Saved(tracks(1001), index = 1000, positionMs = 1)
        assertTrue(store().save(big))
        assertEquals(1001, store().load()!!.tracks.size)
        assertEquals(1000, store().load()!!.index)
    }

    @Test
    fun `an empty queue clears rather than storing an empty session`() {
        store().save(SessionStore.Saved(tracks(3), 0, 0))
        assertTrue(store().save(SessionStore.Saved(emptyList(), 0, 0)))
        assertNull(store().load())
    }

    @Test
    fun `clear forgets the session`() {
        store().save(SessionStore.Saved(tracks(2), 0, 0))
        assertTrue(store().clear())
        assertNull(store().load())
    }

    /** The situation the feature exists for must not be the one that breaks it. */
    @Test
    fun `a corrupt file reads as nothing and is quarantined`() {
        file().writeText("{ this is not json")
        assertNull(store().load())
        assertFalse("the corrupt file was left in place", file().exists())
        assertTrue(
            "nothing was quarantined",
            context.filesDir.listFiles().orEmpty().any { it.name.startsWith("session.json") },
        )
    }

    /**
     * Parsed-but-unusable gets the same treatment: save() never writes an
     * empty session (that is a delete), so a tracks-less file is damage of a
     * politer kind - and left in place it was re-read and re-parsed on every
     * launch for an answer that is always "nothing".
     */
    @Test
    fun `a structurally valid but empty file is quarantined rather than re-read forever`() {
        file().writeText("""{"version":1,"tracks":[]}""")
        assertNull(store().load())
        assertFalse("the unusable file was left in place", file().exists())
        assertTrue(
            "nothing was quarantined",
            context.filesDir.listFiles().orEmpty().any { it.name.startsWith("session.json") },
        )
    }

    @Test
    fun `a truncated write reads as nothing rather than a wrong position`() {
        file().writeText("""{"version":1,"tracks":[{"uri":"content://a"}""")
        assertNull(store().load())
    }

    /** A caller must never have to re-check what load() hands back. */
    @Test
    fun `an out-of-range index is repaired on read`() {
        file().writeText(
            """{"version":1,"tracks":[{"uri":"content://a","title":"A","artist":""}],"index":99,"positionMs":-5}""",
        )
        val loaded = store().load()!!
        assertEquals(0, loaded.index)
        assertEquals(0L, loaded.positionMs)
    }

    @Test
    fun `an out-of-range index is repaired on write`() {
        store().save(SessionStore.Saved(tracks(3), index = 99, positionMs = -1))
        val loaded = store().load()!!
        assertEquals(2, loaded.index)
        assertEquals(0L, loaded.positionMs)
    }

    /** One bad row must not cost the whole session. */
    @Test
    fun `a row with no uri is dropped and the rest survives`() {
        file().writeText(
            """{"version":1,"tracks":[
                {"uri":"content://a","title":"A","artist":""},
                {"title":"orphan","artist":""},
                {"uri":"content://b","title":"B","artist":""}
            ],"index":2,"positionMs":10}""",
        )
        val loaded = store().load()!!
        assertEquals(2, loaded.tracks.size)
        assertEquals("content://b", loaded.tracks[1].uri)
        // The index was written against three rows and is clamped to the two
        // that survived, which is the nearest still-valid track.
        assertEquals(1, loaded.index)
    }

    @Test
    fun `a session of only bad rows reads as nothing`() {
        file().writeText("""{"version":1,"tracks":[{"title":"orphan"}],"index":0,"positionMs":0}""")
        assertNull(store().load())
    }

    @Test
    fun `saving replaces rather than appending`() {
        store().save(SessionStore.Saved(tracks(10), 5, 500))
        store().save(SessionStore.Saved(tracks(2), 1, 20))
        val loaded = store().load()!!
        assertEquals(2, loaded.tracks.size)
        assertEquals(1, loaded.index)
        assertEquals(20L, loaded.positionMs)
    }

    @Test
    fun `the write interval is short enough to lose only a moment`() {
        assertTrue(SessionStore.POSITION_WRITE_INTERVAL_MS in 1_000..10_000)
    }
}
