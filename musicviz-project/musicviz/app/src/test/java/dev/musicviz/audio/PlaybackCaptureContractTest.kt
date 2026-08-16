package dev.musicviz.audio

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Source-level contracts on the playback-capture pair that no JVM test can
 * reach behaviourally: Robolectric cannot mint a real MediaProjection for the
 * service, and the zombie-worker fence in the shared [AudioCapturePump] runs
 * on a thread whose reads cannot be intercepted for [PlaybackCapture] the way
 * [MicCaptureTest] intercepts the microphone's.
 */
class PlaybackCaptureContractTest {
    /**
     * A second start intent - consent granted again while a capture is
     * already up - must stop the projection it replaces, or the old one keeps
     * the capture privilege alive with nothing owning it. Order matters
     * twice: the callback comes off first because `Callback.onStop` fires on
     * a programmatic `stop()` too, and left registered it would
     * publish(null)/stopSelf() over the projection replacing it; and the old
     * projection must be gone before the new one is published.
     */
    @Test
    fun `a replaced projection is unregistered then stopped before the new one is published`() {
        val body =
            source("PlaybackCaptureService.kt")
                .substringAfter("fun onStartCommand")
                .substringBefore("override fun onDestroy")
        val unregister = body.indexOf("unregisterCallback(projectionCallback)")
        val stopOld = body.indexOf(".stop()")
        val publish = body.indexOf("MediaProjectionHolder.publish(mp)")
        assertTrue("onStartCommand never publishes the new projection", publish >= 0)
        assertTrue("onStartCommand never unregisters the projection it replaces", unregister in 0 until stopOld)
        assertTrue("the old projection must be stopped before the new one is published", stopOld in 0 until publish)
    }

    /**
     * The stop-then-start fence: the capture worker gates its loop AND the
     * write after a blocked read on the run's generation, or a worker that
     * outlived stop()'s bounded join keeps feeding the ring alongside the run
     * that replaced it. [MicCaptureTest] proves the fence works; this pins
     * that [AudioCapturePump] - the one worker both captures now run on -
     * keeps carrying it, so a rewrite cannot quietly lose the fence for
     * either capture.
     */
    @Test
    fun `the shared capture pump fences its loop and its write on the generation`() {
        val src = source("AudioCapturePump.kt")
        assertTrue(
            "AudioCapturePump.kt: worker loop is not generation-fenced",
            src.contains("while (running && runGeneration == generation)"),
        )
        assertTrue(
            "AudioCapturePump.kt: a read that straddled stop-then-start is not discarded",
            src.contains("if (runGeneration != generation) break"),
        )
    }

    /**
     * Both onStartCommand branches that cannot produce a projection must tick
     * [MediaProjectionHolder.noteStartFailure] before dying, or the ViewModel's
     * "waiting for the capture permission…" state has nothing to wake it (the
     * holder's StateFlow already holds null and will not repeat it). The
     * malformed-intent branch is pinned behaviourally by
     * [PlaybackCaptureServiceTest]; the getMediaProjection-refused branch
     * cannot be reached without a real projection manager, so it is pinned
     * here: every early return except the user's own ACTION_STOP carries the
     * tick.
     */
    @Test
    fun `every projection-less start path ticks the failure signal`() {
        val body =
            source("PlaybackCaptureService.kt")
                .substringAfter("fun onStartCommand")
                .substringBefore("override fun onDestroy")
        // Statement lines only - the file's own comments mention stopSelf too.
        val code = body.lines().filter { !it.trim().startsWith("//") }
        val stops = code.withIndex().filter { it.value.trim() == "stopSelf()" }.map { it.index }
        assertTrue("expected the ACTION_STOP exit plus two failure exits", stops.size >= 3)
        var from = 0
        stops.forEachIndexed { i, at ->
            val branch = code.subList(from, at).joinToString("\n")
            if (i == 0) {
                // First stopSelf() is ACTION_STOP - the user asked, nothing failed.
                assertTrue(
                    "the ACTION_STOP branch must not report a failure",
                    !branch.contains("noteStartFailure()"),
                )
            } else {
                assertTrue(
                    "projection-less exit #$i does not tick noteStartFailure() before stopSelf()",
                    branch.contains("noteStartFailure()"),
                )
            }
            from = at
        }
    }

    /**
     * UNPROCESSED is a request, not a guarantee: the platform contract is
     * that an app checks PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED first,
     * because on a device without support the source silently behaves like an
     * ordinary voice source - AGC and noise suppression included, which
     * flatten exactly the dynamics the beat tracker keys off. Where it is not
     * declared, VOICE_RECOGNITION is the documented flat-tuned fallback.
     */
    @Test
    fun `the microphone only asks for UNPROCESSED where the device declares support`() {
        val src = source("MicCapture.kt")
        assertTrue(
            "MicCapture never checks PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED",
            src.contains("PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED"),
        )
        assertTrue(
            "the fallback source must be VOICE_RECOGNITION, the flat-tuned one",
            src.contains("MediaRecorder.AudioSource.VOICE_RECOGNITION"),
        )
    }

    /**
     * Both captures meter what they hear ([MicCaptureTest] proves the
     * microphone's meter behaviourally); this pins that the shared pump
     * meters the samples that were actually written, inside the generation
     * fence, so a zombie worker can no longer move the meter either.
     */
    @Test
    fun `the shared capture pump meters inside the fence, after the write`() {
        // The write target moved from `ring.writeInterleaved` to `sink.write`
        // when the pump started feeding a PcmSink rather than one buffer, so
        // both rings receive live input. Updated with the rename, per
        // CLAUDE.md - a gate whose target no longer exists passes vacuously.
        val src = source("AudioCapturePump.kt")
        val write = src.indexOf("sink.write(")
        val meter = src.indexOf("noteLevel(floats,")
        assertTrue("AudioCapturePump.kt: worker never writes its samples", write >= 0)
        assertTrue("AudioCapturePump.kt: worker never meters its samples", meter >= 0)
        assertTrue("AudioCapturePump.kt: metering must follow the fenced write", meter > write)
    }

    /**
     * The blocked-app hint must flip MID-run, not only when a capture never
     * heard anything: start on YouTube, switch to Spotify, and the perfect
     * silence that follows is exactly what the hint exists to name. Pinned at
     * source level because [noteLevel] is protected and the pump's worker
     * thread cannot be intercepted for [PlaybackCapture] (see class doc); the
     * line asserted is the one that restarts the clock from the last audible
     * sample instead of only from stream start, mirroring [MicCapture].
     */
    @Test
    fun `playback capture restarts its silence clock from the last audible sample`() {
        val src = source("PlaybackCapture.kt")
        assertTrue(
            "PlaybackCapture.noteLevel latches blockedLikely only for never-audible runs; " +
                "a capture that goes silent mid-session (app switch to a capture-blocking " +
                "player) must trip it too - clock from lastAudibleAtMs, like MicCapture",
            src.contains("val quietSince = if (lastAudibleAtMs == 0L) startedAtMs else lastAudibleAtMs"),
        )
    }

    private fun source(name: String): String {
        val relatives =
            listOf(
                "src/main/java/dev/musicviz/audio/$name",
                "app/src/main/java/dev/musicviz/audio/$name",
            )
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (rel in relatives) {
                val candidate = File(dir, rel)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("$name not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
