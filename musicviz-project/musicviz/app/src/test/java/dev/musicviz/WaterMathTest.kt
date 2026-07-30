package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.fluid.FluidChoreography
import dev.musicviz.render.fluid.FluidEmitters
import dev.musicviz.render.fluid.RippleMath
import dev.musicviz.render.fluid.WaterMath
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
 *  - "Hue shift" (colorShift) was read by no fluid scene at all. It now
 *    folds into the display pass's base hue exactly the way
 *    ParticleSceneBase/ShaderScene fold it, so one slider means one thing
 *    across every scene family.
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
    fun hueShiftMovesTheBaseHueAndWraps() {
        // Zero shift is the identity: existing presets keep their look.
        assertEquals(0.5f, WaterMath.baseHue(0.5f, 0f), 1e-6f)
        // The slider must actually move the hue (the reported bug).
        assertEquals(0.75f, WaterMath.baseHue(0.5f, 0.25f), 1e-6f)
        // Wrapping stays inside the hue circle in both directions.
        assertEquals(0.2f, WaterMath.baseHue(0.9f, 0.3f), 1e-6f)
        assertEquals(0.9f, WaterMath.baseHue(0.1f, -0.2f), 1e-6f)
        for (shift in floatArrayOf(-3.4f, -1f, 0f, 0.37f, 1f, 2.8f)) {
            for (base in floatArrayOf(0f, 0.33f, 0.97f)) {
                val h = WaterMath.baseHue(base, shift)
                assertTrue("hue $h out of [0,1) for base=$base shift=$shift", h >= 0f && h < 1f)
            }
        }
    }
}
