package dev.musicviz

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.ui.TakeStore
import dev.musicviz.ui.TextureStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Non-ASCII names in [TakeStore] and [TextureStore], against real files in
 * filesDir.
 *
 * Both stores kept the collapse-to-underscore sanitizer after presets,
 * playlists and palettes moved to the hashed scheme, so "夜曲" and "月光"
 * still shared one file here: the second take's load/delete resolved to the
 * first take's file, and importing the second texture silently replaced the
 * first. These cases pin coexistence under the shared scheme, the take
 * migration off the old stems, and - because a texture's file name is the
 * name presets reference - that already-imported textures are left exactly
 * where they are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MusicVizApp::class)
class TakeTextureNameMigrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun filesFile(name: String) = File(ctx.filesDir, name)

    @Before
    fun cleanFilesDir() {
        listOf("takes", "milk").forEach { filesFile(it).deleteRecursively() }
    }

    private fun takeJson(
        name: String,
        durationMs: Long,
    ) = """{"name":"$name","trackUri":"content://media/9","durationMs":$durationMs,"events":[]}"""

    // ------------------------------------------------------------------ takes

    @Test
    fun `takes with distinct cjk names coexist instead of sharing one file`() {
        val store = TakeStore(ctx)
        assertEquals("夜曲", store.save("夜曲", takeJson("夜曲", 111L)))
        assertEquals("月光", store.save("月光", takeJson("月光", 222L)))

        assertEquals(setOf("夜曲", "月光"), TakeStore(ctx).list().map { it.name }.toSet())
        // Loading resolves each name to its OWN file: the old sanitizer sent
        // both to the first one, replaying the wrong performance.
        assertEquals(111L, store.load("夜曲")!!.durationMs)
        assertEquals(222L, store.load("月光")!!.durationMs)

        store.delete("夜曲")
        assertEquals(listOf("月光"), TakeStore(ctx).list().map { it.name })
        assertEquals(222L, TakeStore(ctx).load("月光")!!.durationMs)
    }

    @Test
    fun `takes with distinct emoji names coexist and rename cleanly`() {
        val store = TakeStore(ctx)
        store.save("🔥 Set", takeJson("🔥 Set", 1L))
        store.save("💜 Set", takeJson("💜 Set", 2L))

        assertTrue(store.rename("🔥 Set", "Encore"))
        assertEquals(setOf("Encore", "💜 Set"), TakeStore(ctx).list().map { it.name }.toSet())
        assertEquals(1L, store.load("Encore")!!.durationMs)
        assertEquals(2L, store.load("💜 Set")!!.durationMs)
    }

    @Test
    fun `a take saved under the old sanitizer is migrated to its hashed name`() {
        filesFile("takes").mkdirs()
        // What the pre-hash sanitizer left on disk for "夜曲".
        filesFile("takes/__.json").writeText(takeJson("夜曲", 333L))

        val store = TakeStore(ctx)

        assertFalse(filesFile("takes/__.json").exists())
        assertEquals(333L, store.load("夜曲")!!.durationMs)

        // The name that used to land on the same file now saves alongside it.
        store.save("月光", takeJson("月光", 444L))
        assertEquals(setOf("夜曲", "月光"), store.list().map { it.name }.toSet())
        assertEquals(333L, store.load("夜曲")!!.durationMs)
    }

    @Test
    fun `take migration never renames an old file over an existing one`() {
        val store = TakeStore(ctx)
        store.save("夜曲", takeJson("夜曲", 999L))
        // A stale old-scheme file for the same name reappears (say from a
        // restored backup): it must not replace the current take.
        filesFile("takes/__.json").writeText(takeJson("夜曲", 1L))

        val migrated = TakeStore(ctx)

        assertTrue(filesFile("takes/__.json").exists())
        assertEquals(999L, migrated.load("夜曲")!!.durationMs)
    }

    @Test
    fun `a collision suffixed take lists and loads under the name it returns`() {
        val store = TakeStore(ctx)
        store.save("Take 1", takeJson("Take 1", 1L))

        // The document's name must follow the suffix, or this take would list
        // as a second "Take 1" whose load resolves to the first one's file.
        assertEquals("Take 1 2", store.save("Take 1", takeJson("Take 1", 2L)))
        assertEquals(setOf("Take 1", "Take 1 2"), store.list().map { it.name }.toSet())
        assertEquals(1L, store.load("Take 1")!!.durationMs)
        assertEquals(2L, store.load("Take 1 2")!!.durationMs)
    }

    // --------------------------------------------------------------- textures

    private fun registerImage(
        uri: Uri,
        bytes: ByteArray,
    ) = Shadows.shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))

    @Test
    fun `textures with distinct cjk names coexist instead of one replacing the other`() {
        val store = TextureStore(ctx)
        val night = Uri.parse("content://test/夜曲.png")
        val moon = Uri.parse("content://test/月光.png")
        registerImage(night, ByteArray(64) { 1 })
        registerImage(moon, ByteArray(64) { 2 })

        store.import(listOf(night))
        val listed = store.import(listOf(moon))

        // Two files, both whole: the old sanitizer sent both to "__.png", so
        // the second import silently replaced the first.
        assertEquals(2, listed.size)
        val names = listed.map { it.name }
        assertNotEquals(names[0], names[1])
        val bytes = listed.map { File(it.path).readBytes().toList() }.toSet()
        assertEquals(setOf(List(64) { 1.toByte() }, List(64) { 2.toByte() }), bytes)

        // Removal round-trips through the listed name.
        assertEquals(1, store.remove(names[0]).size)
    }

    @Test
    fun `an already imported texture keeps its exact name on re-import`() {
        // The file name is the name presets reference (sampler_<basename>),
        // so an ASCII-safe name must stay byte-for-byte stable - and nothing
        // on disk is ever renamed by a migration for the same reason.
        val store = TextureStore(ctx)
        val uri = Uri.parse("content://test/logo.png")
        registerImage(uri, ByteArray(16) { 3 })
        assertEquals(listOf("logo.png"), store.import(listOf(uri)).map { it.name })

        registerImage(uri, ByteArray(16) { 4 })
        assertEquals(listOf("logo.png"), store.import(listOf(uri)).map { it.name })
        assertEquals(List(16) { 4.toByte() }, filesFile("milk/textures/logo.png").readBytes().toList())
    }

    @Test
    fun `a hashed texture name still yields a usable display preset`() {
        val store = TextureStore(ctx)
        val uri = Uri.parse("content://test/夜曲.png")
        registerImage(uri, ByteArray(16) { 5 })
        val name = store.import(listOf(uri)).single().name

        val preset = File(store.generateDisplayPreset(name)).readText()
        // The generated shader must reference the hashed base as a legal
        // identifier: sampler_ followed by [A-Za-z0-9_] only.
        val base = name.removeSuffix(".png")
        assertTrue(base, Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(base))
        assertTrue(preset, "sampler sampler_$base;" in preset)
    }
}
