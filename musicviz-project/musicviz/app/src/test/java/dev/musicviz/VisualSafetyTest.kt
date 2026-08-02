package dev.musicviz

import dev.musicviz.render.BlendMode
import dev.musicviz.render.LfoTarget
import dev.musicviz.render.TransitionStyle
import dev.musicviz.render.VisualSafety
import dev.musicviz.render.VisualSafety.SafetyConfig
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless gate for the photosensitivity clamp.
 *
 * Two properties matter and they pull against each other: turned OFF it must
 * change absolutely nothing (every saved preset, every export parity test),
 * and turned ON it must actually bound the three paths that can flash a whole
 * screen faster than three times a second - the 9 Hz strobe, the beat-driven
 * flash, and an LFO pointed at brightness.
 */
class VisualSafetyTest {
    private val eps = 1e-6f

    /** Params with every flash-capable control pushed to its extreme. */
    private val hostile =
        SceneParams.DEFAULT.copy(
            strobe = 1f,
            flash = 1f,
            glitch = 1f,
            invert = true,
            solarize = true,
            brightness = 3f,
            intensity = 3f,
            contrast = 3f,
            bloom = 1f,
            speed = 4f,
            shake = 1f,
            rotation = 2f,
            endlessZoomSpeed = 2f,
        )

    @Test
    fun disabledIsAnExactNoOp() {
        // Not "equal to" - the SAME instance. Anything less means the default
        // experience took a copy through a clamp, and the export byte-parity
        // tests would be asserting a path users do not have.
        assertSame(hostile, VisualSafety.apply(hostile, SafetyConfig.OFF))
        assertSame(SceneParams.DEFAULT, VisualSafety.apply(SceneParams.DEFAULT, SafetyConfig.OFF))
        assertEquals(VisualSafety.DEFAULT_STROBE_HZ, VisualSafety.strobeHz(SafetyConfig.OFF), eps)
        assertTrue("the shipped default must be the no-op", SafetyConfig().isNeutral)
    }

    @Test
    fun theStrobeRateIsCappedNotJustDimmed() {
        // The whole reason uStrobeHz exists. Before it, `strobe` scaled the
        // DEPTH of a 9 Hz flicker and nothing could reach the rate - so a
        // "safe" mode that only clamped the amount would leave a dimmer
        // 9 Hz strobe, which is still 9 Hz.
        val safe = SafetyConfig(enabled = true)
        assertEquals(VisualSafety.WCAG_FLASHES_PER_SECOND, VisualSafety.strobeHz(safe), eps)
        assertTrue(
            "safe mode must be slower than the shipped strobe",
            VisualSafety.strobeHz(safe) < VisualSafety.strobeHz(SafetyConfig.OFF),
        )
        // A user asking for something stricter than WCAG gets it...
        assertEquals(1f, VisualSafety.strobeHz(safe.copy(maxFlashHz = 1f)), eps)
        // ...but cannot use this control to go FASTER than the app ever was.
        assertEquals(
            VisualSafety.DEFAULT_STROBE_HZ,
            VisualSafety.strobeHz(safe.copy(maxFlashHz = 999f)),
            eps,
        )
    }

    @Test
    fun aFastLfoOnBrightnessIsSlowedToTheFlashLimit() {
        // The randomizer can roll an LFO onto BRIGHTNESS, and LfoEngine clamps
        // rate to 0.01..30 Hz. Clamping the params alone bounds the endpoints
        // of that oscillation and leaves its FREQUENCY untouched, which is the
        // half of the hazard that actually matters.
        val safe = SafetyConfig(enabled = true)
        assertEquals(3f, VisualSafety.limitLfoRate(30f, LfoTarget.BRIGHTNESS, safe), eps)
        assertEquals(3f, VisualSafety.limitLfoRate(30f, LfoTarget.INTENSITY, safe), eps)
        // An LFO already slower than the cap is untouched, not raised.
        assertEquals(0.5f, VisualSafety.limitLfoRate(0.5f, LfoTarget.BRIGHTNESS, safe), eps)
        // Geometry targets are motion, not flashing: reduced motion covers
        // them, and slowing them here would quietly change the look.
        assertEquals(30f, VisualSafety.limitLfoRate(30f, LfoTarget.ROTATION, safe), eps)
        assertEquals(30f, VisualSafety.limitLfoRate(30f, LfoTarget.WARP, safe), eps)
        // And with safety off, nothing is limited at all.
        assertEquals(30f, VisualSafety.limitLfoRate(30f, LfoTarget.BRIGHTNESS, SafetyConfig.OFF), eps)
    }

