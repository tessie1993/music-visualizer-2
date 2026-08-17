package dev.geode

import dev.geode.render.fluid.FluidQuality
import dev.geode.render.fluid.PerformanceMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F6 adaptive-quality headless checks (FLUID_SIM v2 sections 10/15). */
class FluidQualityTest {
    @Test
    fun tiersDegradeMonotonically() {
        val t = FluidQuality.TIERS
        for (i in 1 until t.size) {
            assertTrue(t[i].simRes <= t[i - 1].simRes)
            assertTrue(t[i].dyeRes <= t[i - 1].dyeRes)
            assertTrue(t[i].particleSide <= t[i - 1].particleSide)
            assertTrue(t[i].iterations <= t[i - 1].iterations)
        }
    }

    @Test
    fun effectiveIndexClampsAndOnlyLowers() {
        assertEquals(2, FluidQuality.effectiveIndex(2, 0))
        assertEquals(3, FluidQuality.effectiveIndex(2, 1))
        // Downgrades saturate at the lowest tier...
        assertEquals(FluidQuality.TIERS.size - 1, FluidQuality.effectiveIndex(2, 99))
        // ...and a negative "downgrade" can never upgrade.
        assertEquals(2, FluidQuality.effectiveIndex(2, -3))
        assertEquals(0, FluidQuality.effectiveIndex(-5, 0))
    }

    @Test
    fun monitorIgnoresASingleStall() {
        val m = PerformanceMonitor(targetFps = 50f, sustainSeconds = 2.5f)
        // Healthy 60 fps warm-up.
        repeat(120) { assertEquals(0, m.onFrame(1f / 60f)) }
        // One 500 ms GC-style stall (2 fps - discarded as non-signal).
        assertEquals(0, m.onFrame(0.5f))
        // Still healthy afterwards.
        repeat(120) { assertEquals(0, m.onFrame(1f / 60f)) }
    }

    @Test
    fun monitorFiresOnlyAfterSustainedDeficit() {
        val m = PerformanceMonitor(targetFps = 50f, sustainSeconds = 2.5f)
        repeat(60) { m.onFrame(1f / 60f) }
        // 45 fps: below target but must not fire before ~2.5 s of deficit.
        var fired = 0
        var seconds = 0f
        while (fired == 0 && seconds < 10f) {
            fired = m.onFrame(1f / 45f)
            seconds += 1f / 45f
        }
        assertEquals(1, fired) // mild deficit => one tier
        assertTrue("fired after ${seconds}s", seconds >= 2.0f && seconds < 6f)
    }

    @Test
    fun severeDeficitStepsTwoTiers() {
        val m = PerformanceMonitor(targetFps = 50f, sustainSeconds = 2.5f)
        var fired = 0
        var guard = 0
        while (fired == 0 && guard < 2000) {
            fired = m.onFrame(1f / 25f)
            guard++
        }
        assertEquals(2, fired) // 25 fps vs 50 target = severe
    }

    @Test
    fun monitorRecoversWhenFramesRecover() {
        val m = PerformanceMonitor(targetFps = 50f, sustainSeconds = 2.5f)
        repeat(60) { m.onFrame(1f / 60f) }
        // 1.5 s of deficit - not enough to fire...
        repeat(67) { assertEquals(0, m.onFrame(1f / 45f)) }
        // ...then recovery resets the deficit clock entirely.
        repeat(120) { m.onFrame(1f / 60f) }
        repeat(67) { assertEquals(0, m.onFrame(1f / 45f)) }
    }
}
