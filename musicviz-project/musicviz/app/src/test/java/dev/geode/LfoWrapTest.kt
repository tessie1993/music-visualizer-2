package dev.geode

import dev.geode.render.LfoConfig
import dev.geode.render.LfoEngine
import dev.geode.render.LfoTarget
import dev.geode.render.LfoWave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The S&H LFO's cycle accumulator and the wrap that bounds it.
 *
 * `LfoEngine.totalPhase` exists so the sample-and-hold wave draws one new
 * value per full cycle. Like `VisualizerRenderer.timeSeconds` (see
 * [RenderClockWrapTest]) it lives for the life of the process, and a live
 * wallpaper renders continuously: unwrapped, at the 30 Hz rate ceiling it
 * crosses 2^23 after ~3 days of uptime and float32 absorption first thins,
 * then stops, the sample cadence - the hold becomes permanent. The wrap that
 * fixes this must itself be invisible: the S&H may resample exactly once per
 * cycle boundary, including the boundary the wrap lands on.
 */
class LfoWrapTest {
    private companion object {
        /**
         * 1 Hz stepped 0.25 s per tick: rate, dt and their product are exact
         * in binary, so the engine's phase is exactly k * 0.25 and cycle
         * boundaries land exactly on every 4th tick - the test knows from
         * the outside precisely when a resample is due.
         */
        const val RATE_HZ = 1f
        const val DT = 0.25f
        const val TICKS_PER_CYCLE = 4
    }

    private fun engine(rateHz: Float = RATE_HZ): LfoEngine =
        LfoEngine().apply {
            configs =
                listOf(
                    LfoConfig(enabled = true, target = LfoTarget.ZOOM, wave = LfoWave.RANDOM, rateHz = rateHz, depth = 1f),
                    LfoConfig(),
                    LfoConfig(),
                )
        }

    @Test
    fun `sample-and-hold resamples exactly once per cycle boundary, across the wrap`() {
        val e = engine()
        val wrapTicks = LfoEngine.SH_PHASE_WRAP.toInt() * TICKS_PER_CYCLE
        var held = e.tick(DT, 0f)[0] // First tick draws the initial sample.
        var boundaries = 0
        var resampled = 0
        for (k in 2..3 * wrapTicks) {
            val v = e.tick(DT, 0f)[0]
            if (k % TICKS_PER_CYCLE == 0) {
                boundaries++
                if (v != held) resampled++
            } else {
                // A mid-cycle change is a retrigger - what a wrap that lands
                // between cycle boundaries would cause every time round.
                assertEquals("S&H resampled mid-cycle at tick $k", held, v, 0f)
            }
            held = v
        }
        // Two consecutive Math.random() draws can collide, so tolerate the
        // odd boundary whose new sample looks unchanged; a wrap that ate
        // transitions would lose one per period, not one in a million.
        assertTrue("only $resampled of $boundaries boundaries resampled", resampled >= boundaries - 2)
    }

    @Test
    fun `weeks of continuous uptime cannot freeze the sample-and-hold`() {
        // Coarse steps: [LfoEngine.tick] takes dt as given, so hour-long
        // ticks walk the accumulator through the total phase a month of
        // frames would. Unwrapped it ends near 7.8e7, where a real frame's
        // 0.5 advance (30 Hz / 60 fps) is far below the float32 ULP of 8:
        // floor() can never change again and one value is held forever.
        val e = engine(rateHz = 30f)
        repeat(30 * 24) { e.tick(3600f, 0f) }
        // Back at a real frame cadence the S&H must still produce values.
        var held = e.tick(1f / 60f, 0f)[0]
        var changes = 0
        repeat(120) {
            val v = e.tick(1f / 60f, 0f)[0]
            if (v != held) changes++
            held = v
        }
        // 30 Hz at 60 fps is a boundary every other tick: ~60 resamples.
        assertTrue("S&H is frozen after simulated uptime ($changes changes in 2 s)", changes >= 20)
    }

    @Test
    fun `the wrap period is a whole number of cycles with absorption headroom`() {
        val w = LfoEngine.SH_PHASE_WRAP
        // A fractional period would put the wrap mid-cycle: that boundary
        // would fire a spurious extra sample every time it came round.
        assertEquals("wrap must land exactly on a cycle boundary", 0f, w % 1f, 0f)
        // The slowest advance the renderer can produce is 0.01 Hz at its
        // 1 ms dt floor = 1e-5 per tick; it must clear the absorption
        // threshold (ULP / 2) everywhere below the wrap, or the accumulator
        // stalls just beneath it - the original bug relocated.
        assertTrue("accumulator can stall below the wrap", Math.ulp(w) / 2f < 0.01f * 0.001f)
        // And one tick (30 Hz at the renderer's 0.1 s dt clamp = 3) must
        // not come close to lapping the whole period.
        assertTrue("wrap period $w leaves no headroom over a single tick", w >= 3f * 4f)
    }
}
