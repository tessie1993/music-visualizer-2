package dev.musicviz

import dev.musicviz.render.CompositeGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Headless gate for the composite pass' universal grading + geometry math
 * (the CPU mirror of composite_frag's grade block) and for the per-texture
 * gates that decide who it runs on. The fluid family has no grading pass of
 * its own, so the composite shader IS the delivery path for Zoom/Rotation/
 * Saturation/Brightness/Contrast/Gamma/Hue on those styles - and the values
 * sent to the self-grading scenes must be an exact identity, on BOTH the
 * incoming and the outgoing texture, so nothing is ever graded twice.
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
        // A quarter turn maps the +x offset onto the -y offset: the SAMPLING
        // coordinate turns by -angle, which is what turns the picture by
        // +angle on screen.
        val (x, y) = CompositeGrade.geometry(1f, 0.5f, (PI / 2).toFloat(), 1f)
        assertEquals(0.5f, x, eps)
        assertEquals(0f, y, eps)
        // The centre is a fixed point at any angle.
        val (cx, cy) = CompositeGrade.geometry(0.5f, 0.5f, 1.3f, 1f)
        assertEquals(0.5f, cx, eps)
        assertEquals(0.5f, cy, eps)
    }

    @Test
    fun rotationMatchesTheShadersColumnMajorMat2() {
        // GLSL `mat2(a, b, c, d)` is COLUMN-major - column 0 is (a, b) - so
        // geo()'s `mat2(cos(sa), -sin(sa), sin(sa), cos(sa)) * c` expands to
        // (cos*cx + sin*cy, -sin*cx + cos*cy). This mirror used to spell the
        // TRANSPOSE of that (a rotation by +sa), and its own tests agreed with
        // it, so both drifted away from the shader they exist to pin.
        for (angle in listOf(0.35f, 1.1f, -0.8f, 2.6f)) {
            val cs = cos(angle)
            val sn = sin(angle)
            for (u in listOf(0f, 0.25f, 1f)) {
                for (v in listOf(0.1f, 0.5f, 0.9f)) {
                    val cx = u - 0.5f
                    val cy = v - 0.5f
                    val (x, y) = CompositeGrade.geometry(u, v, angle, 1f)
                    assertEquals("x at $angle/$u/$v", cs * cx + sn * cy + 0.5f, x, eps)
                    assertEquals("y at $angle/$u/$v", -sn * cx + cs * cy + 0.5f, y, eps)
                }
            }
        }
        // Still a rigid rotation: the radius from the centre is preserved.
        val (rx, ry) = CompositeGrade.geometry(0.9f, 0.2f, 0.6f, 1f)
        val before = sqrt(0.4f * 0.4f + 0.3f * 0.3f)
        val after = sqrt((rx - 0.5f) * (rx - 0.5f) + (ry - 0.5f) * (ry - 0.5f))
        assertEquals(before, after, eps)
    }

    @Test
    fun everyFamilyOnlyGetsTheGroupsItDoesNotApplyItself() {
        val shader = CompositeGrade.gateFor(CompositeGrade.SceneFamily.SHADER)
        val particle = CompositeGrade.gateFor(CompositeGrade.SceneFamily.PARTICLE)
        val milkdrop = CompositeGrade.gateFor(CompositeGrade.SceneFamily.MILKDROP)
        val fluid = CompositeGrade.gateFor(CompositeGrade.SceneFamily.FLUID)
        // Shader scenes do the lot in view()/grade().
        assertEquals(CompositeGrade.Gate(geo = false, mirrorInvert = false, grade = false, pulse = false), shader)
        // The fluid family applies nothing of its own.
        assertEquals(CompositeGrade.Gate(geo = true, mirrorInvert = true, grade = true, pulse = true), fluid)
        // Particles grade and pulse themselves; milkdrop grades and zooms in
        // pm_post_frag but nothing in it reads `pulse`.
        assertEquals(CompositeGrade.Gate(geo = true, mirrorInvert = true, grade = false, pulse = false), particle)
        assertEquals(CompositeGrade.Gate(geo = true, mirrorInvert = false, grade = false, pulse = true), milkdrop)
        // Nobody but the fluid family may be graded by the composite, or the
        // grade lands twice (squared brightness, doubled contrast).
        assertEquals(
            listOf(CompositeGrade.SceneFamily.FLUID),
            CompositeGrade.SceneFamily.entries.filter { CompositeGrade.gateFor(it).grade },
        )
    }

    @Test
    fun theOutgoingTexturesGateIsIndependentOfTheIncomingScene() {
        // composite_frag routes BOTH uTexA (incoming) and uTexB (outgoing)
        // through postFx, so each texture carries its own gate. Gating both
        // from the ACTIVE scene graded the outgoing julia frame a second time
        // for the whole of a julia -> fluid fade (a white, over-zoomed flash)
        // and dropped the outgoing fluid grade on the reverse switch.
        for (incoming in CompositeGrade.SceneFamily.entries) {
            for (outgoing in CompositeGrade.SceneFamily.entries) {
                val (gateA, gateB) = gatesFor(incoming, outgoing)
                assertEquals("uGateA at $incoming <- $outgoing", CompositeGrade.gateFor(incoming), gateA)
                // The invariant: the outgoing texture is treated exactly as it
                // was on the frame before the switch, whatever replaced it.
                assertEquals("uGateB at $incoming <- $outgoing", CompositeGrade.gateFor(outgoing), gateB)
            }
        }
        // The reported case, both directions.
        val (juliaIn, fluidOut) = gatesFor(CompositeGrade.SceneFamily.FLUID, CompositeGrade.SceneFamily.SHADER)
        assertTrue("the incoming fluid frame must be graded here", juliaIn.grade)
        assertFalse("the outgoing julia frame already graded itself", fluidOut.grade)
        val (back, fluidBack) = gatesFor(CompositeGrade.SceneFamily.SHADER, CompositeGrade.SceneFamily.FLUID)
        assertFalse(back.grade)
        assertTrue("the outgoing fluid frame must keep its grade", fluidBack.grade)
    }

    @Test
    fun theGateUploadsInTheShadersComponentOrder() {
        // vec4(geo, mirrorInvert, grade, pulse) - the order composite_frag
        // reads as gate.x/.y/.z/.w.
        val v = CompositeGrade.Gate(geo = true, mirrorInvert = false, grade = true, pulse = false).toVec4()
        assertEquals(4, v.size)
        assertEquals(floatArrayOf(1f, 0f, 1f, 0f).toList(), v.toList())
        // A fully-off gate is all zeros, which is also GL's default: a program
        // that forgets the upload can only under-apply, never double-apply.
        val off = CompositeGrade.gateFor(CompositeGrade.SceneFamily.SHADER).toVec4()
        assertEquals(listOf(0f, 0f, 0f, 0f), off.toList())
    }

    /** What the renderer uploads as (uGateA, uGateB) during a transition. */
    private fun gatesFor(
        incoming: CompositeGrade.SceneFamily,
        outgoing: CompositeGrade.SceneFamily,
    ): Pair<CompositeGrade.Gate, CompositeGrade.Gate> =
        CompositeGrade.gateFor(incoming) to CompositeGrade.gateFor(outgoing)

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
}
