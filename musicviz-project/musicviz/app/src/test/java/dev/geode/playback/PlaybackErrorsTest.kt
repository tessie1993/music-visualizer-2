package dev.geode.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens when a track will not play.
 *
 * Before this existed the answer was "nothing": no `onPlayerError` anywhere in
 * main source, so a deleted file or a revoked SAF grant froze the transport
 * silently. The two behaviours worth pinning are that one dead file is skipped
 * past, and that a dead *source* is not — because skipping onward through a
 * thousand unavailable tracks is its own failure, and a much louder one.
 */
class PlaybackErrorsTest {
    @Test
    fun `one failure with more queued skips to the next track`() {
        assertEquals(
            PlaybackErrors.Action.SkipToNext,
            PlaybackErrors.decide(consecutiveFailures = 1, hasNext = true),
        )
    }

    @Test
    fun `a failure with nothing queued stops`() {
        assertEquals(
            PlaybackErrors.Action.StopEndOfQueue,
            PlaybackErrors.decide(consecutiveFailures = 1, hasNext = false),
        )
    }

    /** The skip-storm guard: an ejected card must not burn the whole queue. */
    @Test
    fun `enough failures in a row stops even with tracks remaining`() {
        assertEquals(
            PlaybackErrors.Action.StopSourceUnavailable,
            PlaybackErrors.decide(PlaybackErrors.MAX_CONSECUTIVE_FAILURES, hasNext = true),
        )
    }

    @Test
    fun `the run keeps skipping right up to the limit`() {
        for (n in 1 until PlaybackErrors.MAX_CONSECUTIVE_FAILURES) {
            assertEquals(
                "failure $n should still skip",
                PlaybackErrors.Action.SkipToNext,
                PlaybackErrors.decide(n, hasNext = true),
            )
        }
    }

    /**
     * "Your storage is gone" beats "that was the last track": it stays true
     * whichever track the run happened to die on, and it is the one that tells
     * the user what to fix.
     */
    @Test
    fun `a dead source is reported as such even at the end of the queue`() {
        assertEquals(
            PlaybackErrors.Action.StopSourceUnavailable,
            PlaybackErrors.decide(PlaybackErrors.MAX_CONSECUTIVE_FAILURES, hasNext = false),
        )
    }

    @Test
    fun `a missing file is described as missing, and names the track`() {
        val message =
            PlaybackErrors.describe(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                trackTitle = "Blue Monday",
                action = PlaybackErrors.Action.SkipToNext,
            )
        assertTrue(message, message.contains("Blue Monday"))
        assertTrue(message, message.contains("missing"))
        assertTrue(message, message.contains("Skipping"))
    }

    @Test
    fun `a revoked grant tells the user how to restore it`() {
        val message =
            PlaybackErrors.describe(
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                trackTitle = "Anything",
                action = PlaybackErrors.Action.SkipToNext,
            )
        assertTrue(message, message.contains("Re-add the folder"))
    }

    @Test
    fun `an unknown track still produces a readable sentence`() {
        val message =
            PlaybackErrors.describe(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                trackTitle = null,
                action = PlaybackErrors.Action.StopEndOfQueue,
            )
        assertTrue(message, message.startsWith("This track"))
        assertFalse("left an empty quote where the title goes", message.contains("“”"))
    }

    @Test
    fun `a blank title is treated as no title`() {
        val message =
            PlaybackErrors.describe(
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                trackTitle = "   ",
                action = PlaybackErrors.Action.SkipToNext,
            )
        assertTrue(message, message.startsWith("This track"))
    }

    /** The export dialog's habit of printing exception class names is the anti-pattern. */
    @Test
    fun `no message leaks an error code or a class name`() {
        val codes =
            listOf(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_UNSPECIFIED,
            )
        val actions =
            listOf(
                PlaybackErrors.Action.SkipToNext,
                PlaybackErrors.Action.StopEndOfQueue,
                PlaybackErrors.Action.StopSourceUnavailable,
            )
        for (code in codes) {
            for (action in actions) {
                val message = PlaybackErrors.describe(code, "Track", action)
                assertFalse(message, message.contains("ERROR_CODE"))
                assertFalse(message, message.contains("Exception"))
                assertTrue("not a sentence: $message", message.trim().endsWith("."))
            }
        }
    }

    /** Each stop reason has to read differently, or splitting them bought nothing. */
    @Test
    fun `the two stop reasons say different things`() {
        val endOfQueue =
            PlaybackErrors.describe(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                "Track",
                PlaybackErrors.Action.StopEndOfQueue,
            )
        val sourceGone =
            PlaybackErrors.describe(
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                "Track",
                PlaybackErrors.Action.StopSourceUnavailable,
            )
        assertNotEquals(endOfQueue, sourceGone)
        assertTrue(sourceGone, sourceGone.contains("permission") || sourceGone.contains("storage"))
    }
}
