package dev.musicviz.playback

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.engine.audio.PresentationTime
import dev.musicviz.engine.audio.RingReadResult
import dev.musicviz.engine.audio.RingReader
import dev.musicviz.ui.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        session.ring.writeInterleaved(FloatArray(512) { 0.25f }, 256, 2)
        val chunk = viewModel.latestPcm()
        assertNotNull("the scenes read a different ring buffer from the one the player fills", chunk)
        assertEquals("the 512 interleaved samples are 256 mono frames", 256, chunk?.count)
    }

    @Test
    fun `PCM handed to the session's own tap arrives in that ring`() {
        // The test above writes to the ring directly, so it proves the read
        // side and assumes the write side. This drives the real tap, which is
        // the half that moved module in V2-2-03 and is one lambda wide: point
        // it at a different buffer and every scene sits still over playing
        // music, with nothing failing to compile and no crash to notice.
        val session = PlaybackEngine.acquireForUi(ctx)
        val viewModel = PlayerViewModel(ctx)
        session.tap.flush(48_000, 2, C.ENCODING_PCM_16BIT)

        val pcm = ByteBuffer.allocate(512 * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(512) { pcm.putShort(8192) }
        session.tap.handleBuffer(pcm.flip() as ByteBuffer)

        val chunk = viewModel.latestPcm()
        assertNotNull("the tap writes into a buffer nothing reads", chunk)
        assertEquals(256, chunk?.count)
        assertEquals("8192 of 32768 full scale is 0.25", 0.25f, chunk?.data?.get(0) ?: 0f, 0f)
        assertEquals(256L, session.tap.framesWritten)
    }

    @Test
    fun `the session's presentation clock is driven by the session's own audio chain`() {
        // Three objects have to be the same three: the chain the factory built
        // for THIS player raises the hooks, the tap that feeds THIS ring
        // reports the boundary, and the clock THIS session publishes receives
        // the segment. Any one of them wired to a different instance leaves a
        // clock that stays empty forever, with nothing failing.
        val session = PlaybackEngine.acquireForUi(ctx)
        assertNotNull("the tap reports boundaries to nobody", session.tap.boundaryListener)
        assertTrue(
            "the player's audio chain never handed the driver its skip counter, so the factory " +
                "was built without it and no speed change will ever reach the clock",
            session.clockDriver.diagnostics.skippedFramesAttached,
        )
        assertEquals("nothing has been decoded yet", 0, session.presentationClock.current.segments.size)

        session.clockDriver.onSpeedApplied(2f)
        session.clockDriver.onSkipSilenceApplied(false)
        session.tap.flush(48_000, 2, C.ENCODING_PCM_16BIT)

        val snapshot = session.presentationClock.current
        assertEquals(1, snapshot.segments.size)
        assertEquals(2f, snapshot.segments.single().speed, 0f)
        assertEquals("48000 frames at 2x are heard in half a second", PresentationTime.At(500_000), snapshot.presentationTimeOf(48_000, 1))
    }

    @Test
    fun `the V2 ring receives the same audio as the legacy one`() {
        // Both are fed from the one PcmSink lambda. If the second write were
        // dropped or given the wrong frame count, nothing would fail - the app
        // reads only the legacy buffer today, so the V2 ring would simply be
        // empty when the slice that switches readers arrives.
        val session = PlaybackEngine.acquireForUi(ctx)
        session.tap.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        val pcm = ByteBuffer.allocate(512 * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(256) { pcm.putShort(8192).putShort(-8192) }
        session.tap.handleBuffer(pcm.flip() as ByteBuffer)

        assertEquals(256L, session.sampleRing.writtenFrames)
        val out = arrayOf(FloatArray(256), FloatArray(256))
        assertEquals(RingReadResult.Ok(0, 256, 1), RingReader(session.sampleRing).read(out))
        assertEquals("left is planar, not folded", 0.25f, out[0][0], 0f)
        assertEquals("right keeps its own sign", -0.25f, out[1][0], 0f)
    }

    @Test
    fun `live input reaches both rings, not only the legacy one`() {
        // The microphone and the playback capture write through this sink, not
        // through the tap. If it fed only the legacy buffer, the app's own
        // playback would look right and a live mic would drive nothing once
        // the readers move - with no error and no failing test.
        val session = PlaybackEngine.acquireForUi(ctx)
        val viewModel = PlayerViewModel(ctx)
        session.captureSink.write(FloatArray(512) { 0.5f }, 256, 2)

        assertEquals(256L, session.sampleRing.writtenFrames)
        assertEquals("the legacy ring stopped receiving live input", 256, viewModel.latestPcm()?.count)
    }

    @Test
    fun `the capture controller is handed the session's own sink`() {
        // A source scan, and it is the weaker kind of test - but the
        // alternative is test-only API on PlayerViewModel to expose a
        // constructor argument, and CaptureController holds it privately. The
        // behavioural half is covered above; this pins only that the ViewModel
        // does not build a second sink of its own, which would write one ring
        // and look entirely correct.
        val source =
            java.io.File(dev.musicviz.ParamSurface.moduleRoot, "app/src/main/java/dev/musicviz/ui/PlayerViewModel.kt")
                .readText()
        assertTrue(
            "PlayerViewModel no longer hands CaptureController the session's capture sink",
            source.contains("playback.captureSink"),
        )
    }

    @Test
    fun `the ring's numbering and the clock's are the same number`() {
        // Two counters that agree by habit would diverge the first time one of
        // them missed a boundary, and a sample index means nothing without the
        // epoch it belongs to. Both are driven from the tap's generation.
        val session = PlaybackEngine.acquireForUi(ctx)
        session.clockDriver.onSpeedApplied(1f)
        session.clockDriver.onSkipSilenceApplied(false)
        session.tap.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(1, session.sampleRing.epoch)
        assertEquals(1, session.presentationClock.current.epoch)
        assertEquals(session.tap.format?.generation, session.sampleRing.epoch)

        session.clockDriver.onSpeedApplied(1f)
        session.clockDriver.onSkipSilenceApplied(false)
        session.tap.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(2, session.sampleRing.epoch)
        assertEquals(2, session.presentationClock.current.epoch)
        assertEquals(0L, session.sampleRing.writtenFrames)
    }

    @Test
    fun `a decoder buffer far larger than the tap's staging window still fits the ring`() {
        // SampleRing.write REQUIRES each write to fit the reader runway and
        // throws if it does not - on the playback thread, inside
        // AudioProcessor.flush, which stops the music. The tap chunks a large
        // buffer to its staging size, so the ring's capacity has to leave a
        // runway bigger than that chunk. This fails if either constant moves.
        val session = PlaybackEngine.acquireForUi(ctx)
        session.tap.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        val frames = 40_000
        val pcm = ByteBuffer.allocate(frames * 2 * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { pcm.putShort(1).putShort(-1) }
        session.tap.handleBuffer(pcm.flip() as ByteBuffer)
        assertEquals(frames.toLong(), session.sampleRing.writtenFrames)
    }

    /**
     * The first hold anyone takes is taken while the player is still being
     * built, and it used to be thrown away by the act of building it.
     *
     * `acquireFor*` counted the hold and then asked for the session; creating
     * the session binds to the Application, and binding resets both counters
     * because a player left over from a different Application is worthless.
     * On the first acquire of the process those are the same call, so the
     * counter went 0 → 1 → 0 and the caller walked away holding nothing. The
     * next release from anyone else dropped the count to zero and released a
     * player somebody was still using.
     *
     * The two tests below are the two orderings that reach a user.
     */
    @Test
    fun `a second screen closing does not release the player the first still holds`() {
        val first = PlaybackEngine.acquireForUi(ctx)
        PlaybackEngine.acquireForUi(ctx)
        PlaybackEngine.releaseUi()
        assertSame(
            "the first screen's hold was lost while the player was being built",
            first,
            PlaybackEngine.acquireForUi(ctx),
        )
    }

    @Test
    fun `closing the screen does not release the player the service is playing through`() {
        // The worst ordering: the service starts playback, a screen opens over
        // it and then goes away. Releasing here kills the music the
        // notification is still driving, and every later call the service
        // makes on that ExoPlayer throws.
        val forService = PlaybackEngine.acquireForService(ctx)
        PlaybackEngine.acquireForUi(ctx)
        PlaybackEngine.releaseUi()
        assertSame(
            "background playback lost its player when the screen closed",
            forService,
            PlaybackEngine.acquireForService(ctx),
        )
    }

    @Test
    fun `the player is released once both owners have let go`() {
        // The other half of the invariant: fixing the lost hold must not turn
        // into a player that never goes away.
        val first = PlaybackEngine.acquireForUi(ctx)
        PlaybackEngine.acquireForService(ctx)
        PlaybackEngine.releaseUi()
        PlaybackEngine.releaseService()
        assertNotSame(
            "nobody holds the player any more, so the next caller gets a fresh one",
            first,
            PlaybackEngine.acquireForUi(ctx),
        )
    }

    @Test
    fun `the sleep timer rides the player, not the screen`() {
        // A timer in a ViewModel dies when the app is swiped away - the exact
        // moment the user set it for. Hosting it on the session means the UI
        // and the service see one timer, alive exactly as long as the player.
        val forUi = PlaybackEngine.acquireForUi(ctx)
        val forService = PlaybackEngine.acquireForService(ctx)
        assertSame(forUi.sleepTimer, forService.sleepTimer)
        assertNull("no timer runs until someone starts one", forUi.sleepTimer.remainingMs.value)
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
    fun `a media-button receiver exists, or playback resumption never happens`() {
        // Media3 finds the target of a MEDIA_BUTTON broadcast by looking for a
        // receiver with this filter in the app's own manifest - the library
        // declares none of its own. Without it the merged manifest carried one
        // receiver (the profile installer) and zero MEDIA_BUTTON filters, so
        // `PlaybackService.onPlaybackResumption` could not be invoked at all:
        // MusicViz never appeared in the System UI media carousel after its
        // process died, and `lastPlayedResumption` - which
        // PlaybackResumptionTest covers in full - was code with no path to it
        // in production.
        val resolved =
            ctx.packageManager.queryBroadcastReceivers(
                Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(ctx.packageName),
                0,
            )
        assertEquals(
            "no MEDIA_BUTTON receiver: headset buttons and playback resumption reach nothing",
            1,
            resolved.size,
        )
        assertEquals(
            "androidx.media3.session.MediaButtonReceiver",
            resolved[0].activityInfo.name,
        )
        // The callers are other processes (System UI, Bluetooth, a headset),
        // exactly like the session service it drives.
        assertTrue(resolved[0].activityInfo.exported)
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
