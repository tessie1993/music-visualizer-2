package dev.musicviz

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.data.Preset
import dev.musicviz.data.PresetStore
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.ui.BuiltInPresets
import dev.musicviz.ui.PlayerViewModel
import dev.musicviz.ui.PresetLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An imported preset name is laundered exactly like a saved one.
 *
 * " · " is reserved for built-in presets - [BuiltInPresets.isBuiltIn] matches
 * on it - and savePreset has always replaced it. The import path did not, so
 * a shared preset whose name carried the separator was classified BUILT-IN on
 * arrival: hidden from the user's own list, undeletable in the browser, and
 * invisible to the collision suffixing. One laundering rule, both doors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresetImportLaunderTest {
    private fun vm(): PlayerViewModel = PlayerViewModel(ApplicationProvider.getApplicationContext<Application>())

    private fun linkFor(name: String): String = PresetLink.encode(PresetStore.toJson(Preset(name, SceneIds.FLUID, 0.6f, 0.12f, null)))

    @Test
    fun `an imported name with the built-in separator is laundered like savePreset does`() {
        val v = vm()
        val imported = v.importPresetLink(linkFor("Stranger · Danger"))
        assertEquals("Stranger - Danger", imported)
        assertFalse(
            "the imported preset classifies as built-in - invisible and undeletable",
            BuiltInPresets.isBuiltIn(imported!!),
        )
        assertTrue(v.vizState.value.presets.any { it.name == imported })
        // And it stays deletable - the exact ability the misclassification removed.
        v.deletePreset(imported)
        assertFalse(v.vizState.value.presets.any { it.name == imported })
    }

    @Test
    fun `a laundered import still gets the collision suffix`() {
        val v = vm()
        assertEquals("Dusk - Set", v.importPresetLink(linkFor("Dusk · Set")))
        assertEquals("Dusk - Set 2", v.importPresetLink(linkFor("Dusk · Set")))
    }
}
