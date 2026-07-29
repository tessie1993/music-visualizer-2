package dev.musicviz.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

/**
 * Headless validation of the playback-settings math: semitone-to-ratio
 * conversion (the pitch slider contract), the sleep-timer fade curve and
 * countdown formatting, and slider snapping.
 */
class PlaybackMathTest {
    @Test
    fun semitoneRatioMatchesEqualTemperament() {
        assertEquals(1f, PlaybackMath.semitonesToRatio(0f), 1e-6f)
        assertEquals(2f, PlaybackMath.semitonesToRatio(12f), 1e-5f)
        assertEquals(0.5f, PlaybackMath.semitonesToRatio(-12f), 1e-5f)
        assertEquals(2.0.pow(6.0 / 12).toFloat(), PlaybackMath.semitonesToRatio(6f), 1e-5f)
        assertEquals(2.0.pow(-0.5 / 12).toFloat(), PlaybackMath.semitonesToRatio(-0.5f), 1e-5f)
    }

    @Test
    fun sleepFadeIsFullUntilWindowThenLinearToZero() {
        assertEquals(1f, PlaybackMath.sleepFadeVolume(10 * 60_000L), 1e-6f)
        assertEquals(1f, PlaybackMath.sleepFadeVolume(PlaybackMath.SLEEP_FADE_MS), 1e-6f)
        assertEquals(0.5f, PlaybackMath.sleepFadeVolume(PlaybackMath.SLEEP_FADE_MS / 2), 1e-6f)
        assertEquals(0f, PlaybackMath.sleepFadeVolume(0L), 1e-6f)
        assertEquals(0f, PlaybackMath.sleepFadeVolume(-100L), 1e-6f)
        // Degenerate fade window: hard cut instead of divide-by-zero.
        assertEquals(1f, PlaybackMath.sleepFadeVolume(500L, 0L), 1e-6f)
        assertEquals(0f, PlaybackMath.sleepFadeVolume(0L, 0L), 1e-6f)
    }

    @Test
    fun countdownRoundsUpAndFormatsMinutesSeconds() {
        assertEquals("0:00", PlaybackMath.formatCountdown(0L))
        assertEquals("0:01", PlaybackMath.formatCountdown(1L))
        assertEquals("0:59", PlaybackMath.formatCountdown(59_000L))
        assertEquals("1:00", PlaybackMath.formatCountdown(60_000L))
        assertEquals("15:00", PlaybackMath.formatCountdown(15 * 60_000L))
        assertEquals("2:05", PlaybackMath.formatCountdown(2 * 60_000L + 4_001L))
        assertEquals("0:00", PlaybackMath.formatCountdown(-5L))
    }

    @Test
    fun snapLandsOnDetents() {
        assertEquals(1.05f, PlaybackMath.snap(1.049f, 0.05f), 1e-6f)
        assertEquals(0.5f, PlaybackMath.snap(0.51f, 0.05f), 1e-6f)
        assertEquals(2f, PlaybackMath.snap(1.99f, 0.05f), 1e-6f)
        assertEquals(-0.5f, PlaybackMath.snap(-0.6f, 0.5f), 1e-6f)
        assertEquals(0f, PlaybackMath.snap(0.2f, 0.5f), 1e-6f)
        // Non-positive step passes the value through unchanged.
        assertEquals(1.234f, PlaybackMath.snap(1.234f, 0f), 1e-6f)
    }
}
