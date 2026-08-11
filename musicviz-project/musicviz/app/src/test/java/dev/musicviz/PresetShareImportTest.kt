package dev.musicviz

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.ui.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Sharing a preset has two halves, and both have to exist.
 *
 * A short preset travels as a link, which the Presets tab pastes back in. A
 * preset carrying a custom shader is too long for a chat message, so Share
 * sends its `.json` instead - and now every MilkDrop preset does too, since it
 * carries the .milk source that IS its visual. That branch used to be
 * write-only: the file went out and nothing in the app could read one back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PresetShareImportTest {
    private fun vm(): PlayerViewModel = PlayerViewModel(ApplicationProvider.getApplicationContext<Application>())

    private val milk = "MILKDROP_PRESET_VERSION=201\n[preset00]\nfDecay=0.97\n"

    /** Drives the async file import to completion and returns its result. */
    private fun importFile(
        v: PlayerViewModel,
        uri: Uri,
    ): String? {
        var result: String? = null
        var done = false
        v.importPresetFile(uri) {
            result = it
            done = true
        }
        val deadline = System.currentTimeMillis() + 10_000L
        while (!done && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idle()
            Thread.sleep(5)
        }
        v.awaitStoreWrites()
        return result
    }

    @Test
    fun a_shared_preset_file_imports_with_its_milk_source() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val v = vm()
        v.selectScene(SceneIds.MILKDROP)
        v.noteMilkPreset(File(File(app.filesDir, "milk").apply { mkdirs() }, "src.milk").apply { writeText(milk) }.absolutePath)
        v.savePreset("Shared look", null)
        v.awaitStoreWrites()

        // What Share hands to the other app.
        val shared = v.presetFile("Shared look")
        assertNotNull("nothing to share", shared)

        // Importing it back names it around the existing one rather than
        // overwriting the preset already saved under that name.
        val name = importFile(v, Uri.fromFile(shared))
        assertEquals("Shared look 2", name)

        val imported = v.vizState.value.presets.first { it.name == name }
        assertEquals(milk, imported.milkPreset)
        assertEquals(milk, File(v.milkPresetPathFor(imported)!!).readText())
    }

    @Test
    fun a_file_that_is_not_a_preset_imports_as_nothing() {
        val junk = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "junk.json")
        junk.writeText("{\"not\":\"a preset\"}")
        assertNull(importFile(vm(), Uri.fromFile(junk)))
    }

    @Test
    fun a_preset_link_still_imports() {
        val v = vm()
        v.selectScene(SceneIds.PLASMA)
        v.setSceneParams(v.vizState.value.params.copy(speed = 1.63f))
        v.savePreset("Link look", null)
        v.awaitStoreWrites()

        val link = v.presetShareLink("Link look")
        assertNotNull("a plain preset must still fit in a link", link)
        assertEquals("Link look 2", v.importPresetLink("look at this: $link"))
        assertEquals(1.63f, v.vizState.value.presets.first { it.name == "Link look 2" }.params.speed, 1e-4f)
    }
}
