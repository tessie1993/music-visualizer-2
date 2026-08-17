package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The three rules in [BeatGrid]'s class doc, one test each, plus the phase ramp. */
class BeatGridTest {
    private val period = 50f
    private val locked = 0.9f
    private val unlocked = 0.1f

    /** Runs [frames] frames, firing an onset every [onsetEvery] frames from [offset]. */
    private fun run(
        grid: BeatGrid,
        frames: Int,
        onsetEvery: Int,
        confidence: Float,
        offset: Int = 0,
    ): List<Int> =
        (0 until frames).filter { i ->
            grid.step(period, confidence, onset = i >= offset && (i - offset) % onsetEvery == 0)
        }

    @Test
    fun `on-grid onsets are beats`() {
        val grid = BeatGrid()
        val beats = run(grid, frames = 1000, onsetEvery = 50, confidence = locked)
        assertTrue("fired ${beats.size} of 20", beats.size >= 18)
    }

    /** Rule one: while locked, an onset off the grid is not a beat. */
    @Test
    fun `off-grid onsets are suppressed while locked`() {
        val grid = BeatGrid()
        // Anchor the grid, then hammer at an interval that keeps landing between beats.
        run(grid, frames = 500, onsetEvery = 50, confidence = locked)
        val offGrid = run(grid, frames = 500, onsetEvery = 50, confidence = locked, offset = 25)
        assertTrue("fired $offGrid", offGrid.size < 3)
    }

    /** Rule two: with no trustworthy grid, everything passes. */
    @Test
    fun `every onset passes while unlocked`() {
        val grid = BeatGrid()
        val beats = run(grid, frames = 500, onsetEvery = 37, confidence = unlocked)
        assertEquals((0 until 500 step 37).toList(), beats)
    }

    /** Rule three: a predicted beat with no onset behind it stays silent. */
    @Test
    fun `a silent stretch fires nothing`() {
        val grid = BeatGrid()
        run(grid, frames = 500, onsetEvery = 50, confidence = locked)
        val quiet = (0 until 500).filter { grid.step(period, locked, onset = false) }
        assertTrue("fired $quiet", quiet.isEmpty())
    }

    @Test
    fun `phase ramps from zero to one across a beat`() {
        val grid = BeatGrid()
        run(grid, frames = 500, onsetEvery = 50, confidence = locked)
        grid.step(period, locked, onset = true)
        val ramp =
            (0 until 49).map {
                grid.step(period, locked, onset = false)
                grid.phase
            }
        assertTrue("started at ${ramp.first()}", ramp.first() < 0.1f)
        assertTrue("ended at ${ramp.last()}", ramp.last() > 0.9f)
        for (i in 1 until ramp.size) assertTrue("phase went backwards at $i", ramp[i] > ramp[i - 1])
    }

    @Test
    fun `a drifting pulse is followed rather than lost`() {
        val grid = BeatGrid()
        run(grid, frames = 1000, onsetEvery = 50, confidence = locked)
        // The same tempo, phase-shifted by four frames — a player pushing ahead.
        var fired = 0
        repeat(1000) { i -> if (grid.step(period, locked, onset = (i - 4) % 50 == 0 && i >= 4)) fired++ }
        assertTrue("only $fired of 20 followed the shift", fired >= 15)
    }

    @Test
    fun `with no tempo every onset is a beat`() {
        val grid = BeatGrid()
        val beats = (0 until 300).filter { grid.step(0f, locked, onset = it % 31 == 0) }
        assertEquals((0 until 300 step 31).toList(), beats)
    }

    @Test
    fun `reset clears the phase and the lock`() {
        val grid = BeatGrid()
        run(grid, frames = 500, onsetEvery = 50, confidence = locked)
        grid.reset()
        assertEquals(0f, grid.phase, 0f)
        assertTrue(!grid.locked)
        assertTrue(!grid.beat)
    }
}
