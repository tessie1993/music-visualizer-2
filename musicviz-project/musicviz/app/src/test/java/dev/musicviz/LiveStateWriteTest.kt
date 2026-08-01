package dev.musicviz

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.ui.PlayerViewModel
import dev.musicviz.ui.Preset
import dev.musicviz.ui.PresetStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The live viz state is written on a coalescing window off the main thread,
 * because [PlayerViewModel.setSceneParams] is the funnel for every Customize
 * slider, for every pinch/twist touch-move event and for take replay at 30 Hz -
 * and one write is a 171-field serialization plus a rewrite of the whole prefs
 * file. Coalescing is only safe if the LAST value still lands, so that is what
 * these cover, alongside the atomicity of the flow the window reads from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveStateWriteTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private val prefs get() = context.getSharedPreferences("musicviz-viz", Context.MODE_PRIVATE)

    @Before
    fun clean() {
        prefs.edit().clear().commit()
    }

    /** The persisted live state, once it satisfies [matches] or the deadline passes. */
    private fun awaitPersisted(matches: (Preset) -> Boolean): Preset? {
        // Generous on purpose: the assertion is that the value lands at all,
        // and a tight bound would only make the suite flaky under load.
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val stored = prefs.getString("live_state", null)
            val parsed = stored?.let { runCatching { PresetStore.fromJson(it) }.getOrNull() }
            if (parsed != null && matches(parsed)) return parsed
            Thread.sleep(20)
        }
        return null
    }

    @Test
    fun `the last value of a burst is the one that reaches disk`() {
        // A pinch gesture, a swept slider or a replaying take produces tens of
        // these per second. A window that dropped the final value would be a
        // worse bug than the writes it removes: the user lets go and the app
        // comes back to somewhere they passed through.
        val v = PlayerViewModel(context)
        repeat(60) { step ->
            v.setSceneParams(v.vizState.value.params.copy(zoom = 1f + step * 0.01f))
        }
        val final = v.vizState.value.params.zoom
        val persisted = awaitPersisted { it.params.zoom > 1.5f }
        assertTrue("The final value of the burst never reached disk", persisted != null)
        assertEquals(final, persisted!!.params.zoom, 1e-4f)
    }

    @Test
    fun `a single change still lands without another one to push it`() {
        // The window is trailing, so nothing needs to follow a change for it to
        // be written - the common case is one slider moved and then nothing.
        val v = PlayerViewModel(context)
        v.selectScene(dev.musicviz.render.scene.SceneIds.WATER)
        val persisted = awaitPersisted { it.sceneId == dev.musicviz.render.scene.SceneIds.WATER }
        assertEquals(dev.musicviz.render.scene.SceneIds.WATER, persisted?.sceneId)
    }

    @Test
    fun `a random colour roll never drops a change made alongside it`() {
        // randomStepNow reads the params, rolls colours onto them and writes
        // them back. Done as a read-then-write it publishes a snapshot, so any
        // field another writer set in between is silently reverted - the same
        // shape that left an analysed track showing 0 BPM and no sections
        // because the 500 ms poll wrote its pre-analysis copy back over the
        // analysis. An atomic update can only ever change what it means to.
        val v = PlayerViewModel(context)
        v.setRandomizeColors(true)
        val writer =
            Thread {
                repeat(400) { v.reportShaderError("boom $it") }
            }
        writer.start()
        repeat(400) { v.randomStepNow() }
        writer.join()
        // Whatever the last shader error was, the colour rolls cannot have
        // reverted it to a value the writer had already moved past.
        assertEquals("boom 399", v.vizState.value.shaderError)
    }

    @Test
    fun `intelligence and analysis fields are not clobbered by a colour roll`() {
        val v = PlayerViewModel(context)
        v.setRandomizeColors(true)
        v.setIntelligenceMode(dev.musicviz.analysis.IntelligenceMode.AUTO)
        v.randomStepNow()
        assertEquals(dev.musicviz.analysis.IntelligenceMode.AUTO, v.vizState.value.intelligenceMode)
        assertTrue(v.vizState.value.randomizeColors)
    }
}
