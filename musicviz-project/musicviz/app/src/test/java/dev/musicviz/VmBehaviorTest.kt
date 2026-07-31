package dev.musicviz

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.analysis.IntelligenceMode
import dev.musicviz.ui.HistoryRepository
import dev.musicviz.ui.HistoryStore
import dev.musicviz.ui.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VmBehaviorTest {
    private fun vm(): PlayerViewModel = PlayerViewModel(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun live_customization_survives_restart() {
        val first = vm()
        first.selectScene(dev.musicviz.render.scene.SceneIds.FLUID)
        first.setSceneParams(
            first.vizState.value.params.copy(
                speed = 1.77f,
                fluidCurl = 42f,
                fluidSplatRadius = 0.31f,
                fluidBloom = false,
            ),
        )
        first.setReactivity(attack = 0.83f, decay = 0.21f)
        // A new ViewModel = a fresh app process: the live state (scene, every
        // Customize slider, reactivity) must be restored, not reset to
        // defaults - the "customization loses all progress" bug.
        val second = vm()
        val s = second.vizState.value
        assertEquals(dev.musicviz.render.scene.SceneIds.FLUID, s.sceneId)
        assertEquals(1.77f, s.params.speed, 1e-4f)
        assertEquals(42f, s.params.fluidCurl, 1e-4f)
        assertEquals(0.31f, s.params.fluidSplatRadius, 1e-4f)
        assertFalse(s.params.fluidBloom)
        assertEquals(0.83f, s.attack, 1e-4f)
        assertEquals(0.21f, s.decay, 1e-4f)
    }

    @Test
    fun randomize_respects_locks() {
        val v = vm()
        val before = v.vizState.value.params
        v.toggleParamLock("Speed")
        v.randomizeParams()
        val after = v.vizState.value.params
        assertEquals(before.speed, after.speed, 0.0001f)
        // At least something unlocked must have moved.
        assertNotEquals(before.zoom, after.zoom)
    }

    @Test
    fun param_lock_toggles_and_persists_in_flow() {
        val v = vm()
        v.toggleParamLock("Zoom")
        assertTrue("Zoom" in v.lockedParams.value)
        v.toggleParamLock("Zoom")
        assertFalse("Zoom" in v.lockedParams.value)
    }

    @Test
    fun auto_mode_tristate_maps_random_and_intelligence() {
        val v = vm()
        v.cycleAutoMode() // 1 = random
        assertEquals(1, v.autoMode.value)
        assertTrue(v.vizState.value.randomEnabled)
        v.cycleAutoMode() // 2 = intelligent
        assertEquals(2, v.autoMode.value)
        assertFalse(v.vizState.value.randomEnabled)
        assertEquals(IntelligenceMode.AUTO, v.vizState.value.intelligenceMode)
        v.cycleAutoMode() // 0 = off
        assertEquals(0, v.autoMode.value)
        assertEquals(IntelligenceMode.MANUAL, v.vizState.value.intelligenceMode)
    }

    @Test
    fun preset_lock_blocks_random_step() {
        val v = vm()
        val scene = v.vizState.value.sceneId
        v.togglePresetLock()
        v.randomStepNow()
        assertEquals(scene, v.vizState.value.sceneId)
    }

    @Test
    fun preset_save_delete_and_folders() {
        val v = vm()
        v.addPresetFolder("Chill")
        assertTrue("Chill" in v.presetFolders())
        v.savePreset("My Test · Preset", null, folder = "Chill")
        // " · " sanitized so the preset stays deletable (built-in marker).
        val saved =
            v.vizState.value.presets
                .first { it.name.startsWith("My Test") }
        assertFalse(saved.name.contains(" · "))
        assertEquals("Chill", v.presetFolderOf(saved.name))
        v.deletePreset(saved.name)
        assertTrue(
            v.vizState.value.presets
                .none { it.name == saved.name },
        )
    }

    @Test
    fun history_store_orders_recent_and_most() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val h: HistoryRepository = HistoryStore(app)
        h.recordPlay("uri://a", "A")
        h.recordPlay("uri://b", "B")
        h.recordPlay("uri://b", "B")
        assertEquals("uri://b", h.recentlyPlayed().first().uri)
        assertEquals(2, h.mostPlayed().first().playCount)
    }
}
