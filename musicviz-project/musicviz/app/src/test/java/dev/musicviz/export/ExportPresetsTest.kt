package dev.musicviz.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The named render targets, and the rules that keep them worth having.
 *
 * A preset is only useful if it is a different answer from every other preset,
 * if it is reachable through the controls it stands in for, and if it does not
 * quietly select a combination the encoder cannot deliver. Those are the three
 * things tested here - the numbers themselves are a product decision and are
 * asserted only where getting them wrong would be silent, as with a vertical
 * target whose ratio is accidentally landscape.
 */
class ExportPresetsTest {
    @Test
    fun `every preset is a different answer from every other`() {
        // Two chips with the same four values are two buttons that do the same
        // thing, and the second can never be the one that matches.
        val specs = ExportPresets.ALL.map { listOf(it.quality, it.ratio, it.fps, it.loopSafe) }
        assertEquals("two presets carry identical settings", specs.size, specs.distinct().size)
    }

    @Test
    fun `every preset has a distinct name`() {
        val names = ExportPresets.ALL.map { it.name }
        assertEquals("two presets share a name", names.size, names.distinct().size)
    }

    @Test
    fun `every preset asks for a frame rate the controls can show`() {
        // The frame-rate control is two options, so a preset outside them would
        // leave that row showing neither and unable to get back.
        val wrong = ExportPresets.ALL.filterNot { it.fps == 30 || it.fps == 60 }
        assertEquals("presets must use 30 or 60 fps", emptyList<ExportPreset>(), wrong)
    }

    @Test
    fun `no preset picks the ultrawide 4K the encoder cannot do`() {
        // 21:9 at 4K exceeds the AVC level's 4096-pixel long side. Until #70
        // lifts that, a preset offering it would be a chip that always fails.
        val impossible =
            ExportPresets.ALL.filter {
                it.ratio == ExportRatio.R21_9 && it.quality == ExportQuality.UHD4K
            }
        assertEquals("21:9 4K is not encodable yet", emptyList<ExportPreset>(), impossible)
    }

    @Test
    fun `a vertical target is actually taller than it is wide`() {
        // The one way to mis-key this table that no test of the numbers would
        // catch: swapping the ratio on the short-form entries.
        for (name in listOf("TikTok", "Shorts")) {
            val preset = ExportPresets.ALL.single { it.name == name }
            assertTrue("$name is not vertical: ${preset.ratio.label}", preset.ratio.hRatio > preset.ratio.wRatio)
        }
    }

    @Test
    fun `short-form targets are loop-safe and long-form ones are not`() {
        // The reason loopSafe is in the preset at all: the platforms that
        // autoplay a clip on repeat are exactly the vertical and square ones.
        for (preset in ExportPresets.ALL) {
            val short = preset.ratio.hRatio >= preset.ratio.wRatio
            assertEquals("${preset.name} has the wrong loop-safe default", short, preset.loopSafe)
        }
    }

    @Test
    fun `each preset matches itself`() {
        // What the chip row reads to know which chip is lit.
        for ((index, preset) in ExportPresets.ALL.withIndex()) {
            assertEquals(
                "${preset.name} does not match its own settings",
                index,
                ExportPresets.indexMatching(preset.quality, preset.ratio, preset.fps, preset.loopSafe),
            )
        }
    }

    @Test
    fun `hand-tuned settings match no preset`() {
        // 4:3 at 720p is not any platform's target, and the row has to be able
        // to say so rather than lighting whichever chip is closest.
        assertNull(ExportPresets.matching(ExportQuality.HD720, ExportRatio.R4_3, 30, false))
        assertEquals(-1, ExportPresets.indexMatching(ExportQuality.HD720, ExportRatio.R4_3, 30, false))
    }

    @Test
    fun `loop-safe alone decides between two otherwise identical targets`() {
        // Turning the loop-safe switch off under a lit vertical chip has to
        // unlight it, or the row claims a setting the render will not use.
        val tikTok = ExportPresets.ALL.single { it.name == "TikTok" }
        assertNotNull(ExportPresets.matching(tikTok.quality, tikTok.ratio, tikTok.fps, true))
        assertNull(ExportPresets.matching(tikTok.quality, tikTok.ratio, tikTok.fps, false))
    }

    @Test
    fun `the table offers every shape the ratio control does except the unused ones`() {
        // Not a completeness demand - just a record of which shapes have a named
        // target, so adding a ratio does not silently leave it unreachable.
        assertEquals(
            setOf(ExportRatio.R9_16, ExportRatio.R4_5, ExportRatio.R1_1, ExportRatio.R16_9),
            ExportPresets.ALL.map { it.ratio }.toSet(),
        )
    }
}