    @Test
    fun fastModulationIsCappedForColourAndLargeAreaTargetsToo() {
        // The rate cap must not be read as "brightness only". A param can sit
        // at any fixed value safely and still be a hazard when SWUNG fast:
        // a vignette pumping at 30 Hz is a large-area luminance oscillation,
        // and hue/palette at that rate is the saturated-colour flashing
        // WCAG 2.3.1 names separately from luminance. None of these are
        // clamped by apply(), so the rate cap is their only bound.
        val safe = SafetyConfig(enabled = true)
        for (
        target in
        listOf(
            LfoTarget.VIGNETTE,
            LfoTarget.COLOR_SHIFT,
            LfoTarget.PALETTE_MIX,
            LfoTarget.TEMPERATURE,
            LfoTarget.SATURATION,
            LfoTarget.BLOOM,
            LfoTarget.GLITCH,
        )
        ) {
            assertEquals("$target must be rate-capped", 3f, VisualSafety.limitLfoRate(30f, target, safe), eps)
        }
        // Spot-check the other side of the line: these move the picture rather
        // than flash it, so they stay fast and reduced motion owns them.
        for (target in listOf(LfoTarget.ZOOM, LfoTarget.SPEED, LfoTarget.DRIFT_X, LfoTarget.TWIST)) {
            assertEquals("$target is motion, not flashing", 30f, VisualSafety.limitLfoRate(30f, target, safe), eps)
        }
    }

    @Test
    fun enabledBoundsEveryFullScreenLuminancePath() {
        val safe = SafetyConfig(enabled = true)
        val out = VisualSafety.apply(hostile, safe)
        val depth = safe.maxFlashDepth

        // Each flash control is clamped in ITS OWN units, so the resulting
        // on-screen swing is the same budget either way.
        assertEquals(
            "strobe must land at the depth budget once the shader coefficient is applied",
            depth,
            out.strobe * VisualSafety.STROBE_SHADER_DEPTH,
            1e-4f,
        )
        assertEquals(
            "flash must land at the same budget",
            depth,
            out.flash * VisualSafety.FLASH_SHADER_DEPTH,
            1e-4f,
        )
        assertTrue("glitch bounded, got ${out.glitch}", out.glitch <= depth + eps)
        // Bloom is a superlinear full-frame luminance ADD, so it is bounded by
        // the same budget rather than only rate-capped.
        assertTrue("bloom bounded, got ${out.bloom}", out.bloom <= depth + eps)
        assertFalse("full-frame inversion is the largest possible contrast jump", out.invert)
        assertFalse("solarize folds the curve almost as abruptly", out.solarize)
        assertTrue("brightness bounded, got ${out.brightness}", out.brightness <= 1f + depth + eps)
        assertTrue("intensity bounded, got ${out.intensity}", out.intensity <= 1f + depth + eps)
        assertTrue("contrast bounded, got ${out.contrast}", out.contrast <= 1f + depth + eps)
    }

    @Test
    fun aZeroDepthBudgetRemovesFlashingEntirely() {
        val out = VisualSafety.apply(hostile, SafetyConfig(enabled = true, maxFlashDepth = 0f))
        assertEquals(0f, out.strobe, eps)
        assertEquals(0f, out.flash, eps)
        assertEquals(0f, out.glitch, eps)
        assertEquals(0f, out.bloom, eps)
        assertEquals("neutral level", 1f, out.brightness, eps)
        assertEquals("neutral contrast", 1f, out.contrast, eps)
    }

    @Test
    fun inversionIsAvailableToUsersWhoWantIt() {
        // Safe mode is a photosensitivity setting, not a taste setting: a user
        // who has turned it on for flash-rate reasons can still keep invert.
        val out = VisualSafety.apply(hostile, SafetyConfig(enabled = true, allowInversion = true))
        assertTrue(out.invert)
        assertTrue(out.solarize)
        // ...while the rate/depth limits still hold.
        assertTrue(out.strobe < hostile.strobe)
    }

