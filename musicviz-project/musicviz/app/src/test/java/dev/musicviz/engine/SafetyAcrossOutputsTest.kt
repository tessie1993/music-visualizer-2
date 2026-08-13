package dev.musicviz.engine

import dev.musicviz.render.VisualSafety
import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Slice 0.3b. Part 1 fixed *what* the choice resolves to; this pins that every
 * path which can put light on a screen actually honours it.
 *
 * The plan's exit gate is behavioural — "a fresh install and an upgraded
 * install with no v2 choice cannot reach a 9 Hz full-screen strobe" — so these
 * drive the real clamp with the real randomizer, rather than asserting that
 * some flag is set somewhere.
 */
class SafetyAcrossOutputsTest {
    private val pendingChoice = SafetyChoice.resolve(storedVersion = null, storedSafeVisuals = false)

    /** The config an un-asked user's session actually runs with. */
    private fun configForPendingChoice() =
        VisualSafety.SafetyConfig(
            enabled = pendingChoice.safeVisuals,
            maxFlashHz = VisualSafety.WCAG_FLASHES_PER_SECOND,
            maxFlashDepth = 0.25f,
            allowInversion = false,
            reducedMotion = false,
        )

    @Test
    fun `an un-asked user's session is not neutral`() {
        // If this config were neutral, VisualSafety.apply returns the input
        // untouched and every assertion below would pass vacuously.
        assertFalse(
            "the pending-choice config must actually clamp",
            configForPendingChoice().isNeutral,
        )
    }

    @Test
    fun `no randomize roll can reach an unsafe strobe while the choice is pending`() {
        // The plan calls this out by name: ParamRandomizer can roll strobe and
        // beat flash, and the randomize button is one tap away. The clamp runs
        // last, on final params, so the guarantee should hold for every seed.
        val config = configForPendingChoice()
        val ceiling = config.maxFlashDepth
        repeat(500) { seed ->
            val rolled = ParamRandomizer.randomize(SceneParams.DEFAULT, emptySet(), Random(seed))
            val safe = VisualSafety.apply(rolled, config)
            assertTrue(
                "seed $seed produced strobe ${safe.strobe} above the depth budget",
                safe.strobe * VisualSafety.STROBE_SHADER_DEPTH <= ceiling + 1e-4f,
            )
            assertTrue(
                "seed $seed produced flash ${safe.flash} above the depth budget",
                safe.flash * VisualSafety.FLASH_SHADER_DEPTH <= ceiling + 1e-4f,
            )
        }
    }

    @Test
    fun `an explicit opt-out is the only way to reach an unclamped frame`() {
        val optedOut = SafetyChoice.resolve(SafetyChoice.CURRENT_VERSION, storedSafeVisuals = false)
        assertFalse(optedOut.safeVisuals)
        assertTrue(optedOut.policy is SafetyPolicy.UnrestrictedByUserChoice)

        // And the two states that look the same on disk do not behave the same.
        val unasked = SafetyChoice.resolve(null, storedSafeVisuals = false)
        assertTrue(unasked.safeVisuals)
    }

    @Test
    fun `the exporter has no unsafe default for its safety argument`() {
        // VideoExporter.export used to default `safety` to SafetyConfig.OFF, so
        // a caller that simply forgot the argument would export an unclamped
        // video. Requiring it makes omission a compile error instead.
        val src = sourceOf("app/src/main/java/dev/musicviz/export/VideoExporter.kt")
        assertFalse(
            "VideoExporter must not default its safety argument to OFF",
            Regex("""safety:\s*[\w.]*VisualSafety\.SafetyConfig\s*=""").containsMatchIn(src),
        )
    }

    @Test
    fun `the wallpaper derives its safety from the stored choice`() {
        // The wallpaper is an independent output with no Activity, so it is the
        // easiest place for the choice to be missed.
        val src = sourceOf("app/src/main/java/dev/musicviz/wallpaper/VisualizerWallpaperService.kt")
        assertTrue(
            "the wallpaper must read safety from ThemeStore.loadGui(), not construct its own",
            "loadGui().safety" in src,
        )
        assertFalse(
            "the wallpaper must not hard-code SafetyConfig.OFF",
            "SafetyConfig.OFF" in src,
        )
    }

    private fun sourceOf(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            val nested = File(dir, "musicviz-project/musicviz/$relative")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile
        }
        error("$relative not found from ${File("").absolutePath}")
    }
}
