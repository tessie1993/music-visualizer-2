package dev.musicviz.audio

import android.Manifest
import android.media.AudioRecord
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The live-input state machine: what [MicCapture.start] reports, and whether
 * [MicCapture.active] still tells the truth after the capture has ended.
 *
 * Both matter beyond this class. The ViewModel latches its switch on a null
 * return and short-circuits the next enable on `active`, so a start that
 * claims success it did not have, or an `active` that stays true over a
 * released recorder, leaves the user with a switch that is on and visuals that
 * are dead - with nothing on screen to explain it.
 *
 * What cannot be reached from here: Robolectric's AudioRecord always grants
 * `startRecording()`, so the "a call or another app holds the microphone"
 * refusal that [MicCapture.Failure.UNAVAILABLE] now reports has no unit test -
 * it needs a device with the microphone genuinely taken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MicCaptureTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** A recorder that hands back [result] for every read. */
    private fun readsReturning(result: Int) =
        object : ShadowAudioRecord.AudioRecordSource {
            override fun readInFloatArray(
                audioData: FloatArray,
                offsetInFloats: Int,
                sizeInFloats: Int,
                isBlocking: Boolean,
            ): Int = result

            override fun readInShortArray(
                audioData: ShortArray,
                offsetInShorts: Int,
                sizeInShorts: Int,
                isBlocking: Boolean,
            ): Int = result
        }

    private fun grantMic() {
        Shadows.shadowOf(ctx).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    /** Waits for [capture] to report itself inactive, up to [timeoutMs]. */
    private fun awaitInactive(
        capture: MicCapture,
        timeoutMs: Long = 2_000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!capture.active) return true
            Thread.sleep(5)
        }
        return !capture.active
    }

    /**
     * Installs [source] for every AudioRecord the test creates.
     *
     * `ShadowAudioRecord.setSource` is deprecated in favour of the provider
     * form, which is handed the AudioRecord being served; none of these tests
     * builds more than one at a time, so the provider ignores it and answers
     * with the same source.
     */
    private fun installSource(source: ShadowAudioRecord.AudioRecordSource) {
        ShadowAudioRecord.setSourceProvider { source }
    }

    @After
    fun clearSource() {
        ShadowAudioRecord.clearSource()
    }

    @Test
    fun `a refused permission is reported and nothing is left active`() {
        val capture = MicCapture(ctx, PcmRingBuffer())
        assertEquals(MicCapture.Failure.PERMISSION, capture.start())
        assertFalse(capture.active)
    }

    @Test
    fun `a started capture reports active until it is stopped`() {
        grantMic()
        installSource(readsReturning(0))
        val capture = MicCapture(ctx, PcmRingBuffer())
        var rate = 0
        assertNull(capture.start { rate = it })
        assertTrue(capture.active)
        assertTrue("the granted rate is reported to the caller", rate > 0)
        capture.stop()
        assertFalse(capture.active)
    }

    @Test
    fun `a read error clears active instead of leaving the switch stuck on`() {
        grantMic()
        // ERROR_DEAD_OBJECT is what a mid-capture phone call produces: the
        // worker releases the recorder and leaves, so `active` must follow.
        installSource(readsReturning(AudioRecord.ERROR_DEAD_OBJECT))
        val capture = MicCapture(ctx, PcmRingBuffer())
        assertNull(capture.start())
        assertTrue("capture should report the failure, not an open microphone", awaitInactive(capture))
    }

    @Test
    fun `a capture that died of a read error can be started again`() {
        grantMic()
        installSource(readsReturning(AudioRecord.ERROR_DEAD_OBJECT))
        val capture = MicCapture(ctx, PcmRingBuffer())
        assertNull(capture.start())
        assertTrue(awaitInactive(capture))
        // The point of the fix: with `active` still true the ViewModel's
        // `if (micCapture.active) return null` turned this into a no-op and
        // the user had to toggle the switch off and on again.
        installSource(readsReturning(0))
        assertNull(capture.start())
        assertTrue(capture.active)
        capture.stop()
        assertFalse(capture.active)
    }

    /**
     * The stop-then-start race: stop() joins the worker for at most half a
     * second, so a read still blocked past that leaves the old worker alive
     * when the next start() spins up a new one. The generation fence must
     * make the survivor discard its late samples and leave, instead of
     * double-feeding the ring alongside the new worker - which is exactly
     * what `while (running)` alone did once the new run flipped the shared
     * flag back on.
     */
    @Test
    fun `a worker that outlives its stop cannot feed the next run's ring`() {
        grantMic()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var zombie: Thread? = null
        // First recorder: the read blocks until the test releases it, then
        // hands back a full buffer of non-zero samples - the shape of a
        // device read that straddled the stop.
        val blockedRead =
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInFloatArray(
                    audioData: FloatArray,
                    offsetInFloats: Int,
                    sizeInFloats: Int,
                    isBlocking: Boolean,
                ): Int {
                    zombie = Thread.currentThread()
                    entered.countDown()
                    release.await()
                    audioData.fill(0.5f)
                    return sizeInFloats
                }

                override fun readInShortArray(
                    audioData: ShortArray,
                    offsetInShorts: Int,
                    sizeInShorts: Int,
                    isBlocking: Boolean,
                ): Int {
                    zombie = Thread.currentThread()
                    entered.countDown()
                    release.await()
                    audioData.fill(16_000)
                    return sizeInShorts
                }
            }
        // Second recorder reads nothing, so any sample in the ring at the end
        // can only have come from the zombie. Keyed per AudioRecord because
        // the provider is consulted on reads, not once per recorder.
        val perRecord = ConcurrentHashMap<AudioRecord, ShadowAudioRecord.AudioRecordSource>()
        val order = AtomicInteger()
        ShadowAudioRecord.setSourceProvider { rec ->
            perRecord.computeIfAbsent(rec) {
                if (order.getAndIncrement() == 0) blockedRead else readsReturning(0)
            }
        }
        val ring = PcmRingBuffer()
        val capture = MicCapture(ctx, ring)
        assertNull(capture.start())
        assertTrue("worker never reached its read", entered.await(2, TimeUnit.SECONDS))
        // The join inside stop() times out: the read is still blocked, so the
        // worker survives as a zombie.
        capture.stop()
        assertNull(capture.start())
        assertTrue(capture.active)
        // Wake the zombie only now, with the new run already up.
        release.countDown()
        zombie?.join(2_000)
        assertEquals("stale worker fed the ring after its stop", 0L, ring.currentWriteIndex())
        capture.stop()
    }
}
