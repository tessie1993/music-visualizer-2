package dev.musicviz

import dev.musicviz.render.fluid.FluidMath
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Verifies the organic-motion report's defining property headless: the
 * curl-noise field is (numerically) divergence-free, so particle streams
 * swirl without clumping. Mirrors curl_field_frag.glsl exactly.
 */
class CurlFieldMathTest {
    @Test
    fun curlFieldIsDivergenceFreeAndAlive() {
        val h = 5e-3f
        var divSum = 0.0
        var speedSum = 0.0
        var samples = 0
        var t = 0.37f
        for (gy in -6..6) {
            for (gx in -9..9) {
                val x = gx * 0.17f
                val y = gy * 0.15f
                val (vxr, vyr) = FluidMath.curlVelocity(x + h, y, t, 1.4f, 1f)
                val (vxl, _) = FluidMath.curlVelocity(x - h, y, t, 1.4f, 1f)
                val (_, vyt) = FluidMath.curlVelocity(x, y + h, t, 1.4f, 1f)
                val (_, vyb) = FluidMath.curlVelocity(x, y - h, t, 1.4f, 1f)
                val div = (vxr - vxl) / (2f * h) + (vyt - vyb) / (2f * h)
                val (vx, vy) = FluidMath.curlVelocity(x, y, t, 1.4f, 1f)
                divSum += abs(div)
                speedSum += abs(vx) + abs(vy)
                samples++
                t += 0.011f
            }
        }
        val meanDiv = divSum / samples
        val meanSpeed = speedSum / samples
        assertTrue("field is dead (mean speed $meanSpeed)", meanSpeed > 0.05)
        // Divergence of a curl is zero analytically; numerically it must be
        // small relative to the field's own magnitude scale (1/length unit).
        assertTrue(
            "field not divergence-free: meanDiv=$meanDiv vs meanSpeed=$meanSpeed",
            meanDiv < meanSpeed * 0.6,
        )
    }
}
