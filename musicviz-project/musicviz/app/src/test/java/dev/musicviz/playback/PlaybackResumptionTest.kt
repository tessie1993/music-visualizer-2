package dev.musicviz.playback

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.data.HistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the system's playback-resumption request gets after the process has
 * been killed: the user's most recent track, or a clean refusal.
 *
 * Only the lookup is testable here - Robolectric has no System UI and no
 * Bluetooth stack to actually issue the request, and MediaSession callbacks
 * need a session the suite cannot drive (see PlaybackEngineTest's header).
 * What CAN break invisibly is this seam: resumption answering with the wrong
 * track, with no metadata for the resumed notification, or "succeeding" on a
 * fresh install with an empty queue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackResumptionTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `resumption offers the most recent track, from the start`() {
        val history = HistoryStore(ctx)
        history.recordPlay("content://media/1", "First", "Ana")
        history.recordPlay("content://media/2", "Second", "Bo")
        history.awaitWrites()

        val resumption = PlaybackService.lastPlayedResumption(ctx)!!
        assertEquals(1, resumption.mediaItems.size)
        val item = resumption.mediaItems[0]
        assertEquals("the newest play wins", "content://media/2", item.localConfiguration?.uri.toString())
        assertEquals("the resumed notification needs a title", "Second", item.mediaMetadata.title?.toString())
        assertEquals("Bo", item.mediaMetadata.artist?.toString())
        assertEquals(0, resumption.startIndex)
        assertEquals("positions are not persisted, so resume starts clean", 0L, resumption.startPositionMs)
    }

    @Test
    fun `a fresh install refuses rather than resuming nothing`() {
        assertNull(PlaybackService.lastPlayedResumption(ctx))
    }
}
