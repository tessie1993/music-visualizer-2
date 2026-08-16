package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FixedRangeTest {
    @Test
    fun `the range's ends map to the range's ends`() {
        val fixed = FixedRange(200f, 8_000f)
        assertEquals(0f, fixed.normalize(200f, FrameActivity.Sounding), 0f)
        assertEquals(1f, fixed.normalize(8_000f, FrameActivity.Sounding), 0f)
        assertEquals(0.5f, fixed.normalize(4_100f, FrameActivity.Sounding), 1e-6f)
    }

    @Test
    fun `a negative range works the same way`() {
        // Correlation runs -1..1, and a mode that only handled positive ranges
        // would send every stereo feature looking for a workaround.
        val fixed = FixedRange(-1f, 1f)
        assertEquals(0f, fixed.normalize(-1f, FrameActivity.Sounding), 0f)
        assertEquals(0.5f, fixed.normalize(0f, FrameActivity.Sounding), 1e-6f)
        assertEquals(1f, fixed.normalize(1f, FrameActivity.Sounding), 0f)
    }

    @Test
    fun `values outside the range clamp rather than extrapolate`() {
        val fixed = FixedRange(200f, 8_000f)
        assertEquals(0f, fixed.normalize(-5_000f, FrameActivity.Sounding), 0f)
        assertEquals(1f, fixed.normalize(1e9f, FrameActivity.Sounding), 0f)
    }

    @Test
    fun `the same input gives the same output whatever came before it`() {
        // The defining property of the mode, and the reason presets are
        // repeatable under it: no history, so no drift between a live pass and
        // an offline one that reached the same frame by a different route.
        val fresh = FixedRange(0f, 1f)
        val weathered = FixedRange(0f, 1f)
        repeat(10_000) { i ->
            weathered.normalize(if (i % 3 == 0) 1e6f else -1e6f, FrameActivity.Sounding)
            if (i % 5 == 0) weathered.normalize(0f, FrameActivity.Silent)
        }
        for (raw in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
            assertEquals(
                fresh.normalize(raw, FrameActivity.Sounding),
                weathered.normalize(raw, FrameActivity.Sounding),
                0f,
            )
        }
    }

    @Test
    fun `a silent frame rests at zero and says so`() {
        val fixed = FixedRange(200f, 8_000f)
        assertEquals(0f, fixed.normalize(4_100f, FrameActivity.Silent), 0f)
        assertSame(FeatureValidity.Silent, fixed.validity)
        // And the very next sounding frame is valid again: there is nothing to
        // re-learn, which is what separates this mode from the other two.
        assertEquals(0.5f, fixed.normalize(4_100f, FrameActivity.Sounding), 1e-6f)
        assertSame(FeatureValidity.Valid, fixed.validity)
    }

    @Test
    fun `a malformed range is refused at construction`() {
        val bad =
            listOf(
                { FixedRange(1f, 1f) },
                { FixedRange(1f, 0f) },
                { FixedRange(Float.NaN, 1f) },
                { FixedRange(0f, Float.POSITIVE_INFINITY) },
            )
        for (make in bad) {
            try {
                make()
                throw AssertionError("a malformed range was accepted")
            } catch (expected: IllegalArgumentException) {
                assertTrue("the message says nothing useful", expected.message!!.isNotEmpty())
            }
        }
    }
}
