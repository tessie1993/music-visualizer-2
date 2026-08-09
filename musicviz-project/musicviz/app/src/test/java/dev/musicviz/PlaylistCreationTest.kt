package dev.musicviz

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.data.MusicPlaylist
import dev.musicviz.data.MusicPlaylistStore
import dev.musicviz.ui.PlayerViewModel
import dev.musicviz.ui.playlistNameAccepted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Creating playlists from the UI. The store and the full CRUD API shipped
 * long before anything could call `create`, so the contracts here are the
 * ones the new affordances lean on: the shared naming dialog's gate, and the
 * store behaviour that gate exists to guard against.
 *
 * Robolectric only for a Context to write the playlist files with, as in
 * [PlaylistReorderTest]; the name gate is pure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MusicVizApp::class)
class PlaylistCreationTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clean() {
        java.io.File(context.filesDir, "music-playlists").deleteRecursively()
    }

    @Test
    fun `the naming dialog refuses blank and taken names`() {
        val taken = setOf("Drive", "Sleep")
        assertFalse(playlistNameAccepted("", taken))
        assertFalse(playlistNameAccepted("   ", taken))
        assertFalse(playlistNameAccepted("Drive", taken))
        // Trimmed before comparing, exactly as the create call trims before
        // saving - " Drive " and "Drive" would be the same file.
        assertFalse(playlistNameAccepted(" Drive ", taken))
        assertTrue(playlistNameAccepted("Gym", taken))
        assertTrue(playlistNameAccepted("Gym", emptySet()))
    }

    @Test
    fun `a taken name must be refused because save is an overwrite`() {
        // The store-side reason the dialog disables its confirm: save()
        // replaces whichever file holds the name, so accepting "Drive" again
        // would wipe its tracks with an empty list rather than fail politely
        // the way rename does.
        val store = MusicPlaylistStore(context)
        store.save(MusicPlaylist("Drive", listOf("a", "b")))
        store.save(MusicPlaylist("Drive"))
        assertEquals(emptyList<String>(), store.list().first { it.name == "Drive" }.trackUris)
    }

    @Test
    fun `saving the queue keeps its order and collapses a repeated track`() {
        // The queue panel's save is create + addTrack per entry in queue
        // order; addTrack skips a uri already present, so a track queued
        // twice lands in the playlist once.
        val store = MusicPlaylistStore(context)
        store.save(MusicPlaylist("From queue"))
        listOf("a", "b", "a", "c").forEach { store.addTrack("From queue", it) }
        assertEquals(listOf("a", "b", "c"), store.list().first { it.name == "From queue" }.trackUris)
    }

    @Test
    fun `adding a track to a playlist that does not exist yet creates it`() {
        // "New playlist…" in the add-to-playlist picker rides on this: create
        // then addTrack is safe even if the create is interleaved away.
        val store = MusicPlaylistStore(context)
        store.addTrack("Fresh", "a")
        assertEquals(listOf("a"), store.list().first { it.name == "Fresh" }.trackUris)
    }

    @Test
    fun `renameMusicPlaylist surfaces the store's answer instead of dropping it`() {
        // PlayerViewModel.renameMusicPlaylist used to return Unit, so a
        // caller had no way to tell a real rename from one the store
        // refused. It now mirrors MusicPlaylistStore.rename's Boolean.
        val store = MusicPlaylistStore(context)
        store.save(MusicPlaylist("Drive"))
        store.save(MusicPlaylist("Gym"))
        val viewModel = PlayerViewModel(ApplicationProvider.getApplicationContext<Application>())
        assertTrue(viewModel.renameMusicPlaylist("Drive", "Commute"))
        assertTrue(store.list().any { it.name == "Commute" })
        assertFalse(store.list().any { it.name == "Drive" })
        // A name already taken by another playlist must be refused, not
        // silently reported as a success that closes the dialog.
        assertFalse(viewModel.renameMusicPlaylist("Gym", "Commute"))
        assertTrue("the refused rename must not have touched Gym", store.list().any { it.name == "Gym" })
        // A blank name must be refused the same way.
        assertFalse(viewModel.renameMusicPlaylist("Gym", "   "))
        assertTrue(store.list().any { it.name == "Gym" })
    }
}
