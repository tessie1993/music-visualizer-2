package dev.musicviz

import dev.musicviz.data.PerformanceTake
import dev.musicviz.ui.exportSceneIdFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A take export renders on the style the take was RECORDED on.
 *
 * The export used to build its scene from whatever style was live when the
 * dialog opened and only replay the take's parameters over it - so a fluid
 * take exported while looking at hyperspace rendered hyperspace moved by
 * fluid sliders. The minimum honest fix shipped here: the export scene comes
 * from the take's FIRST scene event ([exportSceneIdFor]); PlayerViewModel's
 * startExport resolves it through the `sceneFactoryFor` hook ExportHost now
 * passes. Mid-take scene switches still do not render (one scene is built up
 * front - see TakeUiState.exportTake), which the dialog continues to say.
 *
 * Robolectric because the take format is org.json (same as PerformanceTakeTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportTakeSceneTest {
    private fun take(eventsJson: String): PerformanceTake.Timeline =
        PerformanceTake.Timeline("""{"name":"t","durationMs":5000,"events":$eventsJson}""")

    @Test
    fun `the export scene is the take's first scene event`() {
        val t = take("""[{"t":0,"s":"fluid","p":{}},{"t":900,"s":"hyperspace","p":{}}]""")
        assertEquals("fluid", exportSceneIdFor(t, "beam"))
    }

    @Test
    fun `no take means the live scene`() {
        assertEquals("beam", exportSceneIdFor(null, "beam"))
    }

    @Test
    fun `an empty or sceneless take falls back to the live scene`() {
        assertEquals("beam", exportSceneIdFor(take("[]"), "beam"))
        assertEquals("beam", exportSceneIdFor(take("""[{"t":0,"p":{}}]"""), "beam"))
    }

    @Test
    fun `reading the scene leaves the take replayable from the start`() {
        // exportSceneIdFor probes stateAt(0); the export's per-frame reads
        // then walk forward from 0. The forward-only cursor must not have
        // been left past the opening keyframe.
        val t = take("""[{"t":0,"s":"fluid","p":{}},{"t":1000,"s":"hyperspace","p":{}}]""")
        exportSceneIdFor(t, "beam")
        assertEquals("fluid", t.stateAt(0L)?.sceneId)
        assertEquals("hyperspace", t.stateAt(1500L)?.sceneId)
    }

    @Test
    fun `startExport and ExportHost are wired through the scene resolver`() {
        // Source scan, following ParamSurface: the helper above only matters
        // if the export path actually consults it, and only the code records
        // that. Robolectric cannot run MediaCodec, so the wiring is pinned
        // where it lives.
        // startExport lives in ExportController since the ViewModel decomposition.
        val controller = ParamSurface.source("ui/ExportController.kt")
        assertTrue(
            "startExport no longer resolves the take's scene through exportSceneIdFor",
            controller.contains("sceneFactoryFor(exportSceneIdFor(exportTake"),
        )
        val host = ParamSurface.source("ui/ExportHost.kt")
        assertTrue(
            "ExportHost no longer hands startExport a scene resolver",
            Regex("""sceneFactoryFor\s*=""").containsMatchIn(host),
        )
    }
}
