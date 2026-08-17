package dev.geode.analysis

import dev.geode.engine.audio.OnsetPeakPicker
import kotlin.math.exp
import kotlin.math.ln

/**
 * The user-facing beat-detection bounds, and the unit conversions between what
 * a preset stores and what the engine wants.
 *
 * Single source of truth: the Settings sliders use these as their range, the
 * preference store clamps to them, and [AnalysisEngine] and [OfflineAnalyzer]
 * clamp the values they apply — so a slider can never silently saturate
 * against a tighter clamp somewhere else.
 *
 * ## The sensitivity unit changed
 *
 * It used to be "sigmas over the mean flux". [OnsetPeakPicker] thresholds
 * against a median and a mean absolute deviation instead, because the old
 * statistics were dragged upward by the very peaks they were meant to find.
 * The number a user sets is therefore a count of *robust* deviations, and the
 * same numeric value is a somewhat different sensitivity than it was. The
 * range is chosen so the ends still mean what they meant — [SENSITIVITY_MIN]
 * is "almost everything", [SENSITIVITY_MAX] is "only the obvious" — and
 * [AppTheme]'s stored preference is clamped into it on read, so an old saved
 * value lands somewhere sensible rather than out of bounds.
 */
object BeatTuning {
    /** Most sensitive usable threshold; below this the picker fires on noise. */
    const val SENSITIVITY_MIN: Float = 1.5f

    /** Least sensitive threshold: only onsets that clearly stand out. */
    const val SENSITIVITY_MAX: Float = 8f

    /** Ships-with default. */
    const val SENSITIVITY_DEFAULT: Float = 3f

    /** "Slow track" preset: strict threshold, at most ~85 events/minute. */
    const val SLOW_SENSITIVITY: Float = 5.5f

    /** 200 ms refractory = 300 BPM ceiling; fast enough for drum & bass. */
    const val INTERVAL_MS_MIN: Float = 200f

    /** 1200 ms = 50 BPM, below the slowest common ballad tempo. */
    const val INTERVAL_MS_MAX: Float = 1200f

    /** Ships-with default: a 180 BPM ceiling. */
    const val INTERVAL_MS_DEFAULT: Float = 1000f / 3f

    /** "Slow track" preset partner value for the refractory window. */
    const val SLOW_INTERVAL_MS: Float = 700f

    /**
     * The hop rate the stored envelope values are expressed against; see
     * [envelopeSeconds].
     */
    const val REFERENCE_HOP_HZ: Float = 62.5f

    fun clampSensitivity(value: Float): Float = value.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)

    fun clampIntervalMs(value: Float): Float = value.coerceIn(INTERVAL_MS_MIN, INTERVAL_MS_MAX)

    /**
     * Converts a stored attack/decay value — a per-tick mix fraction, which is
     * how the old `BandSmoother` was configured and therefore how every saved
     * preset and every built-in still stores it — into the time constant the
     * new smoothing wants.
     *
     * Presets keep their feel across this rewrite for the price of this one
     * function. Reinterpreting the stored numbers as seconds would have been
     * less code and would have made every preset roughly thirty times slower,
     * which is a change nobody asked for: the band *scale* is what this work
     * deliberately breaks, not the envelope shape.
     *
     * `f = 1 - exp(-dt / tau)`, inverted. A fraction of 1 means "follow
     * instantly", which is a time constant of 0.
     */
    fun envelopeSeconds(perTickFraction: Float): Float {
        val f = perTickFraction.coerceIn(0f, 1f)
        if (f >= 1f) return 0f
        if (f <= 0f) return MAX_ENVELOPE_SECONDS
        val dt = 1f / REFERENCE_HOP_HZ
        return (-dt / ln(1f - f)).coerceIn(0f, MAX_ENVELOPE_SECONDS)
    }

    /** Inverse of [envelopeSeconds], for round-tripping a value back to the UI. */
    fun envelopeFraction(seconds: Float): Float {
        if (seconds <= 0f) return 1f
        return (1f - exp(-1f / (REFERENCE_HOP_HZ * seconds))).coerceIn(0f, 1f)
    }

    /** Ceiling on a converted time constant, so a stored 0 cannot freeze a band. */
    private const val MAX_ENVELOPE_SECONDS = 4f
}
