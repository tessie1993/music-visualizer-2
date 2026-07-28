package dev.musicviz

import dev.musicviz.render.fluid.FluidMath
import dev.musicviz.render.fluid.FluidQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Headless stability gate for vorticity confinement (the "fluid goes black
 * almost straight away" root cause). The GPU Gems form f = eps * h * omega
 * must (a) never amplify a local velocity difference by more than itself in
 * one frame at default settings, and (b) be independent of the grid
 * resolution - the pre-fix formulation (missing the h factor) violated both
 * by ~1/dx (64-142x), overflowed the half-float divergence grid to Inf and
 * turned the dye NaN/black within frames.
 */
class FluidVorticityMathTest {
    @Test
    fun confinementIsNonExplosiveAtDefaultCurlOnEveryTier() {
        val dt = 1f / 60f
        val velDiff = 12f // strong local shear at a vortex edge
        for (tier in FluidQuality.TIERS) {
            // Portrait phone: the long side is up to ~2.4x the tier res.
            val gridH = (tier.simRes * 2.4f).toInt()
            val dx = 2f / gridH
            val dv = FluidMath.confinementDeltaV(curlStrength = 30f, dx = dx, velDiff = velDiff, dt = dt)
            assertTrue(
                "tier ${tier.label}: dv=$dv must stay below the shear itself ($velDiff)",
                dv < velDiff,
            )
        }
    }

    @Test
    fun confinementIsGridResolutionIndependent() {
        val dt = 1f / 60f
        val velDiff = 8f
        val reference = FluidMath.confinementDeltaV(30f, dx = 2f / 64f, velDiff = velDiff, dt = dt)
        for (gridH in intArrayOf(96, 128, 192, 284, 512, 614)) {
            val dv = FluidMath.confinementDeltaV(30f, dx = 2f / gridH, velDiff = velDiff, dt = dt)
            assertEquals("gridH=$gridH", reference, dv, 1e-5f)
        }
    }

    @Test
    fun confinementMatchesUpstreamTuningScale() {
        // eps * h * (halfRdx * velDiff) collapses to eps * 0.5 * velDiff * dt,
        // exactly the upstream MIT sim's dimensionless force the 0..50 curl
        // range was tuned for.
        val dv = FluidMath.confinementDeltaV(curlStrength = 30f, dx = 2f / 192f, velDiff = 10f, dt = 1f / 60f)
        assertEquals(30f * 0.5f * 10f / 60f, dv, 1e-5f)
    }
}
