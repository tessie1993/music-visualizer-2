package dev.musicviz

import dev.musicviz.render.FlashBudget
import dev.musicviz.render.VisualSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The beat flash is the one full-frame luminance event whose RATE nothing
 * downstream controls.
 *
 * `strobeHz` caps the strobe's own oscillator and `beatMinIntervalMs` asks the
 * analyzer for a floor between beats, but neither binds at the point the
 * uniform is uploaded: a double-time detection, a tempo ramp, a cached beat
 * grid re-decided at different settings, or simply a 200 BPM track all reach
 * the composite as more flashes per second than the analyzer was asked for.
 * This is the last gate before the frame, and it counts what is actually about
 * to be drawn.
 *
 * Deterministic by construction - time is an argument, never a clock - so the
 * live path and the export path get the same answer for the same beat grid,
 * which is what MASTER_PLAN §10.3 requires of anything that shapes a frame.
 */
class FlashBudgetTest {
    private val threshold = FlashBudget.RISK_THRESHOLD

    private fun budget() = FlashBudget()

    /** Drives [steps] impulses at [hz], returning the gain applied to each. */
    private fun run(
        budget: FlashBudget,
        hz: Float,
        steps: Int,
        impulse: Float = 0.8f,
    ): List<Float> {
        val period = 1f / hz
        return (0 until steps).map { i ->
            val t = i * period
            // Each event is a rising edge followed by a return to rest, which
            // is what a beat flash actually is - the decay between beats is
            // what makes the next one a new event rather than a plateau.
            budget.gainFor(t, 0f)
            budget.gainFor(t + period * 0.25f, impulse)
        }
    }

    @Test
    fun `a slow beat is untouched`() {
        val gains = run(budget(), hz = 2f, steps = 20)
        assertTrue("2 Hz is inside the guidance and must pass through", gains.all { it == 1f })
    }

    @Test
    fun `a fast beat is held to the budget`() {
        val gains = run(budget(), hz = 8f, steps = 40)
        // The first three in any second pass; the rest are pushed below the
        // risk threshold. Count what a viewer would perceive as a flash.
        val perceived = gains.count { it * 0.8f > threshold }
        assertTrue(
            "8 Hz produced $perceived perceived flashes over 5 seconds",
            perceived <= (5 * VisualSafety.WCAG_FLASHES_PER_SECOND).toInt() + 1,
        )
    }

    @Test
    fun `suppression rolls off rather than cutting to black`() {
        val gains = run(budget(), hz = 12f, steps = 24)
        assertTrue("a hard cut to zero is itself a full-frame change", gains.all { it > 0f })
        assertTrue("something must actually be suppressed at 12 Hz", gains.any { it < 1f })
    }

    @Test
    fun `an impulse too small to be a flash never spends the budget`() {
        val gains = run(budget(), hz = 12f, steps = 24, impulse = threshold * 0.5f)
        assertTrue("sub-threshold impulses are not flashes and must not be counted", gains.all { it == 1f })
    }

    @Test
    fun `the budget recovers once the fast passage ends`() {
        val b = budget()
        run(b, hz = 10f, steps = 20)
        // Two seconds of quiet, then a single beat: the window has emptied.
        b.gainFor(4f, 0f)
        assertEquals(1f, b.gainFor(4.1f, 0.8f), 0f)
    }

    @Test
    fun `a plateau is one flash, not one per frame`() {
        val b = budget()
        // 60 frames of a held impulse with no return to rest. Counting frames
        // instead of edges would spend the whole budget in 50 ms and suppress
        // a scene that is simply bright.
        val gains = (0 until 60).map { b.gainFor(it * (1f / 60f), 0.8f) }
        assertTrue("a held level is not a sequence of flashes", gains.all { it == 1f })
    }

    @Test
    fun `time running backwards restarts the window instead of corrupting it`() {
        val b = budget()
        run(b, hz = 10f, steps = 20)
        // The renderer wraps uTime at TIME_WRAP_SEC, so the clock this reads
        // does jump backwards in normal operation. Treated as a new session
        // rather than as a second's worth of negative-age events.
        b.gainFor(0f, 0f)
        assertEquals(1f, b.gainFor(0.05f, 0.8f), 0f)
    }

    @Test
    fun `resetting clears the history`() {
        val b = budget()
        run(b, hz = 10f, steps = 20)
        b.reset()
        assertEquals(1f, b.gainFor(2f, 0.8f), 0f)
    }

    @Test
    fun `two budgets fed the same beat grid agree exactly`() {
        // Live and export are two instances driven by the same feature
        // timeline. If they ever disagreed, the exported file would flash
        // differently from the screen it was rendered against.
        val live = budget()
        val export = budget()
        val times = listOf(0f, 0.1f, 0.22f, 0.31f, 0.44f, 0.61f, 0.75f, 0.9f, 1.05f, 1.2f)
        for (t in times) {
            assertEquals(live.gainFor(t, 0f), export.gainFor(t, 0f), 0f)
            assertEquals(live.gainFor(t + 0.01f, 0.9f), export.gainFor(t + 0.01f, 0.9f), 0f)
        }
    }

    @Test
    fun `every path that uploads the flash goes through the budget`() {
        // The limiter is worth nothing if a later edit adds a third compositor
        // or drops the gain from one of the two. Both are string-matched here
        // because there is no runtime seam to assert on: the value goes
        // straight into a GL uniform.
        val bare =
            listOf(
                "app/src/main/java/dev/musicviz/render/VisualizerRenderer.kt",
                "app/src/main/java/dev/musicviz/export/FxCompositor.kt",
            ).flatMap { path ->
                java.io
                    .File(ParamSurface.moduleRoot, path)
                    .readLines()
                    .filter { it.contains("\"uPostFlash\"") }
                    .filterNot { it.contains("flashGain(") }
                    .map { "$path: ${it.trim()}" }
            }
        assertEquals("a full-frame flash uploaded without the rate budget", emptyList<String>(), bare)
    }

    @Test
    fun `the impulse estimate uses the shader's own coefficient`() {
        // The budget judges what the frame will do, not what a slider says.
        // uPostFlash reaches the screen as flash * beat * FLASH_SHADER_DEPTH,
        // so anything else here would be measuring the wrong quantity.
        assertEquals(
            0.5f * 0.8f * VisualSafety.FLASH_SHADER_DEPTH,
            VisualSafety.flashImpulse(flash = 0.5f, beatImpulse = 0.8f),
            1e-6f,
        )
        assertEquals(0f, VisualSafety.flashImpulse(flash = 0.5f, beatImpulse = 0f), 0f)
    }
}
