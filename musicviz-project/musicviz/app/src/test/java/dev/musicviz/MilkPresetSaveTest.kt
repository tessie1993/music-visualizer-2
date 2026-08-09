package dev.musicviz

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.data.Preset
import dev.musicviz.data.PresetStore
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.ui.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A MilkDrop preset has to save the VISUAL, not just the sliders.
 *
 * The picture on a .milk style is painted by the preset file; SceneParams only
 * post-process it. A saved preset that carried the params alone reloaded onto
 * whatever the engine happened to have - projectM's idle "M" logo on a cold
 * start - so the thing the user pressed "Save" on was not what came back.
 *
 * These tests pin the whole chain: the source travels IN the preset (so it
 * survives a share, an import and a reinstall), it is materialized again on
 * apply, presets saved before the source was carried still resolve through
 * their copied file, and the loaded .milk survives an app restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MilkPresetSaveTest {
    private fun vm(): PlayerViewModel = PlayerViewModel(ApplicationProvider.getApplicationContext<Application>())

    private val source =
        """
        MILKDROP_PRESET_VERSION=201
        [preset00]
        comp_1=`sampler sampler_art;
        """.trimIndent()

    /** A .milk the user "loaded", outside the app's own preset directory. */
    private fun importedMilk(name: String = "loaded.milk"): File {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return File(File(app.filesDir, "milk").apply { mkdirs() }, name).apply { writeText(source) }
    }

    /**
     * The named preset, once the ViewModel's startup listing has published it.
     *
     * A fresh ViewModel starts with the built-ins and reads the user's own
     * presets off the main thread - listing them means a directory walk and a
     * parse per file, which is not work the first frame should wait for - so
     * "is it there yet" is a question with a deadline.
     */
    private fun presetNamed(
        v: PlayerViewModel,
        name: String,
    ): Preset {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            v.vizState.value.presets.firstOrNull { it.name == name }?.let { return it }
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            Thread.sleep(10)
        }
        return v.vizState.value.presets.first { it.name == name }
    }

    @Test
    fun saving_on_milkdrop_stores_the_milk_source_in_the_preset() {
        val v = vm()
        v.selectScene(SceneIds.MILKDROP)
        v.noteMilkPreset(importedMilk().absolutePath)
        v.savePreset("Neon set", null)

        // A NEW ViewModel = a fresh app process: the preset is re-read from
        // disk, which is where the "M" bug used to become visible.
        val preset = presetNamed(vm(), "Neon set")
        assertEquals(SceneIds.MILKDROP, preset.sceneId)
        assertEquals("the .milk source did not travel with the preset", source, preset.milkPreset)
    }

    @Test
    fun applying_a_milkdrop_preset_resolves_a_real_milk_file() {
        val v = vm()
        v.selectScene(SceneIds.MILKDROP)
        v.noteMilkPreset(importedMilk().absolutePath)
        v.savePreset("Neon set", null)

        val second = vm()
        val path = second.milkPresetPathFor(presetNamed(second, "Neon set"))
        assertNotNull("a saved MilkDrop preset resolved to no .milk at all", path)
        assertEquals(source, File(path!!).readText())
    }

    @Test
    fun a_carried_source_is_rewritten_when_its_file_is_gone() {
        // The reinstall / shared-preset case: the JSON is all there is.
        val v = vm()
        val preset = Preset("Shared look", SceneIds.MILKDROP, 0.6f, 0.12f, null, milkPreset = source)
        val path = v.milkPresetPathFor(preset)
        assertNotNull(path)
        File(path!!).delete()

        val again = v.milkPresetPathFor(preset)
        assertEquals(path, again)
        assertEquals(source, File(path).readText())
    }

    @Test
    fun a_preset_saved_before_sources_were_carried_still_finds_its_file() {
        // Older builds only left the copied .milk next to the preset; the
        // preset itself carries nothing. That file is still the visual.
        val app = ApplicationProvider.getApplicationContext<Application>()
        val legacy = File(File(app.filesDir, "milk").apply { mkdirs() }, "Old look.milk")
        legacy.writeText(source)

        val path = vm().milkPresetPathFor(Preset("Old look", SceneIds.MILKDROP, 0.6f, 0.12f))
        assertEquals(legacy.absolutePath, path)
    }

    @Test
    fun a_milkdrop_preset_with_nothing_to_load_resolves_to_null() {
        assertNull(vm().milkPresetPathFor(Preset("Never saved", SceneIds.MILKDROP, 0.6f, 0.12f)))
    }

    @Test
    fun other_styles_never_load_a_milk_file() {
        val v = vm()
        v.selectScene(SceneIds.FLUID)
        v.noteMilkPreset(importedMilk().absolutePath)
        v.savePreset("Fluid look", null)

        val preset = presetNamed(vm(), "Fluid look")
        assertNull("a non-MilkDrop preset captured a .milk", preset.milkPreset)
        assertNull(v.milkPresetPathFor(preset))
    }

    @Test
    fun the_milk_and_the_json_share_one_sanitized_name() {
        // "Live / set" used to write "Live _ set.json" next to a "Live / set
        // .milk" path - a directory that does not exist - so the copy failed
        // silently and the preset had no visual to restore.
        val v = vm()
        v.selectScene(SceneIds.MILKDROP)
        v.noteMilkPreset(importedMilk().absolutePath)
        v.savePreset("Live / set", null)

        val second = vm()
        val preset = presetNamed(second, "Live / set")
        val path = second.milkPresetPathFor(preset)
        assertNotNull(path)
        assertEquals(PresetStore.milkFileName("Live / set"), File(path!!).name)
        assertEquals(source, File(path).readText())
    }

    @Test
    fun the_loaded_milk_survives_a_restart() {
        // The style is restored on launch (VizUiState is persisted); without
        // the path the engine came back to its idle "M" instead of the visual.
        val file = importedMilk("kept.milk")
        val v = vm()
        v.selectScene(SceneIds.MILKDROP)
        v.noteMilkPreset(file.absolutePath)

        assertEquals(file.absolutePath, vm().activeMilkPath.value)
    }

    @Test
    fun a_deleted_milk_is_not_offered_to_the_engine() {
        // A path left behind by a preset that has since been deleted must not
        // be handed to the engine on the next launch.
        val file = importedMilk("gone.milk")
        vm().noteMilkPreset(file.absolutePath)
        assertTrue(file.delete())
        assertNull(vm().activeMilkPath.value)
    }
}
