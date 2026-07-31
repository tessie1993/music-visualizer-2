package dev.musicviz

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import dev.musicviz.audio.AudioQualityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the seam [AudioQualityTracker] was extracted across (it used to be
 * eight members of PlayerViewModel): the readout is the JOIN of two events
 * arriving on different threads, and the container comes from the uri at
 * event time, not at construction time.
 *
 * The classification itself is covered by `AudioQualityInfoTest`; what is
 * pinned here is the plumbing that decides *when* and *with what* classify is
 * called.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioQualityTrackerTest {
    private var uri: Uri? = null

    private fun tracker() = AudioQualityTracker { uri }

    private fun flac() =
        Format
            .Builder()
            .setSampleMimeType("audio/flac")
            .setSampleRate(44_100)
            .setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_16BIT)
            .build()

    @Test
    fun idle_until_a_source_format_arrives() {
        val t = tracker()
        assertNull(t.info.value)
        // A tap format alone is not enough: without the source there is
        // nothing to compare the output against.
        t.onTapFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        assertNull(t.info.value)
    }

    @Test
    fun source_then_tap_joins_both_halves() {
        val t = tracker()
        uri = Uri.parse("content://media/external/audio/media/12/song.flac")
        t.onSourceFormat(flac())
        val sourceOnly = t.info.value!!
        assertTrue(sourceOnly.lossless)
        assertEquals("FLAC", sourceOnly.codec)
        assertEquals(44_100, sourceOnly.sourceSampleRateHz)
        // No tap yet, so the output half reads as unknown (0), not as silence.
        assertEquals(0, sourceOnly.outputSampleRateHz)

        t.onTapFormat(44_100, 2, C.ENCODING_PCM_FLOAT)
        val joined = t.info.value!!
        assertEquals(44_100, joined.outputSampleRateHz)
        assertEquals(2, joined.outputChannels)
        assertTrue(joined.outputFloat)
        // The source half survives the second event rather than being reset.
        assertEquals("FLAC", joined.codec)
    }

    @Test
    fun tap_arriving_first_is_not_lost() {
        // Real ordering on device: the tap reconfigures before onTracksChanged
        // reports the selection. The tap value must still be there afterwards.
        val t = tracker()
        uri = Uri.parse("file:///music/track.flac")
        t.onTapFormat(96_000, 2, C.ENCODING_PCM_16BIT)
        t.onSourceFormat(flac())
        assertEquals(96_000, t.info.value!!.outputSampleRateHz)
    }

    @Test
    fun container_is_read_from_the_uri_at_event_time() {
        val t = tracker()
        // Constructed while nothing is playing — the supplier, not a captured
        // value, is what makes this work.
        uri = Uri.parse("file:///music/track.aiff")
        t.onSourceFormat(
            Format
                .Builder()
                .setSampleMimeType("audio/raw")
                .setSampleRate(44_100)
                .setChannelCount(2)
                .setPcmEncoding(C.ENCODING_PCM_16BIT)
                .build(),
        )
        assertEquals("AIFF", t.info.value!!.codec)

        // Same raw-PCM source, different track: the readout follows the uri.
        uri = Uri.parse("file:///music/other.wav")
        t.onTapFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        assertEquals("WAV", t.info.value!!.codec)
    }

    @Test
    fun a_null_source_clears_the_readout() {
        val t = tracker()
        uri = Uri.parse("file:///music/track.flac")
        t.onSourceFormat(flac())
        t.onTapFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        t.onSourceFormat(null)
        assertNull(t.info.value)
    }
}