    @Test
    fun reducedMotionScalesMotionAndLeavesFlashingAlone() {
        // The two switches are independent: reduced motion is a comfort
        // setting (vestibular), safe visuals is a seizure setting. Turning on
        // only the former must not silently also clamp brightness, or the
        // settings would not mean what they say.
        val out = VisualSafety.apply(hostile, SafetyConfig(reducedMotion = true))
        val k = VisualSafety.REDUCED_MOTION_SCALE
        assertEquals(hostile.speed * k, out.speed, eps)
        assertEquals(hostile.shake * k, out.shake, eps)
        assertEquals(hostile.rotation * k, out.rotation, eps)
        // Continuous zoom is the strongest vection trigger in the list.
        assertEquals(hostile.endlessZoomSpeed * k, out.endlessZoomSpeed, eps)
        assertEquals("flash untouched by reduced motion", hostile.flash, out.flash, eps)
        assertEquals("strobe untouched by reduced motion", hostile.strobe, out.strobe, eps)
        assertTrue("invert untouched by reduced motion", out.invert)
    }

    @Test
    fun bothSwitchesCompose() {
        val out = VisualSafety.apply(hostile, SafetyConfig(enabled = true, reducedMotion = true))
        assertTrue("flash still clamped", out.flash < hostile.flash)
        assertEquals("and motion still scaled", hostile.speed * VisualSafety.REDUCED_MOTION_SCALE, out.speed, eps)
    }

    @Test
    fun clampingIsIdempotent() {
        // apply() runs once per frame on params that may already have been
        // clamped by a previous pass through the same chain; a second pass
        // must not keep shrinking the picture.
        val safe = SafetyConfig(enabled = true, reducedMotion = true)
        val once = VisualSafety.apply(hostile, safe)
        val twice = VisualSafety.apply(once, safe)
        assertEquals(once.strobe, twice.strobe, eps)
        assertEquals(once.flash, twice.flash, eps)
        assertEquals(once.brightness, twice.brightness, eps)
        assertEquals(once.contrast, twice.contrast, eps)
        // Motion scaling is NOT idempotent by construction (it is a multiply),
        // which is exactly why the renderer must clamp the faded+modulated
        // params once per frame rather than feeding its own output back in.
        assertEquals(once.speed * VisualSafety.REDUCED_MOTION_SCALE, twice.speed, eps)
    }

    @Test
    fun theBeatFlashRateIsFlooredInTheAnalyzer() {
        // `flash` fires once per detected beat, so no visual slider governs
        // how OFTEN it fires - only the analyzer's minimum gap does. At the
        // shipped 200 ms floor a dense track flashes 5 times a second.
        val safe = SafetyConfig(enabled = true)
        assertEquals(1000f / 3f, VisualSafety.beatMinIntervalMs(200f, safe), 1e-3f)
        // A user who already chose something calmer keeps it - the clamp only
        // ever raises the gap.
        assertEquals(700f, VisualSafety.beatMinIntervalMs(700f, safe), eps)
        // Off, it is the identity.
        assertEquals(200f, VisualSafety.beatMinIntervalMs(200f, SafetyConfig.OFF), eps)
    }

    @Test
    fun hardCutsBecomeCrossfades() {
        val safe = SafetyConfig(enabled = true)
        // A CUT swaps the whole frame between two consecutive frames, which is
        // exactly the full-screen step change the flash limits exist to bound.
        assertEquals(TransitionStyle.FADE, VisualSafety.transitionStyle(TransitionStyle.CUT, safe))
        // The styles that already ramp are untouched.
        for (style in TransitionStyle.entries.filter { it != TransitionStyle.CUT }) {
            assertEquals(style, VisualSafety.transitionStyle(style, safe))
        }
        assertEquals(TransitionStyle.CUT, VisualSafety.transitionStyle(TransitionStyle.CUT, SafetyConfig.OFF))
    }

