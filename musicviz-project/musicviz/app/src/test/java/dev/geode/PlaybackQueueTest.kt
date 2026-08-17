package dev.geode

import dev.geode.ui.DeviceTrack
import dev.geode.ui.LibraryTrack
import dev.geode.ui.PlaybackQueue
import dev.geode.ui.QueueTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the fix for "Next and Previous do nothing unless a playlist is
 * playing".
 *
 * Tapping a track called `setMediaItems(listOf(one))`, so the player held a
 * ONE-item queue and both transport buttons were no-ops. A tap now opens the
 * list the track belongs to, at that track, and these tests pin the rules
 * that make that safe: the right list is chosen, the tapped track is where
 * playback starts, a huge library is windowed rather than turned into
 * thousands of media sources on the main thread, and the metadata the screen
 * already has is carried through so building the queue needs no I/O.
 */
class PlaybackQueueTest {
    private fun device(
        id: Int,
        title: String = "Device $id",
    ) = DeviceTrack("content://media/$id", title, "Artist $id", "Album", "/music", 1000L)

    private fun library(
        id: Int,
        title: String = "Library $id",
    ) = LibraryTrack(uri = "content://library/$id", title = title, artist = "Artist $id")

    @Test
    fun tappingATrackOpensTheListItCameFrom() {
        val tracks = (1..5).map(::device)
        val queue = PlaybackQueue.contextFor(tracks[2].uri, emptyList(), tracks, emptyList())
        assertEquals("the whole visible list becomes the queue", 5, queue.size)
        val window = PlaybackQueue.window(queue, tracks[2].uri)
        assertEquals("playback starts on the tapped row", 2, window.startIndex)
        assertEquals(tracks[2].uri, window.tracks[window.startIndex].uri)
        // The point of the fix: there is somewhere for Next and Previous to go.
        assertTrue("nothing after the tapped track", window.startIndex < window.tracks.size - 1)
        assertTrue("nothing before the tapped track", window.startIndex > 0)
    }

    @Test
    fun theListTheUserIsLookingAtWinsOverTheDeviceIndex() {
        // A drilled-into album, a folder, a set of search hits: the screen's
        // own ordering is what Next should follow, even though the device
        // index contains the same track.
        val all = (1..6).map(::device)
        val album = listOf(all[4], all[1], all[3]).map(PlaybackQueue::queueTrack)
        val queue = PlaybackQueue.contextFor(all[1].uri, album, all, emptyList())
        assertEquals(album.map { it.uri }, queue.map { it.uri })
    }

    @Test
    fun aTrackTheBrowseListDoesNotHoldFallsThroughToTheIndexes() {
        val devices = (1..3).map(::device)
        val libraryTracks = (1..3).map(::library)
        val stale = listOf(QueueTrack("content://media/999"))
        assertEquals(
            "a stale browse list must not capture an unrelated track",
            devices.map { it.uri },
            PlaybackQueue.contextFor(devices[0].uri, stale, devices, libraryTracks).map { it.uri },
        )
        assertEquals(
            "an imported-library track must find the library",
            libraryTracks.map { it.uri },
            PlaybackQueue.contextFor(libraryTracks[1].uri, stale, devices, libraryTracks).map { it.uri },
        )
    }

    @Test
    fun aTrackInNoListStillPlays() {
        // A file opened from another app, or a history entry whose source list
        // is gone: play it alone rather than refusing or playing something else.
        val queue = PlaybackQueue.contextFor("content://elsewhere/7", emptyList(), emptyList(), emptyList())
        assertEquals(listOf("content://elsewhere/7"), queue.map { it.uri })
        val window = PlaybackQueue.window(queue, "content://elsewhere/7")
        assertEquals(0, window.startIndex)
        assertEquals(1, window.tracks.size)
    }

    @Test
    fun aStartUriMissingFromTheListPlaysThatTrackAndNotAnother() {
        // Playing the wrong song is worse than playing one song: never let a
        // stale list silently redirect the tap.
        val tracks = (1..4).map(::device).map(PlaybackQueue::queueTrack)
        val window = PlaybackQueue.window(tracks, "content://media/404")
        assertEquals(listOf("content://media/404"), window.tracks.map { it.uri })
        assertEquals(0, window.startIndex)
    }

    @Test
    fun aHugeLibraryIsWindowedAroundTheTappedTrack() {
        val tracks = (1..5000).map(::device).map(PlaybackQueue::queueTrack)
        val at = 3000
        val window = PlaybackQueue.window(tracks, tracks[at].uri)
        assertEquals(PlaybackQueue.MAX_QUEUE, window.tracks.size)
        assertEquals(
            "the tapped track must still be the one that plays",
            tracks[at].uri,
            window.tracks[window.startIndex].uri,
        )
        assertTrue("the window must reach forward", window.startIndex < window.tracks.size - 1)
        assertTrue("the window must reach back", window.startIndex > 0)
    }

    @Test
    fun aTrackAtEitherEndStillGetsAFullSizeQueue() {
        val tracks = (1..5000).map(::device).map(PlaybackQueue::queueTrack)
        for (at in listOf(0, 1, tracks.lastIndex - 1, tracks.lastIndex)) {
            val window = PlaybackQueue.window(tracks, tracks[at].uri)
            assertEquals("edge track $at lost queue length", PlaybackQueue.MAX_QUEUE, window.tracks.size)
            assertEquals(tracks[at].uri, window.tracks[window.startIndex].uri)
        }
    }

    @Test
    fun queueEntriesCarryTheMetadataTheListAlreadyHas() {
        // Without this the player would look each uri up through the content
        // resolver - fine for one track, a main-thread stall for a thousand.
        val d = PlaybackQueue.queueTrack(device(1, "Kicker"))
        assertEquals("Kicker", d.title)
        assertEquals("Artist 1", d.artist)
        val l = PlaybackQueue.queueTrack(library(2, "Drifter"))
        assertEquals("Drifter", l.title)
        assertEquals("Artist 2", l.artist)
    }

    @Test
    fun previousRestartsRatherThanSkipsOnlyAfterAWhile() {
        // The convention every music player follows, pinned so the threshold
        // cannot drift into "Previous never goes back".
        assertTrue("restarting too eagerly makes Previous useless", PlaybackQueue.PREV_RESTART_MS >= 1_000L)
        assertTrue("restarting too late is not the convention", PlaybackQueue.PREV_RESTART_MS <= 5_000L)
    }

    @Test
    fun theWindowIsBigEnoughToBeWorthHaving() {
        assertTrue("a short queue would strand Next again", PlaybackQueue.MAX_QUEUE >= 500)
        assertNotEquals("an even window has no centre track", 0, PlaybackQueue.MAX_QUEUE % 2)
    }
}
