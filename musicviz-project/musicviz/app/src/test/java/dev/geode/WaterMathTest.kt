package dev.geode

import dev.geode.analysis.AudioFeatures
import dev.geode.render.fluid.FluidChoreography
import dev.geode.render.fluid.FluidEmitters
import dev.geode.render.fluid.FluidHue
import dev.geode.render.fluid.RippleMath
import dev.geode.render.fluid.WaterMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Headless gate for the two Customize sliders the WATER style used to
 * silently ignore, so they can never regress into no-ops again:
 *
 *  - "Catch radius" (fluidCatchRadius) is offered for WATER because the
 *    style is a journey scene, but WaterScene consumed only fluidCatchPull.
 *    It now sizes the catch-point drain well; these tests pin that a wider
 *    slider setting really does widen the well's footprint on the
 *    heightfield, and that the well dips DOWN - what distinguishes a drain
 *    from an ordinary drop.
 *  - "Hue shift" (colorShift) was read by no fluid scene at all. It is
 *    delivered by the COMPOSITE pass now (`uPostHue = colorShift +
 *    cyclePhase`, uploaded for every scene that does not grade itself), so
 *    one slider means one thing across every scene family. This pass keeps
 *    palette IDENTITY only - base hue and span through the shared [FluidHue]
 *    helper - because folding the shift in here as well rotated the pool
 *    twice per slider unit. FluidHueTest pins the once-only arithmetic.
 *
 * Plus one guard in the other direction: Brightness and Intensity are graded
 * by the COMPOSITE pass for every scene that does not grade itself, WATER
 * included, so this scene's own display pass must contribute a neutral
 * factor. Folding them in here too made the response quadratic.
 *
 * The classification test guards the coupling this rests on: WaterScene
 * recognizes a drain by its zero dye, a contract owned by FluidEmitters.
 */
class WaterMathTest {
    private fun features(
        beat: Boolean = false,
        treble: Float = 0.05f,
    ) = AudioFeatures(
        bands = FloatArray(16) { it / 16f },
        waveform = FloatArray(64),
        rms = 0.4f,
        bass = 0.6f,
        mid = 0.3f,
        treble = treble,
        beat = beat,
    )

    @Test
    fun catchRadiusIsClampedToTheSliderDomain() {
        assertEquals(WaterMath.MIN_CATCH_RADIUS, WaterMath.catchWellRadius(0f), 0f)
        assertEquals(WaterMath.MIN_CATCH_RADIUS, WaterMath.catchWellRadius(-1f), 0f)
        assertEquals(WaterMath.MAX_CATCH_RADIUS, WaterMath.catchWellRadius(9f), 0f)
        assertEquals(0.2f, WaterMath.catchWellRadius(0.2f), 1e-6f)
    }

    @Test
    fun catchWellDipsDownAndScalesWithPull() {
        val weak = WaterMath.catchWellAmplitude(speed = 0.2f, catchRadius = 0.12f, rippleStrength = 1f)
        val strong = WaterMath.catchWellAmplitude(speed = 1.4f, catchRadius = 0.12f, rippleStrength = 1f)
        assertTrue("a drain must pull the surface down, not splash (got $weak)", weak < 0f)
        assertTrue("stronger suction must dig deeper ($strong vs $weak)", strong < weak)
        // Ripple strength 0 must keep the whole style still, wells included.
        assertEquals(0f, WaterMath.catchWellAmplitude(1f, 0.12f, 0f), 0f)
    }

    @Test
    fun widerCatchRadiusWidensTheWellOnTheHeightfield() {
        // The bug was "the slider moves and nothing on screen changes", so
        // measure the well the way the sim sees it - through the Gaussian
        // drop kernel - at a fixed distance from the drain center.
        val speed = 0.5f
        var prev = 0f
        for (r in floatArrayOf(0.04f, 0.08f, 0.12f, 0.2f, 0.3f)) {
            val radius = WaterMath.catchWellRadius(r)
            val amp = WaterMath.catchWellAmplitude(speed, r, 1f)
            val reach = abs(RippleMath.dropProfile(dist = 0.25f, radius = radius, amp = amp))
            assertTrue("catch radius $r must reach farther than the previous step ($reach vs $prev)", reach > prev)
            prev = reach
        }
        assertTrue("a wide well must displace measurably 0.25 sim units out", prev > 1e-3f)
        // A wider drain spreads its pull: broad and shallow, not a deep crater.
        val narrowPeak = abs(WaterMath.catchWellAmplitude(speed, 0.04f, 1f))
        val widePeak = abs(WaterMath.catchWellAmplitude(speed, 0.3f, 1f))
        assertTrue("wide wells must be shallower at the center ($widePeak vs $narrowPeak)", widePeak < narrowPeak)
    }

