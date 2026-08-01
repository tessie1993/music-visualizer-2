package dev.musicviz.playback

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.ui.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one invariant background playback rests on: there is exactly one player
 * in the process, and it is the one with the PCM tap on it.
 *
 * Moving playback into a service is the classic place to end up with two -
 * one the notification drives and one the screen does. The symptom is not a
 * crash: it is transport controls that look right over silence, or a
 * visualizer sitting still while music plays, because the tap is teed off the
 * player nobody is listening to. Neither shows up in a compile, and both are
 * exactly what these tests refuse.
 *
 * What needs a device: everything the platform does with the MediaSession.
 * Robolectric has no notification shade, no lock screen and no Bluetooth
 * stack, so that the transport controls appear, that a headset button reaches
 * the session, that audio focus is actually yielded to an incoming call, and
 * that the two foreground services show two separate notifications, all have
 * to be checked by hand on hardware. The manifest declaration those depend on
 * is checked below, since a missing intent-filter or the wrong service type
 * would break all of them at once and is visible from here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackEngineTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the screen and the service are handed the same player`() {
        val forUi = PlaybackEngine.acquireForUi(ctx)
        val forService = PlaybackEngine.acquireForService(ctx)
        assertSame("a second ExoPlayer would leave the notification driving silence", forUi.player, forService.player)
        assertSame(forUi, forService)
    }

    @Test
    fun `a screen that opens over playing music inherits the player, not a copy of it`() {
        val first = PlayerViewModel(ctx)
        val second = PlayerViewModel(ctx)
        assertSame(first.player, second.player)
    }

    @Test
    fun `the visualizer reads the ring buffer the tap writes into`() {
        val session = PlaybackEngine.acquireForUi(ctx)
        val viewModel = PlayerViewModel(ctx)
        assertNull("nothing has been decoded yet", viewModel.latestPcm())
        // Stands in for the tap sink, which only runs inside a real audio
        // pipeline. What is being asserted is the wiring either side of it: the
        // buffer the player writes into is the buffer the scenes read from.
        session.ring.writeInterleaved(FloatArray(512) { 0.25f }, 256, 2)
        val chunk = viewModel.latestPcm()
        assertNotNull("the scenes read a different ring buffer from the one the player fills", chunk)
        assertEquals("the 512 interleaved samples are 256 mono frames", 256, chunk?.count)
    }

    @Test
    fun `a player with nothing loaded is not worth keeping a service alive for`() {
        // This is what decides, when the last screen goes away, whether the
        // service stays up. Idle has to read as "no", or MusicViz would leave a
        // media notification standing over a player with nothing in it.
        assertFalse(PlaybackEngine.acquireForUi(ctx).playbackWanted)
    }

    @Test
    fun `the playback service is declared the way the platform requires`() {
        val info =
            ctx.packageManager.getServiceInfo(
                ComponentName(ctx, PlaybackService::class.java),
                0,
            )
        // Exported because every transport that drives it - the notification,
        // the lock screen, a Bluetooth button - is another process.
        assertTrue("nothing outside the app could reach the session", info.exported)
        assertEquals(
            "mediaPlayback is what lets the service outlive the Activity",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        // Without the intent-filter the platform cannot find the session at
        // all, and every controller outside the app silently gets nothing.
        val resolved =
            ctx.packageManager.queryIntentServices(
                Intent("androidx.media3.session.MediaSessionService").setPackage(ctx.packageName),
                0,
            )
        assertEquals(1, resolved.size)
        assertEquals(PlaybackService::class.java.name, resolved[0].serviceInfo.name)
    }

    @Test
    fun `the capture service keeps its own foreground type, so the two can run at once`() {
        // Android runs several foreground services happily, but each needs the
        // type that matches what it is doing: mediaProjection is what makes
        // reading another app's audio legal, and mediaPlayback is what makes
        // our own playback survive the screen. One service claiming both, or
        // either claiming the other's, breaks the feature it does not belong to.
        val capture =
            ctx.packageManager.getServiceInfo(
                ComponentName(ctx, dev.musicviz.audio.PlaybackCaptureService::class.java),
                0,
            )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            capture.foregroundServiceType,
        )
        assertFalse("the capture service is started by this app alone", capture.exported)
    }
}
