package dev.musicviz.analysis

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure math behind the playback settings: semitone-to-pitch-ratio conversion,
 * the sleep-timer fade curve/countdown, and slider snapping. Lives in the
 * analysis package (not ui/) so the headless gate typechecks and unit-tests
 * it - ui/ needs Compose/Media3, which the gate cannot compile.
 */
object PlaybackMath {
    /** Sleep-timer volume fade length before the pause. */
    const val SLEEP_FADE_MS = 3_000L

    /** Converts a pitch offset in semitones to a playback pitch ratio (2^(st/12)). */
    fun semitonesToRatio(semitones: Float): Float = 2.0.pow(semitones / 12.0).toFloat()

    /**
     * Player volume while a sleep timer runs: full until the final [fadeMs]
     * window, then a linear ramp down to 0 at expiry.
     */
    fun sleepFadeVolume(
        remainingMs: Long,
        fadeMs: Long = SLEEP_FADE_MS,
    ): Float {
        if (fadeMs <= 0L) return if (remainingMs > 0L) 1f else 0f
        return (remainingMs.toFloat() / fadeMs).coerceIn(0f, 1f)
    }

    /** m:ss countdown text for the sleep timer; rounds up so 1 ms shows 0:01. */
    fun formatCountdown(remainingMs: Long): String {
        val totalSec = (remainingMs.coerceAtLeast(0L) + 999L) / 1000L
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    /** Snaps [value] to the nearest multiple of [step] (slider detents). */
    fun snap(
        value: Float,
        step: Float,
    ): Float = if (step <= 0f) value else (value / step).roundToInt() * step
}
