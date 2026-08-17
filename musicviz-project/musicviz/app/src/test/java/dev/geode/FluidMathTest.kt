package dev.geode

import dev.geode.render.fluid.FluidBuffers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Headless validation of the fluid math per FLUID_SIM v2 section 15: the
 * aspect-correct resolution helper, a CPU reference of the projection step
 * (divergence -> Jacobi with alpha=-dx^2 -> gradient subtract) proving the
 * ported formulation actually kills divergence, and the capsule
 * distance-to-segment contract including its degenerate-segment guard.
 */
class FluidMathTest {
    @Test
    fun resolutionGivesShortSideResAndScalesLongSide() {
        val (lw, lh) = FluidBuffers.resolution(128, 1920, 1080)
        assertEquals(128, lh)
        assertEquals(Math.round(128 * 1920f / 1080f), lw)
        val (pw, ph) = FluidBuffers.resolution(128, 1080, 1920)
        assertEquals(128, pw)
        assertTrue(ph > 128)
    }

    @Test
    fun jacobiProjectionReducesDivergenceOver90Percent() {
        // One projection with central-difference div/grad and the compact
        // 5-point Jacobi Laplacian removes only PART of the divergence:
        // D(central) o G(central) is the wide (2dx) Laplacian, not the
        // compact one being solved, so a single pass leaves a fixed
        // residual fraction. The shipped sim (like GPU Gems / Pavel's)
        // converges by projecting EVERY FRAME with a warm-started, damped
        // pressure field - so that is what this test validates: a smooth
        // seed field, 6 simulated frames of (damp 0.8 -> Jacobi x20 ->
        // subtract), ending below 10% of the initial max |divergence|.
        val n = 8
        val dx = 2f / n
        val alpha = -dx * dx
        val halfRdx = 0.5f / dx
        val vx =
            Array(n) { y ->
                FloatArray(n) { x ->
                    (
                        kotlin.math.sin(2.0 * Math.PI * x / n) * kotlin.math.cos(Math.PI * y / n) +
                            0.5 * kotlin.math.sin(Math.PI * (x + y) / n)
                    ).toFloat()
                }
            }
        val vy =
            Array(n) { y ->
                FloatArray(n) { x ->
                    (
                        kotlin.math.cos(Math.PI * x / n) * kotlin.math.sin(2.0 * Math.PI * y / n) -
                            0.4 * kotlin.math.cos(Math.PI * (x - y) / n)
                    ).toFloat()
                }
            }

        fun clampI(i: Int) = i.coerceIn(0, n - 1)

        fun divergence(): Array<FloatArray> =
            Array(n) { y ->
                FloatArray(n) { x ->
                    val r = if (x + 1 < n) vx[y][x + 1] else -vx[y][x]
                    val l = if (x - 1 >= 0) vx[y][x - 1] else -vx[y][x]
                    val t = if (y + 1 < n) vy[y + 1][x] else -vy[y][x]
                    val b = if (y - 1 >= 0) vy[y - 1][x] else -vy[y][x]
                    halfRdx * ((r - l) + (t - b))
                }
            }

        val maxBefore = divergence().maxOf { row -> row.maxOf { abs(it) } }
        assertTrue("seed field should be divergent", maxBefore > 0.5f)

        var p = Array(n) { FloatArray(n) }
        repeat(6) {
            val d0 = divergence()
            p = Array(n) { y -> FloatArray(n) { x -> p[y][x] * 0.8f } }
            repeat(20) {
                val next = Array(n) { FloatArray(n) }
                for (y in 0 until n) {
                    for (x in 0 until n) {
                        next[y][x] = (
                            p[y][clampI(x - 1)] + p[y][clampI(x + 1)] +
                                p[clampI(y - 1)][x] + p[clampI(y + 1)][x] + alpha * d0[y][x]
                        ) * 0.25f
                    }
                }
                p = next
            }
            for (y in 0 until n) {
                for (x in 0 until n) {
                    vx[y][x] -= halfRdx * (p[y][clampI(x + 1)] - p[y][clampI(x - 1)])
                    vy[y][x] -= halfRdx * (p[clampI(y + 1)][x] - p[clampI(y - 1)][x])
                }
            }
        }
        val maxAfter = divergence().maxOf { row -> row.maxOf { abs(it) } }
        assertTrue(
            "warm-started projection over 6 frames should reduce max |divergence| " +
                "by >90% (before=$maxBefore after=$maxAfter)",
            maxAfter < maxBefore * 0.10f,
        )
    }

    @Test
    fun segmentDistanceHandlesDegenerateSegmentAndTapers() {
        // Kotlin mirror of fluid_splat_frag's segDist contract.
        fun segDist(
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
            px: Float,
            py: Float,
        ): Pair<Float, Float> {
            val abx = bx - ax
            val aby = by - ay
            val len2 = abx * abx + aby * aby
            if (len2 < 1e-8f) {
                return sqrt((px - ax) * (px - ax) + (py - ay) * (py - ay)) to 0f
            }
            val fp = (((px - ax) * abx + (py - ay) * aby) / len2).coerceIn(0f, 1f)
            val cx = ax + abx * fp
            val cy = ay + aby * fp
            return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy)) to fp
        }

        // Degenerate: zero-length segment behaves as a point, fp = 0, no NaN.
        val (dDeg, fpDeg) = segDist(0.3f, 0.3f, 0.3f, 0.3f, 0.6f, 0.7f)
        assertTrue(dDeg.isFinite())
        assertEquals(0f, fpDeg, 1e-6f)
        assertEquals(0.5f, dDeg, 1e-4f)

        // Midpoint of a horizontal segment: distance is the vertical offset.
        val (dMid, fpMid) = segDist(-1f, 0f, 1f, 0f, 0f, 0.25f)
        assertEquals(0.25f, dMid, 1e-5f)
        assertEquals(0.5f, fpMid, 1e-5f)

        // Taper makes the trailing end (fp=0) stronger than the head-side falloff shape:
        val r = 0.2f
        val head = exp(-dMid / r) * (1f - 1.0f * 0.6f)
        val tail = exp(-dMid / r) * (1f - 0.0f * 0.6f)
        assertTrue(tail > head)
    }

    @Test
    fun dragInertiaConvergesGeometricallyTowardFlow() {
        var v = 0f
        val flow = 2f
        val drag = 0.5f
        var prevGap = flow - v
        repeat(12) {
            v =
                dev.geode.render.fluid.FluidMath
                    .dragStep(v, flow, drag)
            val gap = flow - v
            org.junit.Assert.assertEquals(prevGap * (1f - drag), gap, 1e-5f)
            prevGap = gap
        }
        org.junit.Assert.assertTrue(kotlin.math.abs(flow - v) < 1e-3f)
        // drag = 1 is the pure tracer: matches the flow in one step.
        org.junit.Assert.assertEquals(
            flow,
            dev.geode.render.fluid.FluidMath
                .dragStep(0f, flow, 1f),
            1e-6f,
        )
    }
}
