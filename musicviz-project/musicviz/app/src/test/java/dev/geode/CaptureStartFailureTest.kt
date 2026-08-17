package dev.geode

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.geode.audio.CaptureFailure
import dev.geode.audio.MediaProjectionHolder
import dev.geode.ui.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "Visualize other apps", when the start the user consented to never arrives.
 *
 * [PlaybackCaptureService] cannot publish a projection if `getMediaProjection`
 * refuses the consent it was handed - an expired token, an OEM that says no -
 * so it ticks [MediaProjectionHolder.startFailures] and dies. That counter is
 * a separate signal precisely because `projection` cannot carry the news: on a
 * failed FIRST start the StateFlow already holds null and a StateFlow does not
 * re-emit a value it is already at.
 *
 * It had no collector anywhere in `main/`, and nothing else clears
 * `awaitingConsent`, so the settings card sat on "Waiting for the capture
 * permission…" with the switch stuck on for the rest of the session.
 *
 * The counter is a wrapping tick, so only CHANGES mean anything: a collector
 * that acted on the value it subscribed at would report a failure that had
 * already been handled (or one from a previous screen), which is why the
 * subscription drops its first emission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureStartFailureTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a start that produces no projection ends the wait and says why`() {
        val vm = PlayerViewModel(ctx)
        idle()
        vm.noteExternalAudioConsentPending()
        assertTrue(vm.externalAudio.value.awaitingConsent)
        assertNull(vm.externalAudio.value.failure)

        // What the service does when getMediaProjection hands back null.
        MediaProjectionHolder.noteStartFailure()
        idle()

        assertFalse("the switch stays on and the card waits forever", vm.externalAudio.value.awaitingConsent)
        assertEquals(CaptureFailure.CONSENT, vm.externalAudio.value.failure)
        assertFalse(vm.externalAudio.value.active)
    }

    @Test
    fun `the tick a screen subscribes at is not a failure it has to report`() {
        // A previous session's failures are already on the counter. Reporting
        // the value rather than the change would open every new screen on
        // "Capture permission was not given".
        MediaProjectionHolder.noteStartFailure()
        MediaProjectionHolder.noteStartFailure()
        val vm = PlayerViewModel(ctx)
        idle()
        assertNull("a stale tick was reported as this screen's failure", vm.externalAudio.value.failure)
        assertFalse(vm.externalAudio.value.awaitingConsent)
    }

    @Test
    fun `every later failure is reported, not just the first`() {
        val vm = PlayerViewModel(ctx)
        idle()
        MediaProjectionHolder.noteStartFailure()
        idle()
        assertEquals(CaptureFailure.CONSENT, vm.externalAudio.value.failure)

        // The user tries again from the card; the second attempt fails too.
        vm.noteExternalAudioConsentPending()
        idle()
        assertTrue(vm.externalAudio.value.awaitingConsent)
        assertNull("asking again clears the last answer", vm.externalAudio.value.failure)

        MediaProjectionHolder.noteStartFailure()
        idle()
        assertFalse(vm.externalAudio.value.awaitingConsent)
        assertEquals(CaptureFailure.CONSENT, vm.externalAudio.value.failure)
    }
}
