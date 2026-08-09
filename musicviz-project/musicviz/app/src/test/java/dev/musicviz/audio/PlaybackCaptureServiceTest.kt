package dev.musicviz.audio

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one service stop path a JVM test can drive end to end: a start intent
 * that cannot produce a projection.
 *
 * Before the failure tick existed this path died silently - the holder's
 * StateFlow already held null, a StateFlow never repeats a value, so the
 * ViewModel's "waiting for the capture permission…" state had nothing to wake
 * it and stayed stuck until the user toggled the switch by hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackCaptureServiceTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `a start with no consent data ticks the failure signal and publishes nothing`() {
        val before = MediaProjectionHolder.startFailures.value
        val controller =
            Robolectric.buildService(
                PlaybackCaptureService::class.java,
                Intent(ctx, PlaybackCaptureService::class.java),
            )
        controller.create().startCommand(0, 1)
        assertEquals(
            "a doomed start must tick startFailures exactly once",
            before + 1,
            MediaProjectionHolder.startFailures.value,
        )
        assertNull("no projection may be published for a failed start", MediaProjectionHolder.projection.value)
        controller.destroy()
    }

    @Test
    fun `every failed start ticks again - the signal can never be conflated away`() {
        val before = MediaProjectionHolder.startFailures.value
        repeat(2) { i ->
            val controller =
                Robolectric.buildService(
                    PlaybackCaptureService::class.java,
                    Intent(ctx, PlaybackCaptureService::class.java),
                )
            controller.create().startCommand(0, i + 1)
            controller.destroy()
        }
        assertEquals(before + 2, MediaProjectionHolder.startFailures.value)
    }
}
