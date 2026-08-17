package dev.geode

import dev.geode.render.fluid.FluidMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F4 look-chain headless checks (FLUID_SIM v2 sections 9/15). */
class FluidLookMathTest {
    @Test
    fun softKneeCurveMatchesReferenceFixture() {
        // Spec fixture: T=0.6, K=0.7 => curve ~ (0.1799, 0.8402, 0.5951).
        val (x, y, z) = FluidMath.bloomCurve(0.6f, 0.7f)
        assertEquals(0.1799f, x, 1e-3f)
        assertEquals(0.8402f, y, 1e-3f)
        assertEquals(0.5951f, z, 1e-3f)
    }

    @Test
    fun prefilterScaleMatchesAnalyticValue() {
        // br=1.0, T=0.6, K=0.7: rq = clamp(1-0.1799, 0, 0.8402) = 0.8201;
        // rq' = 0.5951*rq^2 = 0.40026; max(rq', 1-0.6) / 1 = 0.40026.
        val s = FluidMath.bloomPrefilterScale(1.0f, 0.6f, 0.7f)
        assertEquals(0.4003f, s, 1e-3f)
    }

    @Test
    fun prefilterPassesNothingBelowKneeAndEverythingFarAbove() {
        // Deep below the knee the scale collapses toward zero...
        assertTrue(FluidMath.bloomPrefilterScale(0.05f, 0.6f, 0.7f) < 0.02f)
        // ...and far above threshold it approaches (br - T) / br.
        val bright = FluidMath.bloomPrefilterScale(4f, 0.6f, 0.7f)
        assertEquals((4f - 0.6f) / 4f, bright, 1e-3f)
    }

    @Test
    fun dragStepIsFrameRateIndependent() {
        // Two 1/120s steps must land where one 1/60s step lands.
        val one = FluidMath.dragStep(0f, 1f, 0.5f, 1f / 60f)
        var two = FluidMath.dragStep(0f, 1f, 0.5f, 1f / 120f)
        two = FluidMath.dragStep(two, 1f, 0.5f, 1f / 120f)
        assertEquals(one, two, 1e-4f)
        // And at the reference rate the per-frame factor equals drag itself.
        assertEquals(0.5f, FluidMath.dragStep(0f, 1f, 0.5f, 1f / 60f), 1e-5f)
    }
}
