package dev.musicviz

import androidx.test.core.app.ApplicationProvider
import dev.musicviz.ui.MusicPlaylist
import dev.musicviz.ui.MusicPlaylistStore
import dev.musicviz.ui.playlistDropIndex
import dev.musicviz.ui.playlistRowShift
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drag-to-reorder in the playlist list. Reordering used to be two arrow
 * buttons, so a track could only ever move one place per tap and the store's
 * arbitrary-distance [MusicPlaylistStore.move] was never exercised past ±1.
 * A drag can now land anywhere, which puts the weight on two things: the
 * gesture arithmetic that turns a finger travel distance into a drop index,
 * and the store actually rewriting the order that index asks for.
 *
 * Robolectric only for a Context to write the playlist files with; the
 * gesture maths is pure and needs no Android runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MusicVizApp::class)
class PlaylistReorderTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** One row is 100px tall throughout, so drag distances read as rows. */
    private val row = 100

    @Before
    fun clean() {
        java.io.File(context.filesDir, "music-playlists").deleteRecursively()
    }

    private fun storeWith(vararg uris: String): MusicPlaylistStore =
        MusicPlaylistStore(context).apply { save(MusicPlaylist("Set", uris.toList())) }

    private fun MusicPlaylistStore.uris(): List<String> = list().first { it.name == "Set" }.trackUris

    @Test
    fun `a drag down several rows drops that many rows down`() {
        // The whole point of the gesture: one drag covers what used to be
        // one tap per row.
        assertEquals(7, playlistDropIndex(from = 3, offsetPx = 4f * row, rowHeightPx = row, count = 20))
        assertEquals(19, playlistDropIndex(from = 0, offsetPx = 19f * row, rowHeightPx = row, count = 20))
    }

    @Test
    fun `a drag up several rows drops that many rows up`() {
        assertEquals(3, playlistDropIndex(from = 7, offsetPx = -4f * row, rowHeightPx = row, count = 20))
        assertEquals(0, playlistDropIndex(from = 49, offsetPx = -49f * row, rowHeightPx = row, count = 50))
    }

    @Test
    fun `a row swaps once it has passed half of its neighbour`() {
        // Rounding, not truncation: truncating would make the row snap back
        // until the finger had cleared a whole row in either direction, and
        // would round -0.6 rows towards zero rather than up.
        assertEquals(5, playlistDropIndex(from = 5, offsetPx = 0.49f * row, rowHeightPx = row, count = 10))
        assertEquals(6, playlistDropIndex(from = 5, offsetPx = 0.51f * row, rowHeightPx = row, count = 10))
        assertEquals(4, playlistDropIndex(from = 5, offsetPx = -0.51f * row, rowHeightPx = row, count = 10))
        assertEquals(2, playlistDropIndex(from = 5, offsetPx = -2.6f * row, rowHeightPx = row, count = 10))
    }

    @Test
    fun `a drag that goes nowhere is a no-op`() {
        // A long press the user thought better of must not rewrite the file.
        assertEquals(4, playlistDropIndex(from = 4, offsetPx = 0f, rowHeightPx = row, count = 10))
        assertEquals(4, playlistDropIndex(from = 4, offsetPx = 3f, rowHeightPx = row, count = 10))
    }

    @Test
    fun `dragging past either end parks at the end`() {
        assertEquals(0, playlistDropIndex(from = 2, offsetPx = -40f * row, rowHeightPx = row, count = 10))
        assertEquals(9, playlistDropIndex(from = 2, offsetPx = 40f * row, rowHeightPx = row, count = 10))
    }

    @Test
    fun `an unmeasured row cannot be dropped anywhere`() {
        // Before the first layout pass the row height is 0; the answer has to
        // be "stay put" rather than a division by zero.
        assertEquals(3, playlistDropIndex(from = 3, offsetPx = 500f, rowHeightPx = 0, count = 10))
    }

    @Test
    fun `rows the dragged one passed slide one place to make room`() {
        // Dragging 1 down to 4: rows 2..4 come up one, the rest hold still.
        assertEquals(0, playlistRowShift(index = 1, from = 1, to = 4))
        assertEquals(listOf(-1, -1, -1), listOf(2, 3, 4).map { playlistRowShift(it, from = 1, to = 4) })
        assertEquals(listOf(0, 0), listOf(0, 5).map { playlistRowShift(it, from = 1, to = 4) })
        // And the mirror image: dragging 4 up to 1 pushes rows 1..3 down one.
        assertEquals(listOf(1, 1, 1), listOf(1, 2, 3).map { playlistRowShift(it, from = 4, to = 1) })
        assertEquals(listOf(0, 0), listOf(0, 5).map { playlistRowShift(it, from = 4, to = 1) })
        // A drag hovering over its own row leaves the whole list alone.
        assertEquals(listOf(0, 0, 0), (0..2).map { playlistRowShift(it, from = 1, to = 1) })
    }

    @Test
    fun `a long move rewrites the order in one step`() {
        val store = storeWith("a", "b", "c", "d", "e", "f")
        store.move("Set", 5, 0)
        assertEquals(listOf("f", "a", "b", "c", "d", "e"), store.uris())
        store.move("Set", 0, 3)
        assertEquals(listOf("a", "b", "c", "f", "d", "e"), store.uris())
    }

    @Test
    fun `a move onto its own index leaves the order alone`() {
        val store = storeWith("a", "b", "c")
        store.move("Set", 1, 1)
        assertEquals(listOf("a", "b", "c"), store.uris())
    }

    @Test
    fun `a drop past the end clamps instead of losing the track`() {
        // The gesture already clamps, but the store is the last line of
        // defence: a rounding slip must never drop a track off the playlist.
        val store = storeWith("a", "b", "c")
        store.move("Set", 0, 99)
        assertEquals(listOf("b", "c", "a"), store.uris())
        store.move("Set", 2, -99)
        assertEquals(listOf("a", "b", "c"), store.uris())
    }

    @Test
    fun `a move from an index that is not there changes nothing`() {
        val store = storeWith("a", "b")
        store.move("Set", 7, 0)
        assertEquals(listOf("a", "b"), store.uris())
    }
}
