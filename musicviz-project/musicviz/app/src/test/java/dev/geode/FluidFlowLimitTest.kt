package dev.geode

import dev.geode.render.fluid.FluidMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * fluidWarp soft-limit gate: the composite displacement scale (0.015 UV per
 * unit) was tuned for a +-6 field, but emitters legitimately reach ~36. The
 * soft limit must bound ANY field below 6 (max ~0.09 UV warp - a bend, not
 * full-screen flashing) while staying ~identity for gentle fields.
 */
class FluidFlowLimitTest {
    @Test
    fun extremeFieldsStayBoundedBelowSix() {
        for (mag in floatArrayOf(6f, 12f, 36f, 108f, 1000f)) {
            val (x, y) = FluidMath.softLimitFlow(mag, 0f)
            assertTrue("mag=$mag -> $x", sqrt(x * x + y * y) < 6f)
        }
    }

    @Test
    fun gentleFieldsPassNearlyUnchanged() {
        val (x, y) = FluidMath.softLimitFlow(0.5f, 0.5f)
        // |v| ~ 0.707: k = 6/6.707 ~ 0.895 - within ~11% of identity.
        assertEquals(0.5f * (6f / (6f + sqrt(0.5f))), x, 1e-5f)
        assertEquals(x, y, 1e-6f)
        assertTrue(x > 0.44f)
    }

    @Test
    fun terminalSpeedCapBoundsAnyFieldAndIsIdentityBelowCap() {
        // Below the cap: exact identity (no character change in normal play).
        val (ix, iy) = FluidMath.terminalSpeedCap(3f, 4f) // |v| = 5
        assertEquals(3f, ix, 1e-6f)
        assertEquals(4f, iy, 1e-6f)
        // Above the cap: magnitude pinned to 12, direction preserved - the
        // energy brake that stops the runaway "dye advects off-grid" blackout.
        for (mag in floatArrayOf(13f, 40f, 830f, 1000f)) {
            val (x, y) = FluidMath.terminalSpeedCap(mag * 0.6f, mag * 0.8f)
            assertEquals(12f, sqrt(x * x + y * y), 1e-3f)
            assertEquals(0.6f / 0.8f, x / y, 1e-4f)
        }
    }
}
