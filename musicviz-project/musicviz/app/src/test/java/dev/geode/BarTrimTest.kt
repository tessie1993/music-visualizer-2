package dev.geode

import dev.geode.analysis.BarTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate for loop-safe export.
 *
 * The property that matters is not "the clip got shorter" - it is that the cut
 * lands on a bar boundary, so the last beat runs into the first when a
 * platform autoplays the clip on repeat. Everything here is stated in terms of
 * that: whole bars out, never longer than the source, and a refusal to guess
 * when the tempo is not trustworthy.
 */
class BarTrimTest {
    private fun bars(
        n: Int,
        bpm: Float,
    ): Long = (n * 60_000_000.0 * BarTrim.BEATS_PER_BAR / bpm).toLong()

    @Test
    fun aTrimmedDurationIsAWholeNumberOfBars() {
        val bpm = 128f
        val bar = BarTrim.barDurationUs(bpm)!!
        for (sourceMs in listOf(31_400L, 60_000L, 187_500L, 240_001L)) {
            val trimmed = BarTrim.trimToBars(sourceMs * 1000, bpm)
            assertEquals("cut is not on a bar boundary", 0L, trimmed % bar)
        }
    }

    @Test
    fun theCutIsNeverLongerThanTheSource() {
        // Rounding up would end the clip in silence, which is worse than the
        // seam it was meant to fix.
        val bpm = 100f
        for (sourceUs in listOf(1_000_000L, 7_777_777L, 123_456_789L)) {
            assertTrue(BarTrim.trimToBars(sourceUs, bpm) <= sourceUs)
        }
    }

    @Test
    fun atMostOneBarIsLost() {
        val bpm = 174f
        val bar = BarTrim.barDurationUs(bpm)!!
        for (sourceUs in listOf(30_000_000L, 45_500_000L, 61_234_567L)) {
            assertTrue("more than a bar was cut", BarTrim.trimmedAwayUs(sourceUs, bpm) < bar)
        }
    }

    @Test
    fun anExactNumberOfBarsIsLeftAlone() {
        val bpm = 120f
        val exact = bars(16, bpm)
        assertEquals(exact, BarTrim.trimToBars(exact, bpm))
        assertEquals(0L, BarTrim.trimmedAwayUs(exact, bpm))
    }

    @Test
    fun anUntrustworthyTempoLeavesTheDurationAlone() {
        // A half/double-time detection error or a track with no pulse would
        // otherwise cut the clip somewhere arbitrary while claiming it was
        // musical - so outside the plausible range the answer is "don't".
        val sourceUs = 60_000_000L
        for (bpm in floatArrayOf(0f, -120f, 12f, 400f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals("bpm=$bpm should not be trusted", sourceUs, BarTrim.trimToBars(sourceUs, bpm))
            assertNull(BarTrim.barDurationUs(bpm))
        }
    }

    @Test
    fun aClipShorterThanOneBarIsLeftAlone() {
        // Trimming it would leave nothing, and a clip of zero length is not a
        // loop-safe clip.
        val bpm = 90f
        val halfBar = BarTrim.barDurationUs(bpm)!! / 2
        assertEquals(halfBar, BarTrim.trimToBars(halfBar, bpm))
    }

    @Test
    fun aBarIsFourBeatsOfTheDetectedTempo() {
        assertEquals(2_000_000L, BarTrim.barDurationUs(120f)!!)
        assertEquals(4, BarTrim.BEATS_PER_BAR)
    }

    @Test
    fun zeroAndNegativeDurationsPassStraightThrough() {
        assertEquals(0L, BarTrim.trimToBars(0L, 120f))
        assertEquals(-5L, BarTrim.trimToBars(-5L, 120f))
    }
}
