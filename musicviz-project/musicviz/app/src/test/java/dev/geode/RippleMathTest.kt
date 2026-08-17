package dev.geode

import dev.geode.render.fluid.RippleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Headless gate for the WATER heightfield math (the lockstep CPU mirror of
 * ripple_splat/ripple_update/water_display): a point drop must radiate as a
 * symmetric expanding ring, damping must monotonically drain energy, the
 * CFL clamp must keep the explicit scheme bounded at parameter extremes,
 * and the display refraction offset must never exceed its soft cap.
 */
class RippleMathTest {
    private val w = 65
    private val hgt = 65

    /** Grid with a Gaussian drop injected dead center (the splat mirror). */
    private fun droppedGrid(amp: Float = 1f): Pair<FloatArray, FloatArray> {
        val h = FloatArray(w * hgt)
        val v = FloatArray(w * hgt)
        val cx = w / 2
        val cy = hgt / 2
        for (y in 0 until hgt) {
            for (x in 0 until w) {
                val d = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat())
                h[y * w + x] = RippleMath.dropProfile(d, 3f, amp)
            }
        }
        return h to v
    }

    private fun step(
        h: FloatArray,
        v: FloatArray,
        c: Float,
        dtRaw: Float,
        dx: Float,
        damping: Float,
        times: Int = 1,
    ) {
        val dt = RippleMath.cflClampedDt(c, dtRaw, dx)
        repeat(times) { RippleMath.waveStep(h, v, w, hgt, c, dt, dx, damping) }
    }

    /** Ring radius: distance from center to the outermost cell above a threshold. */
    private fun ringRadius(h: FloatArray): Float {
        val cx = w / 2
        val cy = hgt / 2
        var r = 0f
        for (y in 0 until hgt) {
            for (x in 0 until w) {
                if (abs(h[y * w + x]) > 0.02f) {
                    val d = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toFloat())
                    if (d > r) r = d
                }
            }
        }
        return r
    }

    @Test
    fun pointDropRadiatesSymmetricallyAndRingExpands() {
        val (h, v) = droppedGrid()
        val dx = 2f / hgt
        val r0 = ringRadius(h)
        step(h, v, c = 1f, dtRaw = 1f / 60f, dx = dx, damping = 0.999f, times = 20)
        val r1 = ringRadius(h)
        step(h, v, c = 1f, dtRaw = 1f / 60f, dx = dx, damping = 0.999f, times = 20)
        val r2 = ringRadius(h)
        assertTrue("ring must expand: r0=$r0 r1=$r1 r2=$r2", r1 > r0 && r2 > r1)
        // 4-fold symmetry: a centered drop on a symmetric grid must stay
        // symmetric under the (isotropic) 5-point laplacian.
        val cx = w / 2
        val cy = hgt / 2
        for (d in 1 until w / 2) {
            val right = h[cy * w + (cx + d)]
            val left = h[cy * w + (cx - d)]
            val up = h[(cy + d) * w + cx]
            val down = h[(cy - d) * w + cx]
            assertEquals("left/right asymmetry at d=$d", right, left, 1e-4f)
            assertEquals("up/down asymmetry at d=$d", up, down, 1e-4f)
            assertEquals("axis asymmetry at d=$d", right, up, 1e-4f)
        }
    }

    /**
     * Discrete wave energy: kinetic (v^2) + potential (c^2 |grad h|^2 /
     * dx^2). The symplectic update nearly conserves it undamped, so the
     * per-checkpoint damping factor (0.96^10 ~ 0.66) dominates and the
     * total must fall monotonically.
     */
    private fun totalEnergy(
        h: FloatArray,
        v: FloatArray,
        c: Float,
        dx: Float,
    ): Float {
        var e = 0f
        for (i in v.indices) e += v[i] * v[i]
        val k = (c / dx) * (c / dx)
        for (y in 0 until hgt) {
            for (x in 0 until w) {
                val i = y * w + x
                if (x < w - 1) {
                    val d = h[i + 1] - h[i]
                    e += k * d * d
                }
                if (y < hgt - 1) {
                    val d = h[i + w] - h[i]
                    e += k * d * d
                }
            }
        }
        return e
    }

    @Test
    fun dampingMonotonicallyDecaysEnergy() {
        val (h, v) = droppedGrid()
        val dx = 2f / hgt
        // Let the transient settle into a travelling wave first.
        step(h, v, c = 1f, dtRaw = 1f / 60f, dx = dx, damping = 0.96f, times = 10)
        var prev = totalEnergy(h, v, 1f, dx)
        repeat(12) {
            step(h, v, c = 1f, dtRaw = 1f / 60f, dx = dx, damping = 0.96f, times = 10)
            val e = totalEnergy(h, v, 1f, dx)
            assertTrue("energy must decay: $e !< $prev", e < prev)
            prev = e
        }
    }

    @Test
    fun cflClampedStepStaysBoundedAtWaveSpeedExtremes() {
        // waterWaveSpeed=2 extreme (scene scale 1.2 -> c=2.4) on a fine grid:
        // raw dt=1/60 wildly violates CFL; the clamp must keep it stable.
        val (h, v) = droppedGrid(amp = 2f)
        val dx = 2f / hgt
        val c = 2.4f
        val dt = RippleMath.cflClampedDt(c, 1f / 60f, dx)
        assertTrue("clamp must respect CFL", c * dt / dx <= 0.7f + 1e-5f)
        var maxH = 0f
        repeat(1000) {
            RippleMath.waveStep(h, v, w, hgt, c, dt, dx, 0.999f)
            for (x in h) if (abs(x) > maxH) maxH = abs(x)
        }
        assertTrue("max|h|=$maxH must stay bounded", maxH.isFinite() && maxH <= RippleMath.MAX_HEIGHT)
        // And well below the clamp rail: a stable scheme never grows past
        // the initial amplitude by more than a small dispersion factor.
        assertTrue("max|h|=$maxH suggests instability", maxH < 4f)
    }

    @Test
    fun refractionOffsetNeverExceedsCap() {
        for (mag in floatArrayOf(0.01f, 0.5f, 8f, 100f, 10000f)) {
            val (ox, oy) = RippleMath.refractionOffset(-mag, mag, mag * 0.5f, -mag * 0.5f, strength = 1f)
            val len = sqrt(ox * ox + oy * oy)
            assertTrue("offset $len must stay under cap for mag=$mag", len < RippleMath.REFRACTION_CAP)
        }
        // Gentle gradients pass nearly unchanged (soft limit ~identity).
        val (ox, oy) = RippleMath.refractionOffset(0f, 0.004f, 0f, 0f, strength = 1f)
        assertEquals(0f, oy, 1e-6f)
        assertTrue("gentle offset should be near-identity", ox > 0.0035f && ox < 0.004f)
    }
}
