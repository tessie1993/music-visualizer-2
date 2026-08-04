package dev.musicviz.audio

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Source-level contracts on the playback-capture pair that no JVM test can
 * reach behaviourally: Robolectric cannot mint a real MediaProjection for the
 * service, and the zombie-worker fence in [PlaybackCapture] runs on a thread
 * whose reads cannot be intercepted the way [MicCaptureTest] intercepts the
 * microphone's.
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
     * The stop-then-start fence: both capture workers gate their loop AND the
     * write after a blocked read on the run's generation, or a worker that
     * outlived stop()'s bounded join keeps feeding the ring alongside the run
     * that replaced it. [MicCaptureTest] proves the fence works; this pins
     * that [PlaybackCapture] carries the identical one, since the two classes
     * are deliberate mirrors of each other.
     */
    @Test
    fun `both capture workers fence their loop and their write on the generation`() {
        for (name in listOf("MicCapture.kt", "PlaybackCapture.kt")) {
            val src = source(name)
            assertTrue(
                "$name: worker loop is not generation-fenced",
                src.contains("while (running && runGeneration == generation)"),
            )
            assertTrue(
                "$name: a read that straddled stop-then-start is not discarded",
                src.contains("if (runGeneration != generation) break"),
            )
        }
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
