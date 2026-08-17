package dev.geode

import dev.geode.render.fluid.RippleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Headless gate for the WATER defect "it only ever adds more ripples, it
 * never removes them".
 *
 * The wave update damped VELOCITY only. The discrete Laplacian sums to zero
 * over a Neumann grid, so that step conserves the MEAN of the height field
 * exactly - while every drop injects a strictly positive Gaussian. Under a
 * track the pool therefore rose monotonically until it pinned against
 * [RippleMath.MAX_HEIGHT], at which point the gradient the display pass
 * shades and refracts from went flat and the style froze into a saturated
 * sheet with the drops still visibly piling on.
 *
 * [waterWithoutTheDrainFillsUpForever] reproduces exactly that with
 * `heightDecay = 1` (the old behaviour), and the rest pin the fix: with the
 * drain the same drop stream settles, a stopped stream returns to flat, and
 * ripple lifetime does not depend on frame rate or substep count.
 */
class RippleDrainTest {
    private val w = 48
    private val h = 32
    private val dx = 2f / h
    private val c = 1.2f
    private val damping = 0.985f

    private class Pool(
        val w: Int,
        val h: Int,
    ) {
        val height = FloatArray(w * h)
        val velocity = FloatArray(w * h)

        val mean: Float get() = height.sum() / height.size
        val peak: Float get() = height.maxOf { abs(it) }
    }

    /** One Gaussian drop at the grid centre, as ripple_splat_frag draws it. */
    private fun drop(
        pool: Pool,
        amp: Float = 0.6f,
        radius: Float = 0.12f,
    ) {
        for (y in 0 until pool.h) {
            for (x in 0 until pool.w) {
                val sx = (x - pool.w / 2f) * dx
                val sy = (y - pool.h / 2f) * dx
                val dist = kotlin.math.sqrt(sx * sx + sy * sy)
                pool.height[y * pool.w + x] += RippleMath.dropProfile(dist, radius, amp)
            }
        }
    }

    private fun step(
        pool: Pool,
        dt: Float,
        heightDecay: Float,
    ) = RippleMath.waveStep(pool.height, pool.velocity, pool.w, pool.h, c, dt, dx, damping, heightDecay)

    /**
     * Runs [seconds] of playback at [fps], dropping every [dropEveryFrames]
     * frames, and returns the pool. [heightDecay] of 1 is the pre-fix sim.
     */
    private fun run(
        seconds: Float,
        fps: Int = 60,
        dropEveryFrames: Int = 12,
        drops: Boolean = true,
        heightDecay: Float? = null,
        pool: Pool = Pool(w, h),
    ): Pool {
        val dt = 1f / fps
        val decay = heightDecay ?: RippleMath.heightDecayPerSubstep(damping, dt)
        val frames = (seconds * fps).toInt()
        for (frame in 0 until frames) {
            if (drops && frame % dropEveryFrames == 0) drop(pool)
            step(pool, dt, decay)
        }
        return pool
    }

    @Test
    fun waterWithoutTheDrainFillsUpForever() {
        // The regression, made explicit: velocity damping alone leaves the
        // injected volume in the pool, so the level only ever climbs.
        val short = run(seconds = 4f, heightDecay = 1f).mean
        val long = run(seconds = 12f, heightDecay = 1f).mean
        assertTrue("the un-drained pool must accumulate ($short)", short > 0.05f)
        assertTrue("three times the drops must leave roughly three times the water", long > short * 2.5f)
    }

    @Test
    fun theDrainedPoolSettlesInsteadOfClimbing() {
        val short = run(seconds = 4f).mean
        val long = run(seconds = 12f).mean
        val longer = run(seconds = 36f).mean
        assertTrue("the drained pool must still hold water while drops keep landing ($short)", short > 1e-4f)
        // Steady state, not accumulation. The level is a decaying series, so
        // it is still creeping up at 12 s; what matters is that it CONVERGES -
        // by 36 s it has stopped moving, where the un-drained sim was still
        // tracking the drop count one for one.
        assertTrue("the pool is still filling up fast ($long vs $short)", long < short * 1.5f)
        assertTrue("the level never settles ($longer vs $long)", longer < long * 1.1f)
        // Same interval, both sims: the drain has to be the difference.
        val undrainedGrowth = run(seconds = 36f, heightDecay = 1f).mean / run(seconds = 12f, heightDecay = 1f).mean
        assertTrue("the un-drained sim was supposed to keep climbing ($undrainedGrowth)", undrainedGrowth > 2.5f)
    }

    @Test
    fun aStoppedDropStreamReturnsTheSurfaceToFlat() {
        // "Never removes them": after the music stops, the pool has to become
        // still water again rather than keeping every ring it was given.
        val stirred = run(seconds = 4f)
        val busy = stirred.peak
        assertTrue("the test pool never got stirred ($busy)", busy > 0.05f)
        val calm = run(seconds = 20f, drops = false, pool = stirred)
        assertTrue("ripples outlive the music (peak $${calm.peak} vs $busy)", calm.peak < busy * 0.05f)
        assertTrue("the water level never drains (${calm.mean})", abs(calm.mean) < 1e-3f)
    }

    @Test
    fun theSurfaceNeverPinsAgainstTheHeightRail() {
        // The visible failure was saturation: once large areas clamp at
        // MAX_HEIGHT the gradient is zero and the style stops responding.
        val hammered = run(seconds = 30f, dropEveryFrames = 3)
        assertTrue(
            "the surface saturated against the +/-${RippleMath.MAX_HEIGHT} rail (peak ${hammered.peak})",
            hammered.peak < RippleMath.MAX_HEIGHT * 0.5f,
        )
    }

    @Test
    fun ripplesLiveTheSameLengthOfTimeAtAnyFrameRate() {
        // The decay is renormalized per substep exactly like the velocity
        // damping, so a 120 Hz panel must not halve the pool's memory.
        val at60 = run(seconds = 6f, fps = 60).mean
        val at120 = run(seconds = 6f, fps = 120, dropEveryFrames = 24).mean
        assertEquals("ripple lifetime changed with frame rate", at60, at120, at60 * 0.2f + 1e-4f)
    }

    @Test
    fun theDrainIsGentlerThanTheVelocityDamping() {
        // Damping means "how fast the pool calms down". Draining the height as
        // hard as the velocity would kill the ringing that makes it water, so
        // the height decay is a documented FRACTION of the slider's loss.
        val dt = 1f / 60f
        for (slider in floatArrayOf(0.9f, 0.95f, 0.985f, 0.999f)) {
            val drain = RippleMath.heightDecayPerSubstep(slider, dt)
            assertTrue("height decay $drain must be a real loss at damping $slider", drain < 1f)
            assertTrue("height decay $drain must be gentler than damping $slider", drain > slider)
        }
        // A perfectly lossless slider setting leaves the height untouched too,
        // so "no damping at all" stays a coherent (if unreachable) request.
        assertEquals(1f, RippleMath.heightDecayPerSubstep(1f, dt), 1e-6f)
    }
}
