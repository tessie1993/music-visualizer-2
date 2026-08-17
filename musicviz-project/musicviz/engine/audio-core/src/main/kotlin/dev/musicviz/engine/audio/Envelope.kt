package dev.musicviz.engine.audio

import kotlin.math.exp

/**
 * Asymmetric attack/release follower — fast up, slow down.
 *
 * This is the shape every visual-driving signal wants. A symmetric filter is
 * either laggy or jittery; fast-attack/slow-release keeps the snap of a
 * transient while the decay reads as musical sustain. It is the difference
 * `docs/quality/bar-visualizer.md` §2.5 calls "it dances" versus "it flickers".
 *
 * ## Why time constants and not per-tick coefficients
 *
 * The coefficient is derived as `1 - exp(-dt / tau)` from the elapsed time,
 * every step. The legacy `BandSmoother` mixed by fixed per-tick fractions
 * (`0.6` rising, `0.12` falling) with no `dt` at all, which made its behaviour
 * a property of the analysis cadence: the same music felt different at a 60 Hz
 * hop than at a 120 Hz one, and changing the hop rate silently retuned every
 * scene in the app. Seconds are the unit a listener perceives, so seconds are
 * the unit the follower is configured in.
 *
 * Holds one float. Allocates nothing; safe in the per-frame path.
 */
class Envelope(
    /** Rise time constant in seconds; 0 follows a rising target instantly. */
    @Volatile var attackSeconds: Float,
    /** Fall time constant in seconds; 0 follows a falling target instantly. */
    @Volatile var releaseSeconds: Float,
) {
    /** The current level, as of the last [step]. */
    var value: Float = 0f
        private set

    /**
     * Advances the follower toward [target] over [dtSeconds] and returns the
     * new [value].
     *
     * A long [dtSeconds] — a dropped frame, a stalled worker — settles on the
     * target rather than overshooting it: `exp` underflows toward zero, so the
     * coefficient saturates at 1. A non-positive [dtSeconds] is a no-op, which
     * is what a duplicate tick should be.
     */
    fun step(
        target: Float,
        dtSeconds: Float,
    ): Float {
        if (dtSeconds <= 0f) return value
        val tau = if (target > value) attackSeconds else releaseSeconds
        value =
            if (tau <= 0f) {
                target
            } else {
                val k = (1f - exp(-dtSeconds / tau)).coerceIn(0f, 1f)
                value + (target - value) * k
            }
        return value
    }

    /**
     * Places the follower at [level] without any smoothing.
     *
     * For seeding a follower to a known state — the start of a track whose
     * first level is already known, or a test that wants to observe the release
     * side without waiting out the attack.
     */
    fun primeTo(level: Float) {
        value = level
    }

    /** Drops to zero, keeping [attackSeconds] and [releaseSeconds]. */
    fun reset() {
        value = 0f
    }
}
