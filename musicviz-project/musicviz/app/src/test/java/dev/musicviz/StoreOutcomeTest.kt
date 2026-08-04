package dev.musicviz

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.ui.Preset
import dev.musicviz.ui.PresetStore
import dev.musicviz.ui.TakeStore
import dev.musicviz.ui.TextureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The store-side contracts the Visuals hub actions lean on, against real
 * files in filesDir:
 *
 *  - removing a texture also removes the `show_<base>.milk` display preset
 *    generated for it (otherwise the orphan renders noise or black once the
 *    image is gone) and reports that path so the caller can react;
 *  - texture import answers per file - imported under which name, or skipped
 *    why - and validates CONTENT, not just extension, before anything
 *    touches the disk;
 *  - a preset folder can only be deleted while nothing is filed under it;
 *  - a take rename trims the new name and refuses a blank one, so the
 *    dialog's validation has a floor even when it is bypassed.
 *
 * NATIVE graphics mode is forced so [android.graphics.BitmapFactory] really
 * decodes: the rejects-garbage case is meaningless against a shadow that
 * accepts every byte string as a 100x100 image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MusicVizApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreOutcomeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun filesFile(name: String) = File(ctx.filesDir, name)

    @Before
    fun cleanFilesDir() {
        listOf("milk", "presets", "takes").forEach { filesFile(it).deleteRecursively() }
    }

    private fun registerImage(
        uri: Uri,
        bytes: ByteArray,
    ) = Shadows.shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))

    /** A real (decodable) PNG. */
    private fun pngBytes(argb: Int): ByteArray {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(argb)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // ------------------------------------------------- texture remove (fix 1)

    @Test
    fun `removing a texture also removes its generated display preset and reports its path`() {
        val store = TextureStore(ctx)
        val uri = Uri.parse("content://test/logo.png")
        registerImage(uri, pngBytes(0xFF3366CC.toInt()))
        store.import(listOf(uri))
        val presetPath = store.generateDisplayPreset("logo.png")
        assertTrue(File(presetPath).isFile)

        val outcome = store.removeDetailed("logo.png")

        assertTrue(outcome.removed)
        // The caller may be rendering exactly this preset; the path is how it
        // finds out its current .milk selection just went away.
        assertEquals(presetPath, outcome.removedGeneratedPresetPath)
        assertFalse(File(presetPath).exists())
        assertEquals(emptyList<String>(), outcome.textures.map { it.name })
    }

    @Test
    fun `removing a texture with no generated preset reports none`() {
        val store = TextureStore(ctx)
        val uri = Uri.parse("content://test/plain.png")
        registerImage(uri, pngBytes(0xFF224466.toInt()))
        store.import(listOf(uri))

        val outcome = store.removeDetailed("plain.png")

        assertTrue(outcome.removed)
        assertNull(outcome.removedGeneratedPresetPath)
    }

    @Test
    fun `the legacy remove entry point drops the generated preset for a hashed name too`() {
        // The stored (hashed) name is what list() shows, what
        // generateDisplayPreset was called with, and what remove receives -
        // so the base derivations can never disagree.
        val store = TextureStore(ctx)
        val uri = Uri.parse("content://test/夜曲.png")
        registerImage(uri, pngBytes(0xFF102040.toInt()))
        val stored = store.import(listOf(uri)).single().name
        val presetPath = store.generateDisplayPreset(stored)
        assertTrue(File(presetPath).isFile)

        assertEquals(emptyList<String>(), store.remove(stored).map { it.name })

        assertFalse(File(presetPath).exists())
    }

    // ------------------------------------------------- texture import (fix 9)

    @Test
    fun `import reports per-file outcomes and rejects non-image bytes`() {
        val store = TextureStore(ctx)
        val good = Uri.parse("content://test/cover.png")
        val garbagePng = Uri.parse("content://test/garbage.png")
        val notes = Uri.parse("content://test/notes.txt")
        val garbageDds = Uri.parse("content://test/fake.dds")
        val garbageTga = Uri.parse("content://test/fake.tga")
        registerImage(good, pngBytes(0xFF00CC88.toInt()))
        registerImage(garbagePng, ByteArray(64) { (it % 7).toByte() })
        registerImage(notes, "not pixels".toByteArray())
        registerImage(garbageDds, ByteArray(128) { 9 })
        registerImage(garbageTga, ByteArray(64) { 0x55 })

        val outcome = store.importDetailed(listOf(good, garbagePng, notes, garbageDds, garbageTga))

        val byName = outcome.results.associateBy { it.name }
        assertEquals(5, outcome.results.size)
        assertTrue(byName.getValue("cover.png").imported)
        assertEquals("cover.png", byName.getValue("cover.png").storedName)
        assertNull(byName.getValue("cover.png").skipReason)
        for (skippedName in listOf("garbage.png", "notes.txt", "fake.dds", "fake.tga")) {
            val r = byName.getValue(skippedName)
            assertFalse(skippedName, r.imported)
            assertNull(skippedName, r.storedName)
            assertNotNull(skippedName, r.skipReason)
        }

        // Only the real image landed, and the skipped files left NOTHING
        // behind - no temp artifacts, no junk the picker will list.
        assertEquals(listOf("cover.png"), outcome.textures.map { it.name })
        assertEquals(
            listOf("cover.png"),
            filesFile("milk/textures").listFiles().orEmpty().map { it.name },
        )
    }

    @Test
    fun `dds and tga files with real headers pass the sniff`() {
        // BitmapFactory cannot decode either format, so these go through the
        // magic-byte check instead - they must not be rejected as non-images.
        val store = TextureStore(ctx)
        val dds = Uri.parse("content://test/metal.dds")
        val tga = Uri.parse("content://test/cloth.tga")
        registerImage(dds, "DDS ".toByteArray() + ByteArray(124))
        val tgaHeader =
            ByteArray(21).also {
                it[2] = 2 // uncompressed truecolor
                it[12] = 1 // width 1
                it[14] = 1 // height 1
                it[16] = 24 // 24bpp
            }
        registerImage(tga, tgaHeader)

        val outcome = store.importDetailed(listOf(dds, tga))

        assertEquals(listOf(true, true), outcome.results.map { it.imported })
        assertEquals(listOf("cloth.tga", "metal.dds"), outcome.textures.map { it.name })
    }

    @Test
    fun `a skipped re-import leaves the existing texture byte-for-byte intact`() {
        val store = TextureStore(ctx)
        val uri = Uri.parse("content://test/logo.png")
        val good = pngBytes(0xFF884422.toInt())
        registerImage(uri, good)
        store.import(listOf(uri))

        // The same picked name, now backed by junk: validation must refuse it
        // BEFORE the write, or the atomic rename would replace the good image.
        registerImage(uri, ByteArray(256) { 0x2A })
        val outcome = store.importDetailed(listOf(uri))

        assertFalse(outcome.results.single().imported)
        assertEquals(good.toList(), filesFile("milk/textures/logo.png").readBytes().toList())
        assertEquals(listOf("logo.png"), outcome.textures.map { it.name })
    }

    // ------------------------------------------------ preset folders (fix 10)

    @Test
    fun `removeFolder refuses a folder that still holds a preset`() {
        val store = PresetStore(ctx)
        store.addFolder("Sets")
        store.save(Preset("Neon", "fluid", 0.5f, 0.2f), "Sets")

        assertFalse(store.removeFolder("Sets"))

        // Refused means REFUSED: the folder and its preset are untouched.
        assertEquals(listOf("Sets"), store.folders())
        assertEquals("Sets", store.folderOf("Neon"))
    }

    @Test
    fun `removeFolder deletes an empty folder and empty subfolders with it`() {
        val store = PresetStore(ctx)
        store.addFolder("Sets")
        store.save(Preset("Neon", "fluid", 0.5f, 0.2f), "Sets")
        store.delete("Neon")
        // Structure without content goes too - only FILES block a delete.
        assertTrue(filesFile("presets/Sets/inner").mkdirs())

        assertTrue(store.removeFolder("Sets"))

        assertEquals(emptyList<String>(), store.folders())
        assertFalse(store.removeFolder("Sets")) // already gone
    }

    @Test
    fun `removeFolder refuses the root and blank names`() {
        val store = PresetStore(ctx)
        store.save(Preset("Kept", "fluid", 0.5f, 0.2f))

        assertFalse(store.removeFolder(""))
        assertFalse(store.removeFolder("   "))

        assertEquals(listOf("Kept"), store.list().map { it.name })
    }

    // --------------------------------------------------- take rename (fix 5)

    private fun takeJson(
        name: String,
        durationMs: Long,
    ) = """{"name":"$name","trackUri":"content://media/9","durationMs":$durationMs,"events":[]}"""

    @Test
    fun `take rename trims the new name and refuses a blank one`() {
        val store = TakeStore(ctx)
        store.save("Take 1", takeJson("Take 1", 5L))

        assertFalse(store.rename("Take 1", "   "))
        assertEquals(listOf("Take 1"), store.list().map { it.name })

        // Padding is dropped in the store, not just the dialog: " Encore "
        // and "Encore" must be one take, not two names one file apart.
        assertTrue(store.rename("Take 1", "  Encore  "))
        assertEquals(listOf("Encore"), store.list().map { it.name })
        assertEquals(5L, store.load("Encore")!!.durationMs)
        assertTrue(filesFile("takes/Encore.json").isFile)
    }

    @Test
    fun `take rename refuses a name another take already holds even with padding`() {
        val store = TakeStore(ctx)
        store.save("Take 1", takeJson("Take 1", 1L))
        store.save("Encore", takeJson("Encore", 2L))

        assertFalse(store.rename("Take 1", " Encore "))

        assertEquals(setOf("Take 1", "Encore"), store.list().map { it.name }.toSet())
        assertEquals(1L, store.load("Take 1")!!.durationMs)
        assertEquals(2L, store.load("Encore")!!.durationMs)
    }
}
