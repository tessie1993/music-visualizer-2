package dev.musicviz.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the offline analyzer's memory bound.
 *
 * Historical bug: [OfflineAnalyzer] appended every 60 Hz frame to a plain
 * ArrayList for the whole track. At roughly a kilobyte per frame that is
 * ~3.5 MB per minute - a 3-hour DJ set held ~650 MB of frames and the process
 * was OOM-killed mid-"Analyzing...". [FrameAccumulator] bounds the list by
 * halving its own time resolution instead of truncating the track, and these
 * tests pin the properties that halving must preserve: nothing changes below
 * the bound, the bound actually holds above it, timestamps stay ordered and
 * uniform, and no beat/impulse is lost to a merge.
 */
class FrameAccumulatorTest {
    private fun frame(
        i: Int,
        beat: Boolean = false,
        flux: Float = 0f,
        kick: Float = 0f,
    ): TimelineFrame =
        TimelineFrame(
            timeMs = i * 16L,
            features =
                AudioFeatures(
                    bands = FloatArray(4) { i / 100f },
                    waveform = FloatArray(8),
                    rms = i / 100f,
                    beat = beat,
                    flux = flux,
                    beatStrength = if (beat) 0.5f else 0f,
                    kick = kick,
                ),
        )

    @Test
    fun `below the bound every frame passes through untouched`() {
        val acc = FrameAccumulator(maxFrames = 100)
        val input = (0 until 50).map { frame(it, beat = it % 7 == 0, flux = it / 50f) }
        input.forEach { acc.add(it) }
        val out = acc.finish()
        assertEquals(1, acc.groupSize)
        assertEquals(input, out)
    }

    @Test
    fun `reaching the bound halves the history and keeps it bounded from then on`() {
        val max = 64
        val acc = FrameAccumulator(maxFrames = max)
        for (i in 0 until 1000) {
            acc.add(frame(i))
            assertTrue("size ${acc.size} must stay under $max", acc.size < max)
        }
        val out = acc.finish()
        assertTrue("expected decimation, got groupSize=${acc.groupSize}", acc.groupSize > 1)
        // Power-of-two group size, and enough frames survived to cover the track.
        assertEquals(0, acc.groupSize and (acc.groupSize - 1))
        assertEquals(1000 / acc.groupSize + if (1000 % acc.groupSize == 0) 0 else 1, out.size)
        // Timestamps stay strictly increasing and uniformly spaced (the
        // nearest-index lookup in FeatureTimeline assumes uniform spacing).
        val spacing = out[1].timeMs - out[0].timeMs
        assertEquals(16L * acc.groupSize, spacing)
        for (i in 1 until out.size) {
            assertEquals("spacing at $i", spacing, out[i].timeMs - out[i - 1].timeMs)
        }
    }

    @Test
    fun `no beat or impulse is lost to merging`() {
        val acc = FrameAccumulator(maxFrames = 16)
        // Beats on odd indices too, so pairwise merges must OR them across.
        val beatIndices = setOf(5, 33, 62, 63, 90)
        for (i in 0 until 100) {
            acc.add(frame(i, beat = i in beatIndices, flux = if (i in beatIndices) 1f else 0.01f, kick = if (i == 33) 0.9f else 0f))
        }
        val out = acc.finish()
        assertTrue(acc.groupSize > 1)
        // Every original beat lands inside exactly the merged frame that
        // covers its time; adjacent beats may collapse into one frame, so
        // count covered groups rather than flags.
        val expectedGroups = beatIndices.map { it / acc.groupSize }.toSet()
        val actualGroups = out.withIndex().filter { it.value.features.beat }.map { it.index }.toSet()
        assertEquals(expectedGroups, actualGroups)
        // Impulse channels are max-held, never averaged away.
        assertEquals(1f, out.maxOf { it.features.flux }, 0f)
        assertEquals(0.9f, out.maxOf { it.features.kick }, 0f)
        // Graded strength survives alongside the flag.
        assertEquals(0.5f, out.maxOf { it.features.beatStrength }, 0f)
    }

    @Test
    fun `a partial trailing group is flushed rather than dropped`() {
        val acc = FrameAccumulator(maxFrames = 8)
        // 21 frames with groupSize 2 after the first halving: the last group
        // is partial, and it carries the only late beat.
        for (i in 0 until 21) acc.add(frame(i, beat = i == 20))
        val out = acc.finish()
        assertTrue(acc.groupSize > 1)
        assertTrue("the trailing beat must survive the flush", out.last().features.beat)
        // The flushed frame covers through the final input frame's time.
        assertTrue(out.last().timeMs <= 20L * 16)
        assertTrue(out.last().timeMs > out[out.size - 2].timeMs)
    }

    @Test
    fun `merge keeps the first frame's continuous levels and timestamp`() {
        val a = frame(10, beat = false, flux = 0.1f)
        val b = frame(11, beat = true, flux = 0.8f)
        val m = FrameAccumulator.merge(a, b)
        assertEquals(a.timeMs, m.timeMs)
        assertEquals(a.features.rms, m.features.rms, 0f)
        assertTrue(m.features.beat)
        assertEquals(0.8f, m.features.flux, 0f)
    }
}
