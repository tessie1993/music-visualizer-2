package dev.musicviz.render.fluid

import dev.musicviz.analysis.AudioFeatures

/**
 * Musical drop schedule for the ripple overlay (F2): converts the per-frame
 * audio features into RippleSim drops without any random source - beats land
 * a pair of large rings, strong treble sprinkles small sparkle drops, and
 * every position comes from [RippleMath.overlayDropPosition]'s golden-angle
 * sequence over one shared drop counter, so the live renderer and the export
 * path place identical drops for the same feature stream - at any frame rate,
 * since the sparkle rate cap is a duration rather than a frame count. Pure Kotlin (no
 * GL): the sim is fed through the [queue] callback, which keeps the schedule
 * headless-gate-testable.
 */
internal class RippleOverlayDrops {
    companion object {
        /** Rings dropped per detected beat. */
        const val BEAT_DROPS = 2

        /**
         * Seconds between treble sparkle drops (rate cap).
         *
         * Deliberately a duration, not a frame count. It used to be "every
         * 6th frame", which meant live playback at 60/90/120 Hz and an export
         * at 24-60 fps sprinkled at different rates for the same music - and
         * because sparkles and beat rings share one [dropIndex], a different
         * sparkle count also shifted every subsequent RING position. 0.1 s is
         * what 6 frames meant at 60 fps.
         */
        const val SPARKLE_INTERVAL_SEC = 0.1f

        /** Treble level above which sparkle drops fall. */
        const val SPARKLE_THRESHOLD = 0.5f
    }

    private var sparkleCooldown = 0f
    private var dropIndex = 0
    private var prevBeat = false

    fun reset() {
        sparkleCooldown = 0f
        dropIndex = 0
        prevBeat = false
    }

    /**
     * One frame of the schedule. [queue] receives (x, y, radius, amplitude)
     * in RippleSim sim space (y in [-1, 1], x in [-aspect, aspect]).
     */
    fun tick(
        features: AudioFeatures,
        aspect: Float,
        dtSeconds: Float,
        queue: (Float, Float, Float, Float) -> Unit,
    ) {
        sparkleCooldown -= dtSeconds
        // Edge-detect the beat flag: the ~62.5 Hz analysis snapshot can be
        // consumed by several display frames (FluidEmitters convention).
        val beatEdge = features.beat && !prevBeat
        prevBeat = features.beat
        if (beatEdge) {
            // Scaled by the graded impulse: soft beats land soft rings.
            val amp = (0.22f + 0.4f * features.bass.coerceIn(0f, 1.5f)) * features.beatImpulse
            repeat(BEAT_DROPS) {
                val (x, y) = RippleMath.overlayDropPosition(dropIndex++, aspect)
                queue(x, y, 0.055f, amp)
            }
        }
        if (features.treble > SPARKLE_THRESHOLD && sparkleCooldown <= 0f) {
            // Carry the remainder rather than resetting to the full interval:
            // resetting quantises the rate to whole frames, so 24 fps would
            // still sprinkle at 1/0.125 s while 60 fps managed 1/0.1 s.
            // Accumulating keeps the long-run rate exactly 1/SPARKLE_INTERVAL_SEC
            // at any dt. The floor only guards a pathological stall.
            sparkleCooldown = (sparkleCooldown + SPARKLE_INTERVAL_SEC).coerceAtLeast(0f)
            val (x, y) = RippleMath.overlayDropPosition(dropIndex++, aspect)
            queue(x, y, 0.03f, 0.1f * features.treble.coerceAtMost(2f))
        }
    }
}
