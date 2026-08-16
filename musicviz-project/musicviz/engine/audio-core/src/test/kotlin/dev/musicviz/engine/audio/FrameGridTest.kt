package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The one property MASTER_PLAN §5.3 calls out by name: every branch of the
 * analysis stack must address its windows by the centre sample, so that four
 * resolutions describe one instant rather than four.
 */
class FrameGridTest {
    private val rate = 48_000

    @Test
    fun `every branch of the stack has a frame centred on the shared grid`() {
        // The hops are 256 and 512, so 512 is the coarsest common spacing.
        // Every branch must have a window centred at every multiple of it,
        // whatever its window length.
        val grids = AnalysisBranch.STACK.map { FrameGrid(it) }
        for (sample in 0L until 48_000L step 512L) {
            val missing = grids.filterNot { it.hasFrameCenteredAt(sample) }.map { it.branch.name }
            assertEquals("no window centred at sample $sample", emptyList<String>(), missing)
        }
    }

    @Test
    fun `frames with the same index across branches describe the same instant`() {
        val general = FrameGrid(AnalysisBranch.GENERAL)
        val harmony = FrameGrid(AnalysisBranch.HARMONY)
        // Same hop, so index for index the centres coincide exactly - even
        // though one window is eight times the other's length.
        for (index in 0L..100L) {
            assertEquals(general.centerSample(index), harmony.centerSample(index))
            assertEquals(general.centerMicros(index, rate), harmony.centerMicros(index, rate))
        }
        assertTrue(
            "the windows should differ in extent even where the centres agree",
            harmony.firstSample(10) < general.firstSample(10) && harmony.endSample(10) > general.endSample(10),
        )
    }

    @Test
    fun `right-edge alignment costs the 75 ms the plan names`() {
        // The counterfactual, measured rather than quoted. A streaming
        // analyzer stamps a frame with the last sample it saw, which puts the
        // centre at k*hop + window/2 - an offset that differs per branch.
        fun rightEdgeCentre(
            branch: AnalysisBranch,
            index: Long,
        ) = index * branch.hopFrames + branch.windowFrames / 2L

        val index = 100L
        val general = rightEdgeCentre(AnalysisBranch.GENERAL, index)
        val harmony = rightEdgeCentre(AnalysisBranch.HARMONY, index)
        val skewSamples = abs(harmony - general)
        val skewMs = skewSamples * 1000.0 / rate

        assertEquals("(8192 - 1024) / 2", 3_584L, skewSamples)
        assertTrue("expected roughly 75 ms of skew, measured $skewMs", skewMs in 74.0..75.5)

        // Centre alignment removes it entirely, which is the whole point.
        assertEquals(
            0L,
            FrameGrid(AnalysisBranch.HARMONY).centerSample(index) - FrameGrid(AnalysisBranch.GENERAL).centerSample(index),
        )
    }

    @Test
    fun `a frame's window is centred on its centre sample`() {
        val grid = FrameGrid(AnalysisBranch.PITCH)
        val index = 7L
        assertEquals(grid.centerSample(index) - 2048, grid.firstSample(index))
        assertEquals(grid.centerSample(index) + 2048, grid.endSample(index))
        assertEquals(AnalysisBranch.PITCH.windowFrames.toLong(), grid.endSample(index) - grid.firstSample(index))
    }

    @Test
    fun `the first frames begin before sample zero, and say so`() {
        // Not an error: it is what centre alignment means, and the same
        // convention librosa's center=True uses. A reader either pads or waits
        // for firstCompleteFrame - what it must not do is silently shift.
        val grid = FrameGrid(AnalysisBranch.HARMONY)
        assertTrue("frame 0 must start before the stream does", grid.firstSample(0) < 0)
        assertEquals(-4096L, grid.firstSample(0))
        assertEquals("4096 of half-window, 512 hops", 8L, grid.firstCompleteFrame)
        assertTrue(grid.firstSample(grid.firstCompleteFrame) >= 0)
        assertTrue(grid.firstSample(grid.firstCompleteFrame - 1) < 0)
    }

    @Test
    fun `a frame is only complete when its whole window has arrived`() {
        val grid = FrameGrid(AnalysisBranch.GENERAL)
        assertNull("nothing is readable from an empty stream", grid.latestCompleteFrame(0))
        assertNull(grid.latestCompleteFrame(511))

        // 1024-sample window, 512 hop: frame 1 spans [0, 1024).
        assertEquals(1L, grid.latestCompleteFrame(1024))
        assertEquals(1L, grid.latestCompleteFrame(1535))
        assertEquals(2L, grid.latestCompleteFrame(1536))
        assertTrue(grid.endSample(grid.latestCompleteFrame(1536)!!) <= 1536)
    }

    @Test
    fun `frameAtOrBefore inverts centerSample, including before the stream`() {
        val grid = FrameGrid(AnalysisBranch.TRANSIENT)
        for (index in -4L..40L) {
            assertEquals(index, grid.frameAtOrBefore(grid.centerSample(index)))
        }
        assertEquals("a sample between centres belongs to the earlier frame", 3L, grid.frameAtOrBefore(3 * 256 + 255L))
        assertEquals(-1L, grid.frameAtOrBefore(-1))
    }

    @Test
    fun `a branch with an unusable shape is refused at construction`() {
        listOf(
            { AnalysisBranch("odd", windowFrames = 1000, hopFrames = 500) },
            { AnalysisBranch("zero-hop", windowFrames = 1024, hopFrames = 0) },
            { AnalysisBranch("negative", windowFrames = -1024, hopFrames = 512) },
        ).forEach { build ->
            try {
                build()
                error("expected a rejected branch")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotEmpty())
            }
        }
    }
}
