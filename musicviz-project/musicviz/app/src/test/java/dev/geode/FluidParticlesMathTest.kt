package dev.geode

import dev.geode.render.fluid.FluidMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F3 particle-layer headless checks (FLUID_SIM v2 section 8/15). */
class FluidParticlesMathTest {
    @Test
    fun stateSideIsSmallestSquareCoveringCount() {
        assertEquals(2, FluidMath.stateSide(1))
        assertEquals(2, FluidMath.stateSide(4))
        assertEquals(3, FluidMath.stateSide(5))
        assertEquals(32, FluidMath.stateSide(1000))
        assertEquals(256, FluidMath.stateSide(65536))
        assertEquals(257, FluidMath.stateSide(65537))
        // The square always holds at least the requested count.
        for (n in intArrayOf(1, 7, 100, 4097, 250_000)) {
            val s = FluidMath.stateSide(n)
            assertTrue(s * s >= n)
            assertTrue((s - 1) * (s - 1) < n || s == 2)
        }
    }
}
