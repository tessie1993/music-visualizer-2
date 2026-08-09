package dev.musicviz

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.analysis.IntelligenceMode
import dev.musicviz.data.HistoryStore
import dev.musicviz.data.Preset
import dev.musicviz.data.PresetStore
import dev.musicviz.ui.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VmBehaviorTest {
    private fun vm(): PlayerViewModel = PlayerViewModel(ApplicationProvider.getApplicationContext<Application>())

    /**
     * Waits for the debounced live-state write to reach the prefs file.
     *
     * The deadline is generous on purpose: what is being asserted is that the
     * value lands at all, not how fast, and a tight bound would only make the
     * suite flaky on a loaded machine.
     */
    private fun awaitPersistedLiveState(matches: (dev.musicviz.data.Preset) -> Boolean) {
        val prefs =
            ApplicationProvider
                .getApplicationContext<Application>()
                .getSharedPreferences("musicviz-viz", android.content.Context.MODE_PRIVATE)
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val stored = prefs.getString("live_state", null)
            if (stored != null && runCatching { matches(PresetStore.fromJson(stored)) }.getOrDefault(false)) return
            Thread.sleep(20)
        }
        fail("The live viz state never reached the prefs file")
    }

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
        // The live state is written on a coalescing window off the main thread,
        // so "has it been persisted yet" is a question with a deadline.
        awaitPersistedLiveState { it.params.speed > 1.7f }
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
    fun auto_mode_cycles_through_every_mode_exclusively() {
        // One control, four mutually exclusive modes: "rotate randomly" and
        // "hold a look per section" are opposite instructions, so the cycle is
        // what keeps them from both being on with no visible winner.
        val v = vm()
        v.cycleAutoMode() // 1 = random
        assertEquals(1, v.autoMode.value)
        assertTrue(v.vizState.value.randomEnabled)
        assertFalse(v.vizState.value.sectionStaging)
        v.cycleAutoMode() // 2 = intelligent
        assertEquals(2, v.autoMode.value)
        assertFalse(v.vizState.value.randomEnabled)
        assertFalse(v.vizState.value.sectionStaging)
        assertEquals(IntelligenceMode.AUTO, v.vizState.value.intelligenceMode)
        v.cycleAutoMode() // 3 = sections
        assertEquals(3, v.autoMode.value)
        assertTrue(v.vizState.value.sectionStaging)
        assertFalse(v.vizState.value.randomEnabled)
        assertEquals(IntelligenceMode.MANUAL, v.vizState.value.intelligenceMode)
        v.cycleAutoMode() // 0 = off
        assertEquals(0, v.autoMode.value)
        assertFalse(v.vizState.value.sectionStaging)
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
    fun folder_rename_carries_its_presets_and_leaves_the_others_alone() {
        // A folder is a directory on disk, so renaming it has to take every
        // preset filed under it along - the Visuals hub reads a preset's folder
        // back per name, and one left pointing at the old directory would
        // vanish from the tree with no way to get it back.
        val v = vm()
        v.addPresetFolder("Chill")
        v.addPresetFolder("Loud")
        v.savePreset("Dusk", null, folder = "Chill")
        v.savePreset("Dawn", null, folder = "Chill")
        v.savePreset("Riot", null, folder = "Loud")
        v.renamePresetFolder("Chill", "Ambient")
        assertEquals("Ambient", v.presetFolderOf("Dusk"))
        assertEquals("Ambient", v.presetFolderOf("Dawn"))
        assertEquals("Loud", v.presetFolderOf("Riot"))
        assertTrue("Ambient" in v.presetFolders())
        assertFalse("Chill" in v.presetFolders())
    }

    @Test
    fun folder_rename_to_blank_or_an_existing_name_changes_nothing() {
        // The dialog refuses both before it calls through; this pins the store
        // half of the guard, because a rename that half-succeeded would merge
        // two folders or spill a folder's presets into the root.
        val v = vm()
        v.addPresetFolder("Chill")
        v.addPresetFolder("Loud")
        v.savePreset("Dusk", null, folder = "Chill")
        v.savePreset("Riot", null, folder = "Loud")
        v.renamePresetFolder("Chill", "")
        assertEquals("Chill", v.presetFolderOf("Dusk"))
        v.renamePresetFolder("Chill", "Loud")
        assertEquals("Chill", v.presetFolderOf("Dusk"))
        assertEquals("Loud", v.presetFolderOf("Riot"))
        assertTrue(v.presetFolders().containsAll(listOf("Chill", "Loud")))
    }

    @Test
    fun move_preset_relocates_one_preset_without_disturbing_its_siblings() {
        val v = vm()
        v.addPresetFolder("Chill")
        v.addPresetFolder("Loud")
        v.savePreset("Dusk", null, folder = "Chill")
        v.savePreset("Dawn", null, folder = "Chill")
        v.movePresetToFolder("Dusk", "Loud")
        assertEquals("Loud", v.presetFolderOf("Dusk"))
        assertEquals("Chill", v.presetFolderOf("Dawn"))
        // The move is a file rename, not a re-save: the preset itself has to
        // survive it intact, or filing one would quietly destroy it.
        assertTrue(
            v.vizState.value.presets
                .any { it.name == "Dusk" },
        )
    }

    @Test
    fun move_preset_to_root_takes_it_back_out_of_its_folder() {
        // The way out of a mis-chosen folder. Without a root destination the
        // only cure for one is to delete the preset and save it again.
        val v = vm()
        v.addPresetFolder("Chill")
        v.savePreset("Dusk", null, folder = "Chill")
        v.movePresetToFolder("Dusk", "")
        assertEquals("", v.presetFolderOf("Dusk"))
    }

    @Test
    fun history_store_orders_recent_and_most() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val h = HistoryStore(app)
        h.recordPlay("uri://a", "A")
        h.recordPlay("uri://b", "B")
        h.recordPlay("uri://b", "B")
        assertEquals("uri://b", h.recentlyPlayed().first().uri)
        assertEquals(2, h.mostPlayed().first().playCount)
    }
}
