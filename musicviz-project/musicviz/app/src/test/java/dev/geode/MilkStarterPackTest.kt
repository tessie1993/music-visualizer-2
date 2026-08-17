package dev.geode

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.geode.render.scene.MilkStarterPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The presets the MilkDrop tab is no longer empty because of.
 *
 * Two separate risks, so two kinds of test. The install behaviour is checked
 * against a real (Robolectric) asset manager and file system: it must run once,
 * never overwrite a file the user owns, and never resurrect one they deleted.
 * The preset CONTENT is checked as text, because the only thing that can be
 * asserted off a device is that the files are well-formed MilkDrop and that
 * they actually read the audio — projectM is arm64-only, so no emulator in CI
 * can render them either.
 *
 * That second point is the honest limit of this file: it proves the presets
 * parse and are reactive, not that they look good.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MilkStarterPackTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun freshTarget(name: String): File =
        File(app.cacheDir, "milk-test-$name").apply {
            deleteRecursively()
            mkdirs()
        }

    private fun packSources(): List<Pair<String, String>> =
        app.assets
            .list("milk")
            .orEmpty()
            .filter { it.endsWith(".milk") }
            .map { name -> name to app.assets.open("milk/$name").use { it.readBytes().decodeToString() } }

    @Test
    fun `the pack ships presets`() {
        val names = app.assets.list("milk").orEmpty().filter { it.endsWith(".milk") }
        assertTrue("the MilkDrop tab is empty again: $names", names.size >= 6)
    }

    @Test
    fun `installing writes every preset into the user directory`() {
        val target = freshTarget("install")
        val written = MilkStarterPack.install(app, target)
        assertEquals(packSources().size, written)
        val onDisk = target.listFiles { f -> f.extension == "milk" }.orEmpty().map { it.name }.sorted()
        assertEquals(packSources().map { it.first }.sorted(), onDisk)
    }

    @Test
    fun `installing twice writes nothing the second time`() {
        val target = freshTarget("twice")
        MilkStarterPack.install(app, target)
        assertEquals("the pack reinstalled itself", 0, MilkStarterPack.install(app, target))
    }

    /**
     * The failure this guards is the one that makes a starter pack hateful: a
     * preset the user deleted coming back on the next launch.
     */
    @Test
    fun `a preset the user deleted stays deleted`() {
        val target = freshTarget("deleted")
        MilkStarterPack.install(app, target)
        val doomed = target.listFiles { f -> f.extension == "milk" }!!.first()
        assertTrue(doomed.delete())
        MilkStarterPack.install(app, target)
        assertFalse("a deleted preset came back", doomed.exists())
    }

    /** A file already there is the user's — an edit, a re-import, or a save. */
    @Test
    fun `an edited preset is never overwritten`() {
        val target = freshTarget("edited")
        val name = packSources().first().first
        val mine = File(target, name).apply { writeText("MILKDROP_PRESET_VERSION=201\n[preset00]\nfRating=1.000\n") }
        MilkStarterPack.install(app, target)
        assertTrue("the user's own file was replaced", mine.readText().contains("fRating=1.000"))
    }

    @Test
    fun `every preset is well-formed milkdrop`() {
        for ((name, text) in packSources()) {
            assertTrue("$name has no version header", text.startsWith("MILKDROP_PRESET_VERSION="))
            assertTrue("$name has no [preset00] section", "[preset00]" in text)
            for (key in listOf("fDecay=", "fGammaAdj=", "nWaveMode=", "zoom=", "rot=", "warp=")) {
                assertTrue("$name is missing $key", key in text)
            }
            // Every line past the header is key=value; a stray line is a typo
            // that projectM would report as a parse error on the device.
            val malformed =
                text
                    .lineSequence()
                    .filter { it.isNotBlank() && !it.startsWith("[") }
                    .filterNot { it.contains('=') }
                    .toList()
            assertEquals("$name has lines that are not key=value", emptyList<String>(), malformed)
        }
    }

    /**
     * The point of the whole app: a preset that ignores the music is a
     * screensaver. Each one has to read the analyser in its per-frame code.
     */
    @Test
    fun `every preset reacts to the audio`() {
        val reactive = Regex("""\b(bass|bass_att|mid|mid_att|treb|treb_att|vol|vol_att)\b""")
        for ((name, text) in packSources()) {
            val perFrame = text.lineSequence().filter { it.startsWith("per_frame_") }.toList()
            assertTrue("$name has no per-frame equations at all", perFrame.isNotEmpty())
            assertTrue(
                "$name never reads the audio — it would render the same on silence",
                perFrame.any { reactive.containsMatchIn(it) },
            )
        }
    }

    /**
     * Decay is the one value that turns a preset into an unwatchable white-out
     * or a black screen, and it is the hardest to notice in review.
     */
    @Test
    fun `decay stays inside the watchable range`() {
        for ((name, text) in packSources()) {
            val decay =
                text
                    .lineSequence()
                    .first { it.startsWith("fDecay=") }
                    .substringAfter('=')
                    .trim()
                    .toFloat()
            assertTrue("$name decays at $decay, which never clears", decay in 0.90f..0.999f)
        }
    }
}