    @Test
    fun onlySuctionSplatsAreClassifiedAsWells() {
        assertTrue(WaterMath.isCatchWell(0f, 0f, 0f))
        assertTrue("negative dye is still no dye", WaterMath.isCatchWell(-0f, 0f, -1e-9f))
        assertFalse(WaterMath.isCatchWell(0f, 0f, 0.01f))
        assertFalse(WaterMath.isCatchWell(0.4f, 0f, 0f))
        // And against the real emitter stream: every dyed splat must stay a
        // drop, every suction splat must be recognized as a drain.
        val choreography = FluidChoreography().apply { catchCount = 2 }
        val emitters =
            FluidEmitters(Random(11)).apply {
                beatSplats = 4
                stirrers = 3
                sparkle = true
                bassPump = true
                catchSuction = 1.5f
            }
        emitters.choreography = choreography
        val dt = 1f / 60f
        var wells = 0
        var drops = 0
        repeat(120) { i ->
            val f = features(beat = i % 20 == 0, treble = if (i % 7 == 0) 1f else 0.05f)
            choreography.tick(f, dt, 1.6f)
            for (s in emitters.tick(f, dt, 1.6f, 0.3f, 0.6f)) {
                if (WaterMath.isCatchWell(s.r, s.g, s.b)) {
                    wells++
                    assertEquals("a drain carries no dye", 0f, s.r + s.g + s.b, 0f)
                } else {
                    drops++
                    assertTrue("a dyed splat must never read as a drain", maxOf(s.r, s.g, s.b) > 0f)
                }
            }
        }
        assertTrue("the stream must contain suction splats", wells > 0)
        assertTrue("the stream must contain dyed splats", drops > 0)
    }

    @Test
    fun theDisplayBaseHueIsPaletteIdentityOnly() {
        // WaterScene tints through the shared fluid helper, so Water, Fluid
        // and Curl Flow cannot drift apart - and that helper carries palette
        // identity ONLY. Hue shift reaches the pool through the composite's
        // uPostHue, so this pass must be the identity at every slider value;
        // applying it here too rotated the pool twice per unit.
        assertEquals(0.5f, FluidHue.base(0.5f), 1e-6f)
        assertEquals(0.9f, FluidHue.base(0.9f), 1e-6f)
        // A user-made palette can arrive outside [0,1): wrap, never clamp.
        for (base in floatArrayOf(-3.4f, -1f, 0f, 0.33f, 0.97f, 1f, 2.8f)) {
            val h = FluidHue.base(base)
            assertTrue("hue $h out of [0,1) for base=$base", h >= 0f && h < 1f)
        }
        // The span is the other half of the identity and stays scene-side.
        assertEquals(0.35f, FluidHue.span(0.5f, 0.7f), 1e-6f)
    }

    @Test
    fun brightnessIsAppliedOnceSoTheResponseStaysLinear() {
        // The display pass must contribute NOTHING to the grade: the
        // composite pass multiplies by brightness * intensity for every
        // scene that does not grade itself, and WATER is one of those. When
        // both passes applied it the response was quadratic and the pool
        // blew out at the top of either slider.
        assertEquals(1f, WaterMath.DISPLAY_BRIGHTNESS, 0f)
        assertEquals(1f, WaterMath.effectiveBrightness(1f, 1f), 1e-6f)
        // Linear in each slider independently: 2x in, 2x out (4x = the bug).
        val base = WaterMath.effectiveBrightness(0.6f, 0.9f)
        assertEquals(2f * base, WaterMath.effectiveBrightness(1.2f, 0.9f), 1e-5f)
        assertEquals(2f * base, WaterMath.effectiveBrightness(0.6f, 1.8f), 1e-5f)
        // Explicitly not the squared response the double-apply produced.
        val b = 1.8f
        val i = 1.6f
        val once = WaterMath.effectiveBrightness(b, i)
        assertEquals(b * i, once, 1e-5f)
        assertTrue("brightness must not be squared (got $once)", once < (b * i) * (b * i) - 1e-3f)
    }
}
