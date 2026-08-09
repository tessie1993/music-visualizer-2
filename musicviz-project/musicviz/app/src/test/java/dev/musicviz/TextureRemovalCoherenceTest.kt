package dev.musicviz

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.data.TextureStore
import dev.musicviz.ui.PlayerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Removing a texture must not strand the milk selection on a deleted file.
 *
 * TextureStore.removeDetailed deletes the texture AND the generated display
 * preset that references it. When that preset is the one the engine is
 * showing, the persisted `milk_path` pointed at a dead file: the next launch
 * offered it to the engine, which answers a missing preset with its idle "M"
 * logo. [PlayerViewModel.removeTexture] now runs the removal off the main
 * thread and clears both the live [activeMilkPath] and the persisted key when
 * they named a deleted preset - and leaves them alone when they did not.
 *
 * The preset path is taken from the store rather than spelled out here: its
 * name is the store's business (it is keyed on the whole stored file name, so
 * cover.png and cover.jpg cannot collide), and a copy of that rule in a test
 * is a copy that can go stale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TextureRemovalCoherenceTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("musicviz-viz", Context.MODE_PRIVATE)

    private fun vm(): PlayerViewModel = PlayerViewModel(app)

    /** A texture on disk plus the generated display preset that shows it. */
    private fun plantTexture(base: String): Pair<File, File> {
        val tex = File(File(app.filesDir, "milk/textures").apply { mkdirs() }, "$base.png").apply { writeBytes(byteArrayOf(1)) }
        val gen = File(dev.musicviz.ui.TextureStore(app).generateDisplayPreset(tex.name))
        return tex to gen
    }

    /** Polls (idling the main looper) until the removal's main-thread half landed. */
    private fun await(
        what: String,
        done: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        fail("the texture removal never $what")
    }

    @Test
    fun `removing the texture behind the active milk preset clears the selection`() {
        val (tex, gen) = plantTexture("art")
        val v = vm()
        v.noteMilkPreset(gen.absolutePath)
        assertEquals(gen.absolutePath, v.activeMilkPath.value)

        v.removeTexture(tex.name)
        await("cleared the selection") { v.activeMilkPath.value == null }

        assertFalse(gen.exists())
        assertNull("the persisted milk_path would offer a dead file to the next launch", prefs.getString("milk_path", null))
    }

    @Test
    fun `removing an unrelated texture leaves the selection alone`() {
        val (tex, gen) = plantTexture("bystander")
        // A texture that stays, so the post-removal listing is observably the
        // removal's own publication and not the constructor's initial value.
        plantTexture("keeperTex")
        val keeper = File(File(app.filesDir, "milk").apply { mkdirs() }, "keeper.milk").apply { writeText("x") }
        val v = vm()
        v.noteMilkPreset(keeper.absolutePath)

        v.removeTexture(tex.name)
        await("published its listing") {
            v.textures.value.any { it.name == "keeperTex.png" } && v.textures.value.none { it.name == tex.name }
        }

        assertFalse(gen.exists())
        assertEquals(keeper.absolutePath, v.activeMilkPath.value)
        assertEquals(keeper.absolutePath, prefs.getString("milk_path", null))
    }
}
