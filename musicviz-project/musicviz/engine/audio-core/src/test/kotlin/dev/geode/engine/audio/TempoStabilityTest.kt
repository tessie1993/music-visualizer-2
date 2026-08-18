package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the tempo estimate has STAYED PUT — the signal a scene checks
 * before committing to tempo-synced choreography over the next bars.
 *
 * [TempoTracker.confidence] is per-frame clarity: how much the winner stands
 * above the field right now. A bank flapping between octaves can be clear on
 * every single frame and still useless as a grid. Stability is the missing
 * axis: low while the estimate moves, earned while it holds.
 */
class TempoStabilityTest {
    private val hopRateHz = 100f

    private fun node() = TempoStability(hopRateHz)

    private fun run(
        node: TempoStability,
        bpm: Float,
        frames: Int,
    ) {
        repeat(frames) { node.step(bpm) }
    }

    @Test
    fun `a held tempo reads stable`() {
        val node = node()
        run(node, 120f, 800)
        assertTrue("held tempo read ${node.value}", node.value > 0.8f)
    }

    @Test
    fun `no tempo reads zero`() {
        val node = node()
        run(node, 0f, 400)
        assertEquals(0f, node.value, 1e-6f)
    }

    @Test
    fun `stability is earned, not assumed`() {
        val node = node()
        run(node, 120f, 10)
        assertTrue("read ${node.value} after 100 ms", node.value < 0.5f)
    }

    @Test
    fun `octave flapping reads unstable`() {
        val node = node()
        repeat(800) { i -> node.step(if (i % 2 == 0) 60f else 120f) }
        assertTrue("flapping read ${node.value}", node.value < 0.3f)
    }

    @Test
    fun `a tempo change dips stability and then recovers`() {
        val node = node()
        run(node, 120f, 800)
        val settled = node.value
        run(node, 150f, 30)
        val duringChange = node.value
        run(node, 150f, 1200)
        val recovered = node.value
        assertTrue("no dip: $settled -> $duringChange", duringChange < settled - 0.1f)
        assertTrue("no recovery: $recovered", recovered > 0.8f)
    }

    @Test
    fun `silence between tempos does not inherit the old stability`() {
        val node = node()
        run(node, 120f, 800)
        run(node, 0f, 200)
        assertTrue("held stability through silence: ${node.value}", node.value < 0.3f)
    }

    @Test
    fun `reset returns to zero`() {
        val node = node()
        run(node, 120f, 800)
        node.reset()
        assertEquals(0f, node.value, 0f)
        run(node, 90f, 800)
        assertTrue(node.value > 0.8f)
    }
}