    @Test
    fun safeParamsNeverExceedTheUnsafeOnesTheyCameFrom() {
        // Property check over the whole grid: the clamp may only reduce a
        // flash-capable value, never raise one. A "safety" setting that made
        // something brighter would be the worst possible bug here.
        val safe = SafetyConfig(enabled = true)
        for (v in listOf(0f, 0.1f, 0.25f, 0.5f, 0.9f, 1f)) {
            val src =
                SceneParams.DEFAULT.copy(
                    strobe = v,
                    flash = v,
                    glitch = v,
                    bloom = v,
                    brightness = 1f + v,
                    contrast = 1f + v,
                )
            val out = VisualSafety.apply(src, safe)
            assertTrue("strobe rose at $v", out.strobe <= src.strobe + eps)
            assertTrue("flash rose at $v", out.flash <= src.flash + eps)
            assertTrue("glitch rose at $v", out.glitch <= src.glitch + eps)
            assertTrue("bloom rose at $v", out.bloom <= src.bloom + eps)
            assertTrue("brightness rose at $v", out.brightness <= src.brightness + eps)
            assertTrue("contrast rose at $v", out.contrast <= src.contrast + eps)
        }
    }

    // ---- Layers ------------------------------------------------------------

    /**
     * Layers reaches the screen after apply() has clamped every parameter, so
     * the mix is the only place its magnitude can be bounded. These pin that
     * the two amplifying modes are bounded and the six bounded ones are not
     * touched - a clamp that hit all eight would quietly ruin Screen and
     * Multiply for no safety gain.
     */
    @Test
    fun `layer mix is untouched when safety is off`() {
        val off = VisualSafety.SafetyConfig.OFF
        for (mode in BlendMode.entries) {
            assertEquals(mode.name, 1f, VisualSafety.layerMix(1f, mode, off), 0f)
        }
    }

    @Test
    fun `bounded blend modes keep their full mix under safety`() {
        val on = VisualSafety.SafetyConfig(enabled = true, maxFlashDepth = 0.25f)
        for (mode in listOf(
            BlendMode.NORMAL,
            BlendMode.SCREEN,
            BlendMode.MULTIPLY,
            BlendMode.OVERLAY,
            BlendMode.LIGHTEN,
            BlendMode.DARKEN,
        )) {
            assertEquals(
                "$mode cannot exceed its inputs, so it needs no clamp",
                1f,
                VisualSafety.layerMix(1f, mode, on),
                0f,
            )
        }
    }

    @Test
    fun `add is capped to the flash depth under safety`() {
        val on = VisualSafety.SafetyConfig(enabled = true, maxFlashDepth = 0.25f)
        assertEquals(0.25f, VisualSafety.layerMix(1f, BlendMode.ADD, on), 1e-6f)
        // Already below the cap: untouched, not raised.
        assertEquals(0.1f, VisualSafety.layerMix(0.1f, BlendMode.ADD, on), 1e-6f)
    }

    /** DIFFERENCE is a contrast reversal, so it rides allowInversion. */
    @Test
    fun `difference is off unless inversion is allowed`() {
        val noInvert = VisualSafety.SafetyConfig(enabled = true, allowInversion = false)
        assertEquals(0f, VisualSafety.layerMix(1f, BlendMode.DIFFERENCE, noInvert), 0f)

        val invert = VisualSafety.SafetyConfig(enabled = true, allowInversion = true, maxFlashDepth = 0.25f)
        assertEquals(0.25f, VisualSafety.layerMix(1f, BlendMode.DIFFERENCE, invert), 1e-6f)
    }

    @Test
    fun `layer mix is clamped into range whatever the caller passes`() {
        val off = VisualSafety.SafetyConfig.OFF
        assertEquals(0f, VisualSafety.layerMix(-3f, BlendMode.SCREEN, off), 0f)
        assertEquals(1f, VisualSafety.layerMix(9f, BlendMode.SCREEN, off), 0f)
    }

    /** The ordinals are composite_frag's switch values; only appending is safe. */
    @Test
    fun `blend mode ordinals are the shader contract`() {
        assertEquals(0, BlendMode.NORMAL.ordinal)
        assertEquals(4, BlendMode.DIFFERENCE.ordinal)
        assertEquals(8, BlendMode.entries.size)
        assertEquals(BlendMode.SCREEN, BlendMode.fromOrdinal(99))
        assertEquals(BlendMode.SCREEN, BlendMode.fromOrdinal(-1))
    }
}
