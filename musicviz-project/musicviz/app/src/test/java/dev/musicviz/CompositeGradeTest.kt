package dev.musicviz

import dev.musicviz.render.CompositeGrade
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Headless gate for the composite pass' universal grading + geometry math
 * (the CPU mirror of composite_frag's `uPostGrade` block). The fluid family
 * has no grading pass of its own, so the composite shader IS the delivery
 * path for Zoom/Rotation/Saturation/Brightness/Contrast/Gamma/Hue on those
 * styles - and the neutral values sent to the self-grading scenes must be an
 * exact identity so nothing is ever graded twice.
 *
 * The "Palette tint" block at the bottom pins MilkDrop's own stage
 * (pm_post_frag's `paletteTint`, the Palettes card's only reader on that
 * style): that its default is an EXACT no-op, and that a full tint still
 * leaves two different .milk presets looking different.
 */
class CompositeGradeTest {
    private val eps = 1e-4f

    private fun luma(rgb: FloatArray) = rgb[0] * 0.299f + rgb[1] * 0.587f + rgb[2] * 0.114f

    private fun assertRgb(
        expected: FloatArray,
        actual: FloatArray,
        message: String = "",
    ) {
        for (i in 0..2) assertEquals("$message channel $i", expected[i], actual[i], eps)
    }

    @Test
    fun neutralGradeIsIdentity() {
        val colors =
            listOf(
                floatArrayOf(0f, 0f, 0f),
                floatArrayOf(1f, 1f, 1f),
                floatArrayOf(0.2f, 0.5f, 0.9f),
                floatArrayOf(1.6f, 0.03f, 0.44f),
            )
        for (c in colors) {
            assertRgb(c, CompositeGrade.grade(c, 0f, 1f, 1f, 1f, 1f), "neutral grade")
        }
    }

    @Test
    fun neutralGeometryIsIdentity() {
        for (u in listOf(0f, 0.25f, 0.5f, 1f)) {
            for (v in listOf(0f, 0.5f, 0.75f, 1f)) {
                val (x, y) = CompositeGrade.geometry(u, v, 0f, 1f)
                assertEquals(u, x, eps)
                assertEquals(v, y, eps)
            }
        }
    }

    @Test
    fun zoomMagnifiesAboutTheCentre() {
        // Zoom 2 halves the distance from the centre: each texel now covers
        // twice the screen, i.e. the image is magnified 2x.
        val (x, y) = CompositeGrade.geometry(1f, 1f, 0f, 2f)
        assertEquals(0.75f, x, eps)
        assertEquals(0.75f, y, eps)
        // The centre is a fixed point at any zoom.
        val (cx, cy) = CompositeGrade.geometry(0.5f, 0.5f, 0f, 7.3f)
        assertEquals(0.5f, cx, eps)
        assertEquals(0.5f, cy, eps)
    }

    @Test
    fun zoomIsFlooredSoItCanNeverDivideByZero() {
        val (x, _) = CompositeGrade.geometry(1f, 0.5f, 0f, 0f)
        // 0.5 / 0.05 = 10 -> 10.5, the clamp - not an infinity or a NaN.
        assertEquals(10.5f, x, eps)
        assertTrue(x.isFinite())
    }

    @Test
    fun rotationTurnsAboutTheCentre() {
        // A quarter turn maps the +x offset onto the +y offset.
        val (x, y) = CompositeGrade.geometry(1f, 0.5f, (PI / 2).toFloat(), 1f)
        assertEquals(0.5f, x, eps)
        assertEquals(1f, y, eps)
    }

    @Test
    fun rotationIsIntegratedAsASpeedAndStaysBounded() {
        // 1 rad/s over one second of 60 Hz frames -> ~1 rad total, not 1 rad
        // per frame (the slider is a speed on every other scene type).
        var angle = 0f
        repeat(60) { angle = CompositeGrade.integrateRotation(angle, 1f, 1f / 60f) }
        assertEquals(1f, angle, 1e-3f)
        // Long sessions must wrap instead of bleeding float precision.
        var wrapped = 0f
        repeat(100_000) { wrapped = CompositeGrade.integrateRotation(wrapped, 2f, 1f / 60f) }
        assertTrue("angle grew unbounded: $wrapped", abs(wrapped) <= 6.2832f)
    }

    @Test
    fun swayFoldsIntoTheRotationAngle() {
        // Shader parity: a = uPostRotation + uPostSway * 0.35 * sin(t * 0.7).
        assertEquals(0.8f, CompositeGrade.swayAngle(0.8f, 0f, 3.1f), eps)
        assertNotEquals(0.8f, CompositeGrade.swayAngle(0.8f, 1f, 1f), 1e-2f)
    }

    @Test
    fun cyclePhaseAdvancesOnlyWhileEnabledAndWraps() {
        var phase = 0f
        repeat(60) { phase = CompositeGrade.integrateCyclePhase(phase, 0.5f, 1f / 60f, true) }
        // One second at 0.5 turns/s = half a turn, kept inside [0,1).
        assertEquals(0.5f, phase, 1e-3f)
        assertTrue(phase >= 0f && phase < 1f)
        val held = CompositeGrade.integrateCyclePhase(phase, 0.5f, 1f, false)
        assertEquals(phase, held, 0f)
    }

    @Test
    fun beatPulseEnvelopeSnapsOnABeatAndDecaysToZero() {
        // Parity with ShaderScene/ParticleSceneBase:
        // beatPulse = if (beat) 1f else (beatPulse - dt * 3f).coerceAtLeast(0f)
        var env = CompositeGrade.integrateBeatPulse(0f, beat = true, dt = 1f / 60f)
        assertEquals(1f, env, eps)
        repeat(10) { env = CompositeGrade.integrateBeatPulse(env, beat = false, dt = 1f / 60f) }
        assertEquals(0.5f, env, 1e-3f)
        // A third of a second after the beat it is fully spent, never negative.
        repeat(60) { env = CompositeGrade.integrateBeatPulse(env, beat = false, dt = 1f / 60f) }
        assertEquals(0f, env, 0f)
    }

    @Test
    fun pulseAmountIsNeutralWithoutASliderOrABeat() {
        // Both factors must be able to switch the swell off on their own:
        // pulse 0 (slider parked) and envelope 0 (no recent beat).
        assertEquals(0f, CompositeGrade.pulseAmount(0f, 1f), 0f)
        assertEquals(0f, CompositeGrade.pulseAmount(1f, 0f), 0f)
        // 0 is the GL default too, so an un-uploading program is an identity.
        assertEquals(1f, CompositeGrade.pulseScale(0f), 0f)
        val (x, y) = CompositeGrade.geometry(0.8f, 0.3f, 0f, 1f, pulseAmount = 0f)
        assertEquals(0.8f, x, eps)
        assertEquals(0.3f, y, eps)
    }

    @Test
    fun pulseUsesTheShaderScenesResponseCurve() {
        // plasma_frag: 1.0 + uPulse * 0.22 * bump, with bump the SQUARED beat
        // bump - so a full slider on the beat is a 22% swell, and a quarter of
        // the way down the envelope it is already a quarter of that.
        assertEquals(1.22f, CompositeGrade.pulseScale(CompositeGrade.pulseAmount(1f, 1f)), eps)
        assertEquals(1.055f, CompositeGrade.pulseScale(CompositeGrade.pulseAmount(1f, 0.5f)), eps)
        // Half the slider is half the swell at the same point in the envelope.
        assertEquals(1.11f, CompositeGrade.pulseScale(CompositeGrade.pulseAmount(0.5f, 1f)), eps)
    }

    @Test
    fun pulseMagnifiesAboutTheCentreLikeZoom() {
        val amount = CompositeGrade.pulseAmount(1f, 1f)
        val (x, y) = CompositeGrade.geometry(1f, 1f, 0f, 1f, amount)
        // uv /= (1 + 0.22): the sampled offset shrinks, i.e. the image swells.
        assertEquals(0.5f + 0.5f / 1.22f, x, eps)
        assertEquals(0.5f + 0.5f / 1.22f, y, eps)
        // The centre is a fixed point, as it is for zoom.
        val (cx, cy) = CompositeGrade.geometry(0.5f, 0.5f, 0f, 1f, amount)
        assertEquals(0.5f, cx, eps)
        assertEquals(0.5f, cy, eps)
        // Pulse and zoom compose as separate divides, in the shader's order.
        val (zx, _) = CompositeGrade.geometry(1f, 0.5f, 0f, 2f, amount)
        assertEquals(0.5f + 0.5f / (2f * 1.22f), zx, eps)
    }

    @Test
    fun saturationZeroCollapsesToLuma() {
        val c = floatArrayOf(0.9f, 0.2f, 0.4f)
        val lum = luma(c)
        assertRgb(floatArrayOf(lum, lum, lum), CompositeGrade.grade(c, 0f, 0f, 1f, 1f, 1f))
    }

    @Test
    fun saturationAboveOnePushesAwayFromLuma() {
        val c = floatArrayOf(0.9f, 0.2f, 0.4f)
        val lum = luma(c)
        val out = CompositeGrade.grade(c, 0f, 2f, 1f, 1f, 1f)
        for (i in 0..2) assertEquals(lum + (c[i] - lum) * 2f, out[i], eps)
    }

    @Test
    fun contrastPivotsAroundMidGrey() {
        val out = CompositeGrade.grade(floatArrayOf(0.5f, 0.25f, 0.75f), 0f, 1f, 2f, 1f, 1f)
        assertEquals(0.5f, out[0], eps)
        assertEquals(0f, out[1], eps)
        assertEquals(1f, out[2], eps)
    }

    @Test
    fun gammaMatchesTheSceneShaderFormula() {
        // Every scene shader uses pow(col, 1 / max(gamma, 0.05)).
        val out = CompositeGrade.grade(floatArrayOf(0.25f, 0.5f, 0.75f), 0f, 1f, 1f, 2f, 1f)
        for ((i, v) in listOf(0.25f, 0.5f, 0.75f).withIndex()) {
            assertEquals(sqrt(v), out[i], eps)
        }
        val lift = CompositeGrade.grade(floatArrayOf(0.4f, 0.4f, 0.4f), 0f, 1f, 1f, 2f, 1f)
        val crush = CompositeGrade.grade(floatArrayOf(0.4f, 0.4f, 0.4f), 0f, 1f, 1f, 0.5f, 1f)
        assertTrue("gamma > 1 must lift midtones", lift[0] > 0.4f)
        assertTrue("gamma < 1 must crush midtones", crush[0] < 0.4f)
    }

    @Test
    fun gammaIsFlooredSoZeroCannotExplode() {
        val out = CompositeGrade.grade(floatArrayOf(0.5f, 0.5f, 0.5f), 0f, 1f, 1f, 0f, 1f)
        for (i in 0..2) assertTrue("channel $i = ${out[i]}", out[i].isFinite())
    }

    @Test
    fun brightnessFoldsIntensityAndScalesLinearly() {
        assertEquals(1.5f, CompositeGrade.brightness(0.75f, 2f), eps)
        val out = CompositeGrade.grade(floatArrayOf(0.2f, 0.4f, 0.6f), 0f, 1f, 1f, 1f, 1.5f)
        assertRgb(floatArrayOf(0.3f, 0.6f, 0.9f), out)
    }

    @Test
    fun hueRotationIsCyclicAndFixesTheGreyAxis() {
        val c = floatArrayOf(0.9f, 0.2f, 0.35f)
        assertNotEquals(c[0], CompositeGrade.hueRotate(c, 0.33f)[0], 1e-2f)
        // Greys have no hue to rotate: r == g == b is a fixed point at every
        // angle (both the (c - g) term and the cross product vanish).
        val grey = floatArrayOf(0.42f, 0.42f, 0.42f)
        for (amount in listOf(0.1f, 0.5f, 0.9f)) {
            assertRgb(grey, CompositeGrade.hueRotate(grey, amount), "grey at $amount")
        }
        // Zero rotation is an exact identity - the neutral value the renderer
        // sends to every scene that grades itself.
        assertRgb(c, CompositeGrade.hueRotate(c, 0f), "zero rotation")
        // A full turn returns the original color (the shader spells 2*pi as
        // 6.2831, a hair short, so this is approximate by construction).
        assertRgb(c, CompositeGrade.hueRotate(c, 1f), "full hue turn")
    }

    @Test
    fun gradeOrderMatchesTheSceneShaders() {
        // hue -> saturation -> contrast -> gamma -> brightness, exactly how
        // plasma_frag's grade() and pm_post_frag sequence the same stages.
        val c = floatArrayOf(0.35f, 0.62f, 0.18f)
        val hue = 0.21f
        val sat = 1.4f
        val contrast = 1.2f
        val gamma = 0.8f
        val bright = 1.1f
        val hued = CompositeGrade.hueRotate(c, hue)
        val lum = luma(hued)
        val satted = FloatArray(3) { lum + (hued[it] - lum) * sat }
        val contrasted = FloatArray(3) { (satted[it] - 0.5f) * contrast + 0.5f }
        val gammaed = FloatArray(3) { maxOf(contrasted[it], 0f).pow(1f / gamma) }
        val expected = FloatArray(3) { gammaed[it] * bright }
        assertRgb(expected, CompositeGrade.grade(c, hue, sat, contrast, gamma, bright))
    }

    // ---------------------------------------------------------- Palette tint

    /** A few strongly-coloured "presets", i.e. pixels that have a real hue. */
    private val coloured =
        listOf(
            floatArrayOf(0.9f, 0.1f, 0.1f),
            floatArrayOf(0.1f, 0.8f, 0.2f),
            floatArrayOf(0.15f, 0.3f, 0.95f),
            floatArrayOf(0.7f, 0.7f, 0.05f),
            floatArrayOf(0.6f, 0.05f, 0.75f),
        )

    /** Independent hue/saturation/value probe (NOT the mirror's own helper). */
    private fun hsv(rgb: FloatArray): Triple<Float, Float, Float> {
        val mx = maxOf(rgb[0], maxOf(rgb[1], rgb[2]))
        val mn = minOf(rgb[0], minOf(rgb[1], rgb[2]))
        val d = mx - mn
        val h =
            when {
                d <= 0f -> 0f
                mx == rgb[0] -> ((rgb[1] - rgb[2]) / d / 6f + 1f) % 1f
                mx == rgb[1] -> (2f + (rgb[2] - rgb[0]) / d) / 6f
                else -> (4f + (rgb[0] - rgb[1]) / d) / 6f
            }
        return Triple(h, if (mx <= 0f) 0f else d / mx, mx)
    }

    /** Distance from [base] going forwards round the wheel, in [0,1). */
    private fun arc(
        hue: Float,
        base: Float,
    ): Float = ((hue - base) % 1f + 1f) % 1f

    @Test
    fun theDefaultTintIsAnExactNoOp() {
        // The whole design rests on this: existing presets and the default
        // experience must be untouched until the user opts in.
        assertEquals(0f, SceneParams.DEFAULT.milkdropPaletteTint, 0f)
        val amount = CompositeGrade.paletteTintAmount(SceneParams.DEFAULT.milkdropPaletteTint)
        assertEquals(0f, amount, 0f)
        val samples = coloured + listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0.4f, 0.4f, 0.4f))
        for (c in samples) {
            for (palette in SceneParams.PALETTES) {
                val span = CompositeGrade.paletteSpan(1f, palette.third)
                val out = CompositeGrade.paletteTint(c, palette.second, span, amount)
                for (i in 0..2) {
                    assertEquals("palette ${palette.first} channel $i", c[i], out[i], 0f)
                }
            }
        }
    }

    @Test
    fun tintNeverTouchesValueSoPresetsKeepTheirStructure() {
        // Value is the preset's own light and shade: whatever the palette
        // does, the shapes, contrast and motion must survive it intact.
        for (c in coloured + listOf(floatArrayOf(0.05f, 0.05f, 0.06f), floatArrayOf(0.98f, 0.98f, 0.98f))) {
            for (amount in listOf(0.25f, 0.5f, 1f)) {
                val out = CompositeGrade.paletteTint(c, 0f, 0.14f, amount)
                assertEquals("value at $amount", hsv(c).third, hsv(out).third, eps)
            }
        }
    }

    @Test
    fun fullTintLandsInsideThePaletteBand() {
        // Fire: base 0, span 0.14 - the narrowest real palette. Every
        // coloured pixel must end up inside that band of the wheel.
        val base = 0.05f
        val span = 0.14f
        for (c in coloured) {
            val out = CompositeGrade.paletteTint(c, base, span, 1f)
            val d = arc(hsv(out).first, base)
            assertTrue("hue $d outside 0..$span for ${c.toList()}", d <= span + 1e-3f || d >= 1f - 1e-3f)
        }
    }

    @Test
    fun presetsKeepTheirIndividualCharacterUnderAFullTint() {
        // The failure mode this stage must avoid: every .milk preset painted
        // the same. Distinct hues stay distinct AND stay in the same order
        // round the wheel, because the tint compresses the wheel rather than
        // replacing it.
        val base = 0.05f
        val span = 0.14f
        val hues = coloured.map { hsv(it).first }.sorted()
        val tinted =
            hues.map { h ->
                val rgb = CompositeGrade.paletteTint(rgbOfHue(h), base, span, 1f)
                arc(hsv(rgb).first, base)
            }
        for (i in 0 until tinted.size - 1) {
            assertTrue("hues $i and ${i + 1} collapsed onto one colour", tinted[i + 1] - tinted[i] > 1e-3f)
        }
        // ... and the compression is exactly the palette's span.
        assertEquals(span * (hues.last() - hues.first()), tinted.last() - tinted.first(), 1e-3f)
    }

    @Test
    fun aColouredPixelKeepsItsSaturationExactly() {
        // The lift exists for greys only; a tint must not double as a
        // saturation boost. With the Spectrum palette (base 0, span 1) that
        // makes a full tint an identity on coloured pixels.
        for (c in coloured) {
            val out = CompositeGrade.paletteTint(c, 0f, 1f, 1f)
            assertRgb(c, out, "spectrum full tint")
        }
    }

    @Test
    fun greysTakeThePaletteEntryTheirBrightnessSelects() {
        // A grey has no hue to steer, so it is gradient-mapped instead - the
        // only way the white cores most presets draw can show the palette.
        val base = 0.55f
        val span = 0.2f
        for (v in listOf(0.2f, 0.5f, 0.9f)) {
            val grey = floatArrayOf(v, v, v)
            val out = CompositeGrade.paletteTint(grey, base, span, 1f)
            val (h, s, value) = hsv(out)
            assertEquals("grey $v must keep its value", v, value, eps)
            assertEquals("grey $v must gain the palette's chroma", CompositeGrade.TINT_SAT_LIFT, s, eps)
            assertEquals("grey $v must take the palette entry for its luma", base + v * span, h, 1e-3f)
        }
        // Black has no value to colour: it stays black at any tint.
        assertRgb(floatArrayOf(0f, 0f, 0f), CompositeGrade.paletteTint(floatArrayOf(0f, 0f, 0f), base, span, 1f))
    }

    @Test
    fun tintBlendsContinuouslyFromNoOpToFull() {
        val c = floatArrayOf(0.15f, 0.3f, 0.95f)
        val base = 0.05f
        val span = 0.14f
        val full = hsv(CompositeGrade.paletteTint(c, base, span, 1f)).first
        val start = hsv(c).first
        var previous = arc(start, base)
        // Walking the slider up moves the hue monotonically from the preset's
        // own toward the palette's, and never past it.
        for (amount in listOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)) {
            val d = arc(hsv(CompositeGrade.paletteTint(c, base, span, amount)).first, base)
            assertTrue("tint $amount moved backwards", d < previous + 1e-3f)
            previous = d
        }
        assertEquals(arc(full, base), previous, eps)
    }

    @Test
    fun paletteSpanIsTheShaderAndParticleForm() {
        // `paletteRange * hueRange`, the product ShaderScene and
        // ParticleSceneBase use - NOT FluidHue.span, which clamps hueRange to
        // 0.1..1 for reasons that belong to the fluid emitters.
        assertEquals(0.14f, CompositeGrade.paletteSpan(1f, 0.14f), eps)
        assertEquals(0.07f, CompositeGrade.paletteSpan(0.5f, 0.14f), eps)
        // A zero hue range legitimately means "one hue" here, as on a shader
        // scene, and the slider's 1.0-1.5 band is not flat.
        assertEquals(0f, CompositeGrade.paletteSpan(0f, 0.7f), eps)
        assertTrue(CompositeGrade.paletteSpan(1.5f, 0.7f) > CompositeGrade.paletteSpan(1f, 0.7f))
        // The tint amount is the raw slider, clamped to its own range.
        assertEquals(0.5f, CompositeGrade.paletteTintAmount(0.5f), 0f)
        assertEquals(1f, CompositeGrade.paletteTintAmount(2f), 0f)
        assertEquals(0f, CompositeGrade.paletteTintAmount(-1f), 0f)
    }

    @Test
    fun everyPaletteIncludingACustomOneReachesTheTint() {
        // paletteBase/paletteRange resolve the palette maker's overrides
        // transparently, so the tint needs no custom-palette branch: a custom
        // palette must land in its own band exactly like a built-in one.
        val custom = SceneParams.DEFAULT.copy(paletteBaseOverride = 0.42f, paletteRangeOverride = 0.09f)
        val slots =
            SceneParams.PALETTES.map { it.second to it.third } +
                listOf(custom.paletteBase to custom.paletteRange)
        val probe = floatArrayOf(0.15f, 0.3f, 0.95f)
        for ((base, range) in slots) {
            val span = CompositeGrade.paletteSpan(1f, range)
            val out = CompositeGrade.paletteTint(probe, base, span, 1f)
            val d = arc(hsv(out).first, base)
            assertTrue("base $base span $span produced hue arc $d", d <= span + 1e-3f || d >= 1f - 1e-3f)
        }
    }

    /** A fully saturated, full-value colour at hue [h] - HSV(h, 1, 1). */
    private fun rgbOfHue(h: Float): FloatArray {
        val k = floatArrayOf(1f, 2f / 3f, 1f / 3f)
        return FloatArray(3) {
            val x = (h + k[it]).let { v -> v - floor(v) }
            (abs(x * 6f - 3f) - 1f).coerceIn(0f, 1f)
        }
    }
}
