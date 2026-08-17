package dev.geode.export

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lifetime a render runs under, and the manifest that lets it.
 *
 * A 4K render is tens of minutes of GPU work, and it used to run on
 * `viewModelScope` with `onCleared` cancelling it on purpose — so switching
 * apps or letting the screen time out threw it away silently. What has to hold
 * now is that the run's state is process-wide (so a returning screen can see
 * it, and a second render cannot start against a busy encoder) and that the
 * foreground service is actually declared, since an undeclared one is a crash
 * rather than a degradation.
 */
class ExportRunTest {
    @After
    fun reset() {
        ExportRun.finish()
    }

    @Test
    fun `nothing is running to begin with`() {
        ExportRun.finish()
        assertFalse(ExportRun.running)
        assertNull(ExportRun.state.value.progress)
    }

    @Test
    fun `beginning a run publishes it with no progress yet`() {
        ExportRun.begin("Blue Monday.mp3")
        assertTrue(ExportRun.running)
        // Indeterminate until the length is known: a determinate bar frozen at
        // zero reads as a hang.
        assertNull(ExportRun.state.value.progress)
        assertEquals("Blue Monday.mp3", ExportRun.state.value.label)
    }

    @Test
    fun `progress is published while running`() {
        ExportRun.begin("track")
        ExportRun.publish(0.42f)
        assertEquals(0.42f, ExportRun.state.value.progress!!, 1e-6f)
    }

    @Test
    fun `progress is clamped rather than trusted`() {
        ExportRun.begin("track")
        ExportRun.publish(5f)
        assertEquals(1f, ExportRun.state.value.progress!!, 0f)
        ExportRun.publish(-1f)
        assertEquals(0f, ExportRun.state.value.progress!!, 0f)
    }

    /** Stops a late callback from resurrecting a finished run's notification. */
    @Test
    fun `progress published after finishing is ignored`() {
        ExportRun.begin("track")
        ExportRun.finish()
        ExportRun.publish(0.9f)
        assertFalse(ExportRun.running)
        assertNull(ExportRun.state.value.progress)
    }

    @Test
    fun `finishing clears the label so no notification outlives the run`() {
        ExportRun.begin("track")
        ExportRun.publish(0.5f)
        ExportRun.finish()
        assertEquals("", ExportRun.state.value.label)
    }

    /** The scope has to outlive every screen, or nothing above matters. */
    @Test
    fun `the run scope is not tied to a screen`() {
        assertTrue("the export scope died", ExportRun.scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }

    @Test
    fun `the foreground service and its permissions are declared`() {
        val manifest = File(repoDir(), "src/main/AndroidManifest.xml").readText()
        assertTrue("ExportService is not declared", ".export.ExportService" in manifest)
        assertTrue(
            "the service declares no mediaProcessing type",
            "mediaProcessing" in manifest,
        )
        assertTrue(
            "FOREGROUND_SERVICE_MEDIA_PROCESSING is not requested",
            "FOREGROUND_SERVICE_MEDIA_PROCESSING" in manifest,
        )
        assertTrue(
            "no pre-Android-15 fallback type is requested",
            "FOREGROUND_SERVICE_DATA_SYNC" in manifest,
        )
    }

    /** The regression this whole change exists to prevent. */
    @Test
    fun `teardown no longer cancels a running export`() {
        val viewModel = File(repoDir(), "src/main/java/dev/geode/ui/PlayerViewModel.kt").readText()
        val onCleared = viewModel.substringAfter("override fun onCleared()").substringBefore("\n    }")
        assertFalse(
            "onCleared still cancels the export, which is what threw away long renders",
            "cancelExport()" in onCleared,
        )
    }

    @Test
    fun `a second export cannot start while one is running`() {
        val controller = File(repoDir(), "src/main/java/dev/geode/ui/ExportController.kt").readText()
        assertTrue(
            "startExport does not check the process-wide run, so a fresh screen could start a second one",
            "ExportRun.running" in controller,
        )
    }

    private fun repoDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, prefix + "src/main")
                if (candidate.isDirectory) return candidate.parentFile.parentFile
            }
            dir = dir.parentFile
        }
        error("app module not found from ${File("").absolutePath}")
    }
}
