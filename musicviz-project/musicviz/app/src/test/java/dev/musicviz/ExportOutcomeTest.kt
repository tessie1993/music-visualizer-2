package dev.musicviz

import android.net.Uri
import dev.musicviz.export.VideoExporter
import dev.musicviz.ui.exportUiStateFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An export ends three ways and the dialog has to be able to tell them apart.
 *
 * The render itself needs a hardware encoder, an EGL context and a muxer, so
 * none of it is reachable from a unit test - but the bug never was in the
 * render. [VideoExporter.export] used to answer with a nullable Uri, and null
 * meant BOTH "you cancelled" and "the provider refused to open the file for
 * writing", which the ViewModel then published as running=false, progress=1,
 * no uri and no error: a state the dialog reads as neither finished nor
 * failed, so it ran its bar to 100% and dropped silently back to the quality
 * chips. That mapping is what this pins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportOutcomeTest {
    private val saved = Uri.parse("content://media/external/video/media/7")

    @Test
    fun `a saved export carries the file the user can share`() {
        val state = exportUiStateFor(VideoExporter.Result.Saved(saved), customDestination = false)
        assertEquals(false, state.running)
        assertEquals(saved, state.resultUri)
        assertNull(state.error)
        assertEquals(1f, state.progress, 1e-6f)
    }

    @Test
    fun `a failed export says why and offers nothing to share`() {
        val state =
            exportUiStateFor(
                VideoExporter.Result.Failed("The folder you chose would not let the file be written."),
                customDestination = true,
            )
        assertEquals(false, state.running)
        assertNull(state.resultUri)
        assertNotNull(state.error)
    }

    @Test
    fun `a cancel says nothing at all`() {
        // Cancelling is the user's own decision, so the dialog goes back to the
        // options with no message - which is exactly why a failure must not
        // produce this same state.
        val state = exportUiStateFor(VideoExporter.Result.Cancelled, customDestination = false)
        assertEquals(false, state.running)
        assertNull(state.resultUri)
        assertNull(state.error)
    }

    @Test
    fun `a failure is not mistaken for a cancel or a success`() {
        val failed = exportUiStateFor(VideoExporter.Result.Failed("no write access"), customDestination = false)
        val cancelled = exportUiStateFor(VideoExporter.Result.Cancelled, customDestination = false)
        val ok = exportUiStateFor(VideoExporter.Result.Saved(saved), customDestination = false)
        assertNotEquals(cancelled, failed)
        assertNotEquals(ok, failed)
        assertNotEquals(ok, cancelled)
    }

    @Test
    fun `the chosen-destination flag only survives a save`() {
        // It decides which "Saved to..." sentence the dialog shows, so it is
        // meaningless - and misleading - on an outcome with no file.
        assertEquals(true, exportUiStateFor(VideoExporter.Result.Saved(saved), customDestination = true).customDestination)
        assertEquals(false, exportUiStateFor(VideoExporter.Result.Failed("x"), customDestination = true).customDestination)
    }
}
