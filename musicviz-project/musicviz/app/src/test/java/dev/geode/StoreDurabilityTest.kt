package dev.geode

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.geode.data.AtomicWrite
import dev.geode.data.HistoryStore
import dev.geode.data.MusicPlaylist
import dev.geode.data.MusicPlaylistStore
import dev.geode.data.PaletteStore
import dev.geode.data.Preset
import dev.geode.data.PresetStore
import dev.geode.data.TakeStore
import dev.geode.data.TextureStore
import dev.geode.ui.LibraryTrack
import dev.geode.ui.TrackLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The stores that keep a JSON (or image) document they rewrite whole -
 * history, presets, playlists, palettes, takes, textures and the track
 * library - against real files in filesDir.
 *
 * All of them used `File.writeText`, which truncates its target to zero before
 * writing a byte. The app being killed inside that window - or the device
 * losing power - left invalid content behind, and because every one of them
 * parses inside `runCatching { … }.getOrDefault(emptyList())` the damage was
 * SILENT: the user simply found their history, playlists, palettes, textures
 * or takes empty, and the next write made it permanent.
 *
 * So each case here is one of the two properties that can only be checked
 * against a real file: a write that cannot complete leaves the PREVIOUS
 * document intact, and no store ever persists a fresh empty document over
 * data it merely failed to read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = GeodeApp::class)
class StoreDurabilityTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun filesFile(name: String) = File(ctx.filesDir, name)

    @Before
    fun cleanFilesDir() {
        listOf("history.json", "library.json").forEach { doc ->
            filesFile(doc).deleteRecursively()
            filesFile(doc + AtomicWrite.TEMP_SUFFIX).deleteRecursively()
            filesFile(doc + AtomicWrite.CORRUPT_SUFFIX).deleteRecursively()
        }
        listOf("music-playlists", "palettes", "presets", "takes", "milk").forEach { filesFile(it).deleteRecursively() }
    }

    /**
     * Blocks writes to [target] by putting a directory where its temp copy
     * has to go, so the write cannot even start. The stand-in for "the disk
     * said no" - and the point is that the target is then left alone.
     */
    private fun blockWritesTo(target: File) {
        assertTrue(File(target.absolutePath + AtomicWrite.TEMP_SUFFIX).mkdirs())
    }

    // ---------------------------------------------------------------- history

    @Test
    fun `an interrupted history write leaves the previous history intact`() {
        val store = HistoryStore(ctx)
        store.recordPlay("a", "A", "Artist")
        store.addListenTime("a", 45_000L)
        store.flush()
        store.awaitWrites()
        val good = filesFile("history.json").readText()

        // What process death mid-write leaves behind now: a half-written temp
        // file, never a truncated history.json.
        filesFile("history.json" + AtomicWrite.TEMP_SUFFIX).writeText("""{"tracks":[{"uri":"a""")

        assertEquals(good, filesFile("history.json").readText())
        assertEquals(45_000L, HistoryStore(ctx).entryFor("a")?.listenedMs)

        // And the next write recovers, stale temp file and all.
        HistoryStore(ctx).apply {
            recordPlay("b", "B")
            awaitWrites()
        }
        assertEquals(2, HistoryStore(ctx).recentlyPlayed().size)
    }

    @Test
    fun `a damaged history file is quarantined rather than written over`() {
        HistoryStore(ctx).apply {
            recordPlay("a", "A", "Artist")
            awaitWrites()
        }
        val damaged = filesFile("history.json").readText().dropLast(20)
        filesFile("history.json").writeText(damaged)

        // The store recovers rather than refusing to record anything ever
        // again, but the bytes it could not read are kept, not overwritten.
        val store = HistoryStore(ctx)
        assertNull(store.entryFor("a"))
        store.recordPlay("b", "B")
        store.awaitWrites()

        assertEquals(damaged, filesFile("history.json" + AtomicWrite.CORRUPT_SUFFIX).readText())
        assertEquals(listOf("b"), HistoryStore(ctx).recentlyPlayed().map { it.uri })
    }

    @Test
    fun `a history file that cannot be read at all is never written over`() {
        HistoryStore(ctx).apply {
            recordPlay("a", "A", "Artist")
            awaitWrites()
        }
        val good = filesFile("history.json").readText()
        // A directory in place of the file is the cheap stand-in for "the
        // read failed": the store must record in memory and touch nothing.
        filesFile("history.json").delete()
        assertTrue(filesFile("history.json").mkdir())

        val store = HistoryStore(ctx)
        store.recordPlay("b", "B")
        store.awaitWrites()

        assertEquals(1, store.recentlyPlayed().size)
        assertTrue(filesFile("history.json").isDirectory)
        assertFalse(filesFile("history.json" + AtomicWrite.CORRUPT_SUFFIX).exists())

        // Nothing was lost: the real history is still exactly what it was.
        filesFile("history.json").delete()
        filesFile("history.json").writeText(good)
        assertEquals(listOf("a"), HistoryStore(ctx).recentlyPlayed().map { it.uri })
    }

    // ---------------------------------------------------------------- presets

    private fun preset(
        name: String,
        attack: Float = 0.5f,
    ) = Preset(name, "fluid", attack, 0.2f)

    @Test
    fun `presets with distinct cjk names coexist instead of sharing one file`() {
        // The old sanitizer collapsed both names to "__.json", so saving the
        // second silently destroyed the first.
        val store = PresetStore(ctx)
        store.save(preset("夜曲", attack = 0.1f))
        store.save(preset("月光", attack = 0.9f))

        val back = PresetStore(ctx).list()
        assertEquals(setOf("夜曲", "月光"), back.map { it.name }.toSet())
        assertEquals(0.1f, back.single { it.name == "夜曲" }.attack, 1e-4f)

        // Deleting one must find its own file and leave the other's alone.
        store.delete("夜曲")
        assertEquals(listOf("月光"), PresetStore(ctx).list().map { it.name })
    }

    @Test
    fun `an interrupted preset save leaves the previous preset intact`() {
        val store = PresetStore(ctx)
        store.save(preset("Neon", attack = 0.1f))
        blockWritesTo(filesFile("presets/Neon.json"))

        // Re-saving a name deliberately REPLACES the preset, so a truncating
        // write here used to sit on top of the only copy of it.
        store.save(preset("Neon", attack = 0.9f))

        assertEquals(0.1f, PresetStore(ctx).list().single().attack, 1e-4f)
    }

    @Test
    fun `a preset saved under the old sanitizer is migrated to its hashed name`() {
        filesFile("presets").mkdirs()
        filesFile("milk").mkdirs()
        // What the pre-hash sanitizer left on disk for "夜曲".
        filesFile("presets/__.json").writeText(PresetStore.toJson(preset("夜曲", attack = 0.3f)))
        filesFile("milk/__.milk").writeText("MILKDROP_PRESET_VERSION=201")

        val store = PresetStore(ctx)

        assertFalse(filesFile("presets/__.json").exists())
        assertEquals(0.3f, PresetStore.fromJson(store.fileOf("夜曲")!!.readText()).attack, 1e-4f)
        // The paired .milk moved with it: presets saved before sources were
        // carried resolve their visual through this name.
        assertEquals("MILKDROP_PRESET_VERSION=201", filesFile("milk/${PresetStore.milkFileName("夜曲")}").readText())

        // The name that used to overwrite it now saves alongside it.
        store.save(preset("月光", attack = 0.8f))
        assertEquals(setOf("夜曲", "月光"), store.list().map { it.name }.toSet())
    }

    @Test
    fun `migration never renames an old file over an existing one`() {
        val store = PresetStore(ctx)
        store.save(preset("夜曲", attack = 0.9f))
        // A stale old-scheme file for the same name reappears (say from a
        // restored backup): it must not replace the current save.
        filesFile("presets/__.json").writeText(PresetStore.toJson(preset("夜曲", attack = 0.1f)))

        val migrated = PresetStore(ctx)

        assertTrue(filesFile("presets/__.json").exists())
        assertEquals(0.9f, PresetStore.fromJson(migrated.fileOf("夜曲")!!.readText()).attack, 1e-4f)
    }

    // -------------------------------------------------------------- playlists

    @Test
    fun `a damaged playlist is not replaced by the track being added to it`() {
        val store = MusicPlaylistStore(ctx)
        store.save(MusicPlaylist("Set", listOf("a", "b", "c")))
        val f = filesFile("music-playlists/Set.json")
        val damaged = f.readText().dropLast(10)
        f.writeText(damaged)

        // list() skips a file it cannot parse, so every mutator would
        // otherwise start from an empty playlist and save one track over
        // three.
        store.addTrack("Set", "d")
        store.removeTrack("Set", "a")
        store.move("Set", 0, 2)

        assertEquals(damaged, f.readText())
    }

    @Test
    fun `an interrupted playlist write leaves the previous track order`() {
        val store = MusicPlaylistStore(ctx)
        store.save(MusicPlaylist("Set", listOf("a", "b", "c")))
        val f = filesFile("music-playlists/Set.json")
        blockWritesTo(f)

        store.move("Set", 0, 2)

        assertEquals(listOf("a", "b", "c"), store.list().single().trackUris)
        // The blocked temp path is not a playlist, and must never be listed
        // as one.
        assertEquals(listOf("Set"), store.list().map { it.name })
    }

    @Test
    fun `a rename that cannot write the new file keeps the old one`() {
        val store = MusicPlaylistStore(ctx)
        store.save(MusicPlaylist("Set", listOf("a", "b")))
        blockWritesTo(filesFile("music-playlists/Live.json"))

        assertFalse(store.rename("Set", "Live"))

        // The old file is only removed once the new one is whole; deleting it
        // after a failed write is how a rename loses a playlist.
        assertEquals(listOf("a", "b"), store.list().single { it.name == "Set" }.trackUris)
    }

    @Test
    fun `playlists with distinct emoji names coexist instead of sharing one file`() {
        val store = MusicPlaylistStore(ctx)
        store.save(MusicPlaylist("🔥 Mix", listOf("a")))
        store.save(MusicPlaylist("💜 Mix", listOf("b")))

        val back = MusicPlaylistStore(ctx).list()
        assertEquals(setOf("🔥 Mix", "💜 Mix"), back.map { it.name }.toSet())
        assertEquals(listOf("a"), back.single { it.name == "🔥 Mix" }.trackUris)

        store.delete("🔥 Mix")
        assertEquals(listOf("💜 Mix"), MusicPlaylistStore(ctx).list().map { it.name })
    }

    @Test
    fun `a playlist saved under the old sanitizer is migrated and reachable`() {
        filesFile("music-playlists").mkdirs()
        // What the pre-hash sanitizer left on disk for "🔥 Mix".
        filesFile("music-playlists/__ Mix.json").writeText("""{"name":"🔥 Mix","tracks":["a","b"]}""")

        val store = MusicPlaylistStore(ctx)

        assertFalse(filesFile("music-playlists/__ Mix.json").exists())
        // Without the rename, fileOf would miss the old stem and addTrack
        // would start from a fresh empty playlist.
        assertEquals(listOf("a", "b", "c"), store.addTrack("🔥 Mix", "c").trackUris)
    }

    // --------------------------------------------------------------- palettes

    @Test
    fun `an interrupted palette write leaves the previous gradient`() {
        val store = PaletteStore(ctx)
        val saved = store.save(PaletteStore.create("Sunrise Fade", 0.08f, 0.42f))
        blockWritesTo(filesFile("palettes/${saved.id}.json"))

        // Saving the same name deliberately REPLACES the palette, so the
        // truncation window used to sit on top of the only copy of it.
        store.save(PaletteStore.create("Sunrise Fade", 0.90f, 0.05f))

        val back = PaletteStore(ctx).get(saved.id)!!
        assertEquals(0.08f, back.baseHue, 1e-4f)
        assertEquals(0.42f, back.hueSpan, 1e-4f)
    }

    @Test
    fun `an in-progress palette copy is never listed as a saved palette`() {
        val store = PaletteStore(ctx)
        store.save(PaletteStore.create("Dusk", 0.6f, 0.3f))
        // A temp file left by an earlier crash, holding a whole document.
        filesFile("palettes/Dusk.json" + AtomicWrite.TEMP_SUFFIX)
            .writeText("""{"id":"Dusk","name":"Dusk","baseHue":0.9,"hueSpan":0.1}""")

        assertEquals(listOf("Dusk"), PaletteStore(ctx).list().map { it.name })
        assertEquals(0.6f, PaletteStore(ctx).get("Dusk")!!.baseHue, 1e-4f)
    }

    @Test
    fun `palettes with distinct cjk names coexist under distinct ids`() {
        val store = PaletteStore(ctx)
        val night = store.save(PaletteStore.create("夜曲", 0.1f, 0.2f))
        val moon = store.save(PaletteStore.create("月光", 0.7f, 0.8f))

        assertNotEquals(night.id, moon.id)
        val back = PaletteStore(ctx)
        assertEquals(0.1f, back.get(night.id)!!.baseHue, 1e-4f)
        assertEquals(0.7f, back.get(moon.id)!!.baseHue, 1e-4f)
    }

    @Test
    fun `a palette saved under the old sanitizer is re-keyed to its hashed id`() {
        filesFile("palettes").mkdirs()
        // What the pre-hash sanitizer left on disk for "夜曲": the id is the
        // file stem AND lives inside the JSON, so migration must move both.
        filesFile("palettes/__.json").writeText("""{"id":"__","name":"夜曲","baseHue":0.25,"hueSpan":0.5}""")

        val store = PaletteStore(ctx)

        assertFalse(filesFile("palettes/__.json").exists())
        val migrated = store.get(PaletteStore.idFor("夜曲"))!!
        assertEquals("夜曲", migrated.name)
        assertEquals(0.25f, migrated.baseHue, 1e-4f)

        // Re-keyed for real: saving the same name replaces the migrated file
        // instead of forking a second palette.
        store.save(PaletteStore.create("夜曲", 0.9f, 0.1f))
        assertEquals(1, store.list().size)
        assertEquals(0.9f, store.get(PaletteStore.idFor("夜曲"))!!.baseHue, 1e-4f)
    }

    // ---------------------------------------------------------------- library

    @Test
    fun `a blocked library write leaves the previous library on disk`() {
        val store = TrackLibrary(ctx)
        store.addAll(listOf(LibraryTrack(uri = "a", title = "A"), LibraryTrack(uri = "b", title = "B")))
        blockWritesTo(filesFile("library.json"))

        store.addAll(listOf(LibraryTrack(uri = "c", title = "C")))

        // The write could not complete, so the previous two tracks are
        // exactly where they were - not a truncated library.json.
        assertEquals(setOf("a", "b"), TrackLibrary(ctx).list().map { it.uri }.toSet())
    }

    // ------------------------------------------------------------------ takes

    private fun takeJson(
        name: String,
        durationMs: Long,
    ) = """{"name":"$name","trackUri":"content://media/9","durationMs":$durationMs,"events":[]}"""

    @Test
    fun `an in-progress take is never listed and never collides with a saved one`() {
        val store = TakeStore(ctx)
        store.save("Take 1", takeJson("Take 1", 12_345L))
        filesFile("takes/Take 1.json" + AtomicWrite.TEMP_SUFFIX).writeText(takeJson("Take 1", 999L))

        assertEquals(listOf("Take 1"), store.list().map { it.name })
        assertEquals(12_345L, store.list().single().durationMs)
        // The collision check reads the real names, so a leftover temp copy
        // must not push the next take onto "Take 1 2" either.
        assertEquals("Take 2", store.save("Take 2", takeJson("Take 2", 1L)))
    }

    @Test
    fun `a take rename that cannot write the new file keeps the performance`() {
        val store = TakeStore(ctx)
        store.save("Take 1", takeJson("Take 1", 12_345L))
        blockWritesTo(filesFile("takes/Encore.json"))

        assertFalse(store.rename("Take 1", "Encore"))

        // A take is a performance that cannot be repeated: writing the
        // destination and deleting the source unconditionally is the one way
        // this method can destroy one.
        assertEquals(listOf("Take 1"), store.list().map { it.name })
        assertEquals(12_345L, store.load("Take 1")!!.durationMs)
    }

    // --------------------------------------------------------------- textures

    private fun registerImage(
        uri: Uri,
        stream: InputStream,
    ) = Shadows.shadowOf(ctx.contentResolver).registerInputStream(uri, stream)

    /** A real (decodable) PNG: import validates content now, not just extension. */
    private fun pngBytes(argb: Int): ByteArray {
        val bmp = android.graphics.Bitmap.createBitmap(4, 4, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(argb)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test
    fun `an import that dies part-way leaves the previous texture usable`() {
        val store = TextureStore(ctx)
        val uri = Uri.parse("content://test/logo.png")
        val good = pngBytes(0xFF3366CC.toInt())
        registerImage(uri, ByteArrayInputStream(good))
        assertEquals(listOf("logo.png"), store.import(listOf(uri)).map { it.name })

        // Re-importing over a texture a preset is already using, from a
        // provider that gives up half way. projectM answers a texture it
        // cannot decode with noise or black, which is the whole failure this
        // store exists to prevent.
        registerImage(
            uri,
            object : InputStream() {
                private var sent = 0

                override fun read(): Int = throw IOException("provider went away")

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (sent > 0) throw IOException("provider went away")
                    sent = minOf(len, 512)
                    b.fill(0x7f.toByte(), off, off + sent)
                    return sent
                }
            },
        )
        store.import(listOf(uri))

        val kept = filesFile("milk/textures/logo.png")
        assertTrue(good.contentEquals(kept.readBytes()))
        assertEquals(listOf("logo.png"), store.list().map { it.name })
    }

    @Test
    fun `an in-progress texture copy is never listed as a texture`() {
        val store = TextureStore(ctx)
        filesFile("milk/textures").mkdirs()
        filesFile("milk/textures/logo.png" + AtomicWrite.TEMP_SUFFIX).writeBytes(ByteArray(32))

        assertEquals(emptyList<String>(), store.list().map { it.name })
    }
}
