package dev.musicviz

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.ui.AtomicWrite
import dev.musicviz.ui.HistoryStore
import dev.musicviz.ui.MusicPlaylist
import dev.musicviz.ui.MusicPlaylistStore
import dev.musicviz.ui.PaletteStore
import dev.musicviz.ui.TakeStore
import dev.musicviz.ui.TextureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The five stores that keep a JSON (or image) document they rewrite whole,
 * against real files in filesDir.
 *
 * All five used `File.writeText`, which truncates its target to zero before
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
@Config(sdk = [35], application = MusicVizApp::class)
class StoreDurabilityTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun filesFile(name: String) = File(ctx.filesDir, name)

    @Before
    fun cleanFilesDir() {
        filesFile("history.json").deleteRecursively()
        filesFile("history.json" + AtomicWrite.TEMP_SUFFIX).deleteRecursively()
        filesFile("history.json" + AtomicWrite.CORRUPT_SUFFIX).deleteRecursively()
        listOf("music-playlists", "palettes", "takes", "milk").forEach { filesFile(it).deleteRecursively() }
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
        assertEquals(2, HistoryStore(ctx).stats().trackCount)
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

        assertEquals(1, store.stats().trackCount)
        assertTrue(filesFile("history.json").isDirectory)
        assertFalse(filesFile("history.json" + AtomicWrite.CORRUPT_SUFFIX).exists())

        // Nothing was lost: the real history is still exactly what it was.
        filesFile("history.json").delete()
        filesFile("history.json").writeText(good)
        assertEquals(listOf("a"), HistoryStore(ctx).recentlyPlayed().map { it.uri })
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

    @Test
    fun `an import that dies part-way leaves the previous texture usable`() {
        val store = TextureStore(ctx)
        val uri = Uri.parse("content://test/logo.png")
        val good = ByteArray(4096) { (it % 251).toByte() }
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
