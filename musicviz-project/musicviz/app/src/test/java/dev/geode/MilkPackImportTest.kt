package dev.geode

import dev.geode.data.MilkPackImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The MegaPack door: one folder pick imports every preset and texture, skips
 * what the user already owns, and says up front which presets will render
 * without their textures instead of letting each be discovered broken.
 */
class MilkPackImportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(
        name: String,
        content: String = "MILKDROP_PRESET_VERSION=201\n[preset00]\nfRating=2.0\n",
    ) = MilkPackImporter.Entry(name) { content.byteInputStream() }

    private fun milkDir(): File = tmp.newFolder("milk")

    @Test
    fun `presets and textures land in their own homes`() {
        val dir = milkDir()
        val report =
            MilkPackImporter.import(
                listOf(
                    entry("martin - arcane cathedral.milk"),
                    entry("flexi - kaleidoscope.milk"),
                    entry("headlights.jpg", content = "jpegbytes"),
                    entry("README.txt", content = "not a preset"),
                ),
                dir,
            )
        assertEquals(2, report.presets)
        assertEquals(1, report.textures)
        assertEquals(0, report.skipped)
        assertEquals(2, dir.listFiles { f -> f.extension == "milk" }.orEmpty().size)
        assertTrue(File(dir, "textures/headlights.jpg").isFile)
        assertFalse("a stray text file was imported", dir.walkTopDown().any { it.name == "README.txt" })
    }

    @Test
    fun `a file the user already has is never overwritten`() {
        val dir = milkDir()
        MilkPackImporter.import(listOf(entry("mine.milk", content = "ORIGINAL")), dir)
        val theirs = dir.listFiles { f -> f.extension == "milk" }.orEmpty().single()
        val edited = theirs.readText() + "\nper_frame_1=zoom=1.01;\n"
        theirs.writeText(edited)
        val report = MilkPackImporter.import(listOf(entry("mine.milk", content = "REPLACEMENT")), dir)
        assertEquals(0, report.presets)
        assertEquals(1, report.skipped)
        assertEquals("the user's edit was overwritten", edited, theirs.readText())
    }

    @Test
    fun `an unreadable entry is counted as skipped, not as success`() {
        val dir = milkDir()
        val report =
            MilkPackImporter.import(
                listOf(MilkPackImporter.Entry("ghost.milk") { null }, entry("real.milk")),
                dir,
            )
        assertEquals(1, report.presets)
        assertEquals(1, report.skipped)
    }

    @Test
    fun `a preset referencing an absent texture is called out`() {
        val dir = milkDir()
        val needy =
            "MILKDROP_PRESET_VERSION=201\n[preset00]\n" +
                "warp_1=`shader_body { ret = tex2D(sampler_headlights, uv).rgb; }`\n"
        val report =
            MilkPackImporter.import(
                listOf(entry("needy.milk", content = needy), entry("fine.milk")),
                dir,
            )
        assertEquals(1, report.presetsMissingTextures)
    }

    @Test
    fun `a texture arriving in the same import satisfies the reference`() {
        val dir = milkDir()
        val needy =
            "MILKDROP_PRESET_VERSION=201\n[preset00]\n" +
                "warp_1=`ret = tex2D(sampler_Headlights, uv).rgb;`\n"
        val report =
            MilkPackImporter.import(
                listOf(entry("needy.milk", content = needy), entry("headlights.JPG", content = "img")),
                dir,
            )
        assertEquals("case must not matter - packs are authored on Windows", 0, report.presetsMissingTextures)
    }

    @Test
    fun `builtin samplers and noise never count as missing`() {
        val dir = milkDir()
        val preset =
            "MILKDROP_PRESET_VERSION=201\n[preset00]\n" +
                "comp_1=`ret = tex2D(sampler_main, uv).rgb + tex2D(sampler_fc_noise_lq, uv2).rgb " +
                "+ tex2D(sampler_blur1, uv).rgb + tex2D(sampler_pw_noisevol_hq, uv3).rgb;`\n"
        val report = MilkPackImporter.import(listOf(entry("clean.milk", content = preset)), dir)
        assertEquals(0, report.presetsMissingTextures)
    }

    @Test
    fun `wrap and filter prefixes still resolve to the texture name`() {
        val dir = milkDir()
        val preset =
            "MILKDROP_PRESET_VERSION=201\n[preset00]\n" +
                "warp_1=`ret = tex2D(sampler_fw_stone, uv).rgb;`\n"
        val withTexture =
            MilkPackImporter.import(
                listOf(entry("a.milk", content = preset), entry("stone.png", content = "img")),
                dir,
            )
        assertEquals(0, withTexture.presetsMissingTextures)
        val without = MilkPackImporter.import(listOf(entry("b.milk", content = preset)), tmp.newFolder("milk2"))
        assertEquals(1, without.presetsMissingTextures)
    }
}
