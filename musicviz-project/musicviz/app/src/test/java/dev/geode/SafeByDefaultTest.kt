package dev.geode

import dev.geode.render.LfoTarget
import dev.geode.render.VisualSafety
import dev.geode.render.VisualSafetyChoice
import dev.geode.render.scene.SceneParams
import dev.geode.ui.GuiPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2-0-02 asked for the Strobe and randomizer paths to be tamed under the safe
 * settings. They are — `VisualSafety.apply` runs last, after `LfoEngine` and
 * `AdsrEngine`, on the params the scenes actually get — but nothing proved it
 * end to end, and "the clamp is in the right place" is a claim about a call
 * order that a refactor can quietly break.
 *
 * So this drives the worst parameters anything upstream could produce through
 * the choice a fresh install resolves to. Worst-case rather than a random roll
 * on purpose: `ParamRandomizer` is random, and a test that samples it proves
 * only what it happened to draw.
 */
class SafeByDefaultTest {
    /** Everything a randomizer, an LFO or a preset could push to its limit. */
    private val hostile =
        SceneParams.DEFAULT.copy(
            strobe = 1f,
            flash = 1f,
            glitch = 1f,
            bloom = 1f,
            invert = true,
            solarize = true,
            brightness = 4f,
            intensity = 4f,
            contrast = 4f,
        )

    private val freshInstall = GuiPrefsFixture.safety(VisualSafetyChoice.UNKNOWN)

    private val optedOut = GuiPrefsFixture.safety(VisualSafetyChoice.CUSTOM)

    @Test
    fun `a fresh install bounds every full-frame luminance path`() {
        val out = VisualSafety.apply(hostile, freshInstall)
        val depth = freshInstall.maxFlashDepth
        assertTrue("strobe", out.strobe <= depth / VisualSafety.STROBE_SHADER_DEPTH + 1e-6f)
        assertTrue("flash", out.flash <= depth / VisualSafety.FLASH_SHADER_DEPTH + 1e-6f)
        assertTrue("glitch", out.glitch <= depth + 1e-6f)
        assertTrue("bloom", out.bloom <= depth + 1e-6f)
        assertTrue("brightness", out.brightness <= 1f + depth + 1e-6f)
        assertTrue("intensity", out.intensity <= 1f + depth + 1e-6f)
        assertTrue("contrast", out.contrast <= 1f + depth + 1e-6f)
        assertFalse("invert is the largest contrast reversal there is", out.invert)
        assertFalse("solarize", out.solarize)
    }

    @Test
    fun `the clamp is load-bearing, not a no-op that happens to pass`() {
        // The same input through an explicit opt-out comes back untouched. If
        // this ever started clamping too, the assertion above would pass for
        // the wrong reason and prove nothing about the choice.
        assertEquals(hostile, VisualSafety.apply(hostile, optedOut))
        assertTrue("the safe path must actually change something", VisualSafety.apply(hostile, freshInstall) != hostile)
    }

    @Test
    fun `a fresh install caps the strobe rate, not only its depth`() {
        // Dimming a 9 Hz flicker leaves a 9 Hz flicker. Rate is what the
        // guidance is about.
        assertTrue(VisualSafety.strobeHz(freshInstall) <= VisualSafety.WCAG_FLASHES_PER_SECOND)
        assertEquals(VisualSafety.DEFAULT_STROBE_HZ, VisualSafety.strobeHz(optedOut), 0f)
    }

    @Test
    fun `a fresh install caps a randomized luminance LFO`() {
        // ParamRandomizer can roll an LFO onto BRIGHTNESS, and LfoEngine
        // permits 30 Hz. Clamping the endpoints of a 30 Hz oscillation still
        // leaves a 30 Hz oscillation.
        val capped = VisualSafety.limitLfoRate(30f, LfoTarget.BRIGHTNESS, freshInstall)
        assertTrue("30 Hz on brightness is the hazard, not the amplitude", capped <= VisualSafety.WCAG_FLASHES_PER_SECOND)
        assertEquals(30f, VisualSafety.limitLfoRate(30f, LfoTarget.BRIGHTNESS, optedOut), 0f)
    }

    @Test
    fun `a fresh install floors the gap between beat flashes`() {
        val gui = GuiPrefs(safetyChoice = VisualSafetyChoice.UNKNOWN, beatMinIntervalMs = 200f)
        assertEquals(1000f / VisualSafety.WCAG_FLASHES_PER_SECOND, gui.effectiveBeatMinIntervalMs, 1e-3f)
    }
}

/** Resolves a choice the way `GuiPrefs.safety` does, without a Context. */
private object GuiPrefsFixture {
    fun safety(choice: VisualSafetyChoice): VisualSafety.SafetyConfig = VisualSafety.resolve(choice, VisualSafety.SafetyConfig.OFF)
}
