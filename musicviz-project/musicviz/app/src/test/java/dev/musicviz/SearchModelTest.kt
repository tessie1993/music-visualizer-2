package dev.musicviz

import dev.musicviz.ui.DeviceTrack
import dev.musicviz.ui.LibraryTrack
import dev.musicviz.ui.MusicPlaylist
import dev.musicviz.ui.Preset
import dev.musicviz.ui.SearchModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search overlay's merge rules, now that they are ordinary functions
 * rather than four `remember` blocks inside a composable. These were
 * previously only reachable through a Compose test, so the duplicate-uri
 * precedence and the per-source field lists were effectively unverified.
 */
class SearchModelTest {
    private fun device(
        uri: String,
        title: String = "T",
        artist: String = "A",
        album: String = "AL",
        folder: String = "/music",
    ) = DeviceTrack(uri, title, artist, album, folder, durationMs = 1000L)

    private fun library(
        uri: String,
        title: String = "T",
        artist: String = "A",
        album: String = "AL",
        genre: String = "G",
    ) = LibraryTrack(uri = uri, title = title, artist = artist, album = album, genre = genre)

    @Test
    fun blankQueryMatchesNothing() {
        val r =
            SearchModel.search(
                query = "   ",
                deviceTracks = listOf(device("u1", title = "Anything")),
                libraryTracks = listOf(library("u2", title = "Anything")),
                playlists = listOf(MusicPlaylist("Anything")),
                presets = listOf(Preset("Anything", "julia", 0.5f, 0.1f)),
            )
        assertTrue("a blank query must not dump the whole library", r.isEmpty)
    }

    @Test
    fun everyTermMustMatchAndSearchIsCaseInsensitive() {
        val tracks = listOf(device("u1", title = "Blue Monday", artist = "New Order"))
        assertEquals(1, SearchModel.search("blue order", tracks, emptyList(), emptyList(), emptyList()).tracks.size)
        assertEquals(0, SearchModel.search("blue absent", tracks, emptyList(), emptyList(), emptyList()).tracks.size)
    }

    @Test
    fun deviceRowWinsADuplicateUri() {
        // The device index is the entry the system keeps current, so when the
        // same uri is also an imported library row the device row is the one
        // shown. SearchModel always merges device rows first, so this also
        // pins that the later library row does not overwrite it.
        val d = device("same://uri", title = "Match", artist = "Device")
        val l = library("same://uri", title = "Match", artist = "Library")

        val results = SearchModel.search("match", listOf(d), listOf(l), emptyList(), emptyList())
        assertEquals(1, results.tracks.size)
        assertTrue("device row must win the dedupe", results.tracks.single().fromDevice)
        assertEquals(
            "Device",
            results.tracks
                .single()
                .subtitle
                .substringBefore(" ·"),
        )
    }

    @Test
    fun sourcesMatchOnTheirOwnFields() {
        // Only device rows carry a folder; only library rows carry a genre.
        val d = listOf(device("u1", title = "x", artist = "y", album = "z", folder = "/Trance"))
        val l = listOf(library("u2", title = "x", artist = "y", album = "z", genre = "Ambient"))

        val byFolder = SearchModel.search("trance", d, l, emptyList(), emptyList()).tracks
        assertEquals(listOf("u1"), byFolder.map { it.uri })

        val byGenre = SearchModel.search("ambient", d, l, emptyList(), emptyList()).tracks
        assertEquals(listOf("u2"), byGenre.map { it.uri })
    }

    @Test
    fun subtitleSkipsBlankArtistAndAlbum() {
        val r =
            SearchModel.search(
                "solo",
                listOf(device("u1", title = "Solo", artist = "", album = "")),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        assertEquals("", r.tracks.single().subtitle)
    }

    @Test
    fun playlistsAndPresetsMatchByName() {
        val r =
            SearchModel.search(
                query = "night",
                deviceTracks = emptyList(),
                libraryTracks = emptyList(),
                playlists = listOf(MusicPlaylist("Night drive"), MusicPlaylist("Morning")),
                presets = listOf(Preset("Nightfall", "julia", 0.5f, 0.1f), Preset("Sunrise", "julia", 0.5f, 0.1f)),
            )
        assertEquals(listOf("Night drive"), r.playlists.map { it.name })
        assertEquals(listOf("Nightfall"), r.presets.map { it.name })
        assertTrue(r.tracks.isEmpty())
    }

    @Test
    fun trackResultsAreCappedAtTheMatcherLimit() {
        val many = (1..60).map { device("u$it", title = "Track $it") }
        val r = SearchModel.search("track", many, emptyList(), emptyList(), emptyList())
        assertEquals(dev.musicviz.analysis.SearchMatcher.TRACK_LIMIT, r.tracks.size)
    }
}
