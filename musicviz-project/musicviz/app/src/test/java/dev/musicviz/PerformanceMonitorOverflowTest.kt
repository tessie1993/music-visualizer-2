package dev.musicviz

import dev.musicviz.render.fluid.PerformanceMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The warm-up counter must not run away.
 *
 * [PerformanceMonitor.onFrame] counts every accepted frame for the life of
 * the sim, and [FluidQualityTest]'s scenarios all finish inside a few
 * hundred. A live wallpaper does not: an unbounded Int count overflows to
 * negative after about fourteen months at 60 fps, after which averageFps
 * reads "not warmed up yet" forever and the downgrade latch is silently dead
 * until reset() - the runaway-counter failure DrumChannelsTest pins for the
 * beat gates. Two billion frames is not steppable in a test, so this pins
 * the clamp itself and the behaviour it must preserve.
 */
class PerformanceMonitorOverflowTest {
    /** The private frame count, read back by reflection like [ParticleGatingTest] reads SceneIds. */
    private fun countOf(m: PerformanceMonitor): Int =
        PerformanceMonitor::class.java.getDeclaredField("count").let {
            it.isAccessible = true
            it.getInt(m)
        }

    @Test
    fun theFrameCountIsBoundedByTheWindow() {
        // Past windowSize the exact count never matters (averageFps takes
        // minOf(count, windowSize)), so the bound changes nothing reachable -
        // it only removes the overflow.
        val m = PerformanceMonitor(targetFps = 50f, sustainSeconds = 2.5f, windowSize = 30)
        repeat(200_000) { m.onFrame(1f / 60f) }
        assertTrue("count ran to ${countOf(m)} - it will overflow on a long-lived wallpaper", countOf(m) <= 30)
    }

    @Test
    fun uptimeBeforeASlowdownDoesNotChangeTheVerdict() {
        // The behaviour the clamp must preserve: how long the monitor has
        // been healthy must not change whether, or how hard, it fires once
        // frames genuinely degrade.
        fun severityAfter(healthyFrames: Int): Int {
            val m = PerformanceMonitor(targetFps = 50f, sustainSeconds = 2.5f)
            repeat(healthyFrames) { assertEquals(0, m.onFrame(1f / 60f)) }
            var fired = 0
            var guard = 0
            while (fired == 0 && guard < 2000) {
                fired = m.onFrame(1f / 25f)
                guard++
            }
            return fired
        }
        assertEquals(severityAfter(200), severityAfter(200_000))
        assertEquals("25 fps against a 50 fps target must still read as severe", 2, severityAfter(200_000))
    }
}
