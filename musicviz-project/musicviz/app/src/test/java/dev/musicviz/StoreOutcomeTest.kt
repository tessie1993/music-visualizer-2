package dev.musicviz

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.data.AtomicWrite
import dev.musicviz.data.Preset
import dev.musicviz.data.PresetStore
import dev.musicviz.data.TakeStore
import dev.musicviz.data.TextureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 *  - removing a texture also removes the display preset generated for it
 *    (otherwise the orphan renders noise or black once the image is gone) and
 *    reports that path so the caller can react - keyed on the WHOLE stored
 *    name, since two textures can share a stem;
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

    @Test
    fun `two textures sharing a stem get their own display presets`() {
        // cover.png and cover.jpg both survive safeTextureFileName as
        // themselves, and the preset key used to be the stem alone - so the
        // second Use overwrote the first's preset and either delete took the
        // other's away. The stored name is unique by construction; the key
        // has to be the whole of it.
        val store = TextureStore(ctx)
        val png = Uri.parse("content://test/cover.png")
        val jpg = Uri.parse("content://test/cover.jpg")
        registerImage(png, pngBytes(0xFF112233.toInt()))
        registerImage(jpg, pngBytes(0xFF445566.toInt()))
        store.import(listOf(png, jpg))
        assertEquals(listOf("cover.jpg", "cover.png"), store.list().map { it.name })

        val forPng = store.generateDisplayPreset("cover.png")
        val forJpg = store.generateDisplayPreset("cover.jpg")
        assertNotEquals("one preset file for two textures", forPng, forJpg)
        assertTrue(File(forPng).isFile)
        assertTrue(File(forJpg).isFile)

        val outcome = store.removeDetailed("cover.jpg")

        assertEquals(listOf(forJpg), outcome.removedGeneratedPresetPaths)
        assertFalse(File(forJpg).exists())
        assertTrue("deleting one texture deleted the other's live preset", File(forPng).isFile)
        assertEquals(listOf("cover.png"), outcome.textures.map { it.name })
    }

    @Test
    fun `an older stem-keyed preset is swept, but only when nothing still claims that stem`() {
        // Installs upgrading across the key change carry show_<stem>.milk
        // files this version no longer writes. Left behind they are exactly
        // the orphan removeDetailed exists to prevent - unless another
        // texture with the same stem is still there, in which case the
        // legacy file may be ITS preset and deleting it is the old bug.
        val store = TextureStore(ctx)
        val png = Uri.parse("content://test/art.png")
        val jpg = Uri.parse("content://test/art.jpg")
        registerImage(png, pngBytes(0xFF778899.toInt()))
        registerImage(jpg, pngBytes(0xFF99AABB.toInt()))
        store.import(listOf(png, jpg))
        val generated = File(ctx.filesDir, "milk/generated").apply { mkdirs() }
        val legacy = File(generated, "show_art.milk")

        legacy.writeText("MILKDROP_PRESET_VERSION=201\n[preset00]\n")
        assertEquals(
            "art.jpg is gone but art.png still answers to that stem",
            emptyList<String>(),
            store.removeDetailed("art.jpg").removedGeneratedPresetPaths,
        )
        assertTrue(legacy.isFile)

        assertEquals(
            listOf(legacy.absolutePath),
            store.removeDetailed("art.png").removedGeneratedPresetPaths,
        )
        assertFalse("the orphan outlived the last texture that could own it", legacy.exists())
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

    // ------------------------------------- .milk materialization (additive)

    @Test
    fun `materializeMilk writes the carried source under the preset's own sanitized stem`() {
        val store = PresetStore(ctx)
        // A name no filesystem takes verbatim: the .milk must land under the
        // same sanitized stem the preset's .json uses, not under a path with
        // a directory separator in it that silently fails to write.
        val name = "Live / set 1"
        val source = "MILKDROP_PRESET_VERSION=201\n[preset00]\nfDecay=0.98\n"

        val path = store.materializeMilk(name, source)

        assertNotNull(path)
        assertEquals(store.milkFileOf(name).absolutePath, path)
        assertEquals(source, File(path!!).readText())
        assertEquals(PresetStore.milkFileName(name), File(path).name)
        // No temp artifact left beside it - the write was atomic.
        assertEquals(listOf(File(path).name), filesFile("milk").listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `materializeMilk with no source uses the pre-source-era file as-is or reports none`() {
        val store = PresetStore(ctx)
        // Nothing carried, nothing on disk: there is nothing to render.
        assertNull(store.materializeMilk("Never Saved", null))

        // A preset saved before sources were carried only left the copied
        // file behind; that file IS the visual, not a broken preset.
        val legacy = store.milkFileOf("Old Era")
        legacy.parentFile!!.mkdirs()
        legacy.writeText("[preset00]\nzoom=1.0\n")

        assertEquals(legacy.absolutePath, store.materializeMilk("Old Era", null))
        assertEquals("[preset00]\nzoom=1.0\n", legacy.readText())
    }

    @Test
    fun `an interrupted materializeMilk leaves the previous milk whole`() {
        val store = PresetStore(ctx)
        val good = "[preset00]\nfGammaAdj=2.0\n"
        val path = store.materializeMilk("Show", good)!!

        // Block the write the way process death does: the temp path is taken.
        val file = File(path)
        assertTrue(File(file.absolutePath + dev.musicviz.data.AtomicWrite.TEMP_SUFFIX).mkdirs())

        // A changed source cannot be written - but the file the engine may be
        // rendering RIGHT NOW is still the whole previous preset, and the
        // returned path still points at it.
        assertEquals(path, store.materializeMilk("Show", "[preset00]\nbroken"))
        assertEquals(good, file.readText())

        // An identical source is recognized on disk and never rewritten, so
        // the blocked temp path does not even matter.
        assertEquals(path, store.materializeMilk("Show", good))
        assertEquals(good, file.readText())
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
