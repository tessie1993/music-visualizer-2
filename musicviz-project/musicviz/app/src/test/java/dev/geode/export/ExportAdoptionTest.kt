package dev.geode.export

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.geode.analysis.FeatureTimeline
import dev.geode.ui.ExportController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A render outlives the screen that started it, so a fresh
 * [ExportController] adopts a run already in flight and mirrors its
 * progress. What has to hold about that mirror:
 *
 *  - it tracks the ADOPTED run only. The original collector never completed
 *    (`return@collect` ends one emission, not the collection), so it lived
 *    for the whole ViewModel and kept mirroring every LATER export too -
 *    overwriting the state startExport had just written with a two-field
 *    copy, which reset customDestination and presented a
 *    custom-destination export as a default one.
 *  - it goes back to idle when the adopted run ends; the outcome belongs to
 *    the controller that started the render and is unknowable from here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportAdoptionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Unconfined so StateFlow emissions reach the collector synchronously. */
    private fun controller() = ExportController(app, CoroutineScope(Dispatchers.Unconfined), UnusedHost)

    @After
    fun reset() {
        ExportRun.finish()
    }

    @Test
    fun `a running render is adopted with its progress`() {
        ExportRun.begin("left-behind.mp3")
        ExportRun.publish(0.3f, atMs = 1_000)
        val c = controller()
        assertTrue("a running render was ignored by the fresh screen", c.exportState.value.running)
        assertEquals(0.3f, c.exportState.value.progress, 1e-6f)
        ExportRun.publish(0.6f, atMs = 2_000)
        assertEquals(0.6f, c.exportState.value.progress, 1e-6f)
    }

    @Test
    fun `the mirror ends with the adopted run`() {
        ExportRun.begin("left-behind.mp3")
        val c = controller()
        ExportRun.finish()
        assertFalse("the adopted run ended but the dialog still shows a render", c.exportState.value.running)

        // A LATER run must be invisible to the adoption mirror: its state
        // belongs to whichever startExport call began it, and the leaked
        // collector overwriting that state is the customDestination bug.
        ExportRun.begin("a-later-render.mp3")
        ExportRun.publish(0.9f, atMs = 3_000)
        assertFalse(
            "the adoption collector is still alive and mirroring runs it never adopted",
            c.exportState.value.running,
        )
    }

    @Test
    fun `an idle process adopts nothing`() {
        val c = controller()
        assertFalse(c.exportState.value.running)
        // And later runs stay invisible, because no collector was started.
        ExportRun.begin("later")
        ExportRun.publish(0.5f, atMs = 1_000)
        assertFalse(c.exportState.value.running)
    }

    /** No test reaches startExport, so every member can refuse loudly. */
    private object UnusedHost : ExportController.Host {
        override val exportUri: Uri? get() = null
        override var cachedTimeline: FeatureTimeline? = null

        override suspend fun analyze(
            uri: Uri,
            onProgress: (Float) -> Unit,
        ): FeatureTimeline = error("not reached")

        override val guiPrefs: dev.geode.ui.GuiPrefs get() = error("not reached")
        override val sceneId: String get() = error("not reached")
        override val sceneParams: dev.geode.render.scene.SceneParams get() = error("not reached")

        override fun lfoConfigs(): List<dev.geode.render.LfoConfig> = error("not reached")

        override fun adsrConfigs(): List<dev.geode.render.AdsrConfig> = error("not reached")

        override fun loadExportTake(): dev.geode.data.PerformanceTake.Timeline? = error("not reached")

        override fun publishSections(
            uri: Uri,
            timeline: FeatureTimeline,
        ) = error("not reached")
    }
}
