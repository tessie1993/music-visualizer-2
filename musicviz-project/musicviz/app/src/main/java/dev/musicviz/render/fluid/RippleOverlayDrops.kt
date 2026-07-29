package dev.musicviz.render.fluid

import dev.musicviz.analysis.AudioFeatures

/**
 * Musical drop schedule for the ripple overlay (F2): converts the per-frame
 * audio features into RippleSim drops without any random source - beats land
 * a pair of large rings, strong treble sprinkles small sparkle drops, and
 * every position comes from [RippleMath.overlayDropPosition]'s golden-angle
 * sequence over one shared drop counter, so the live renderer and the export
 * path place identical drops for the same feature stream. Pure Kotlin (no
 * GL): the sim is fed through the [queue] callback, which keeps the schedule
 * headless-gate-testable.
 */
internal class RippleOverlayDrops {
    companion object {
        /** Rings dropped per detected beat. */
        const val BEAT_DROPS = 2

        /** Frames between treble sparkle drops (rate cap). */
        const val SPARKLE_INTERVAL = 6

        /** Treble level above which sparkle drops fall. */
        const val SPARKLE_THRESHOLD = 0.5f
    }

    private var frame = 0
    private var dropIndex = 0
    private var prevBeat = false

    fun reset() {
        frame = 0
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
        queue: (Float, Float, Float, Float) -> Unit,
    ) {
        frame++
        // Edge-detect the beat flag: the ~62.5 Hz analysis snapshot can be
        // consumed by several display frames (FluidEmitters convention).
        val beatEdge = features.beat && !prevBeat
        prevBeat = features.beat
        if (beatEdge) {
            val amp = 0.22f + 0.4f * features.bass.coerceIn(0f, 1.5f)
            repeat(BEAT_DROPS) {
                val (x, y) = RippleMath.overlayDropPosition(dropIndex++, aspect)
                queue(x, y, 0.055f, amp)
            }
        }
        if (features.treble > SPARKLE_THRESHOLD && frame % SPARKLE_INTERVAL == 0) {
            val (x, y) = RippleMath.overlayDropPosition(dropIndex++, aspect)
            queue(x, y, 0.03f, 0.1f * features.treble.coerceAtMost(2f))
        }
    }
}
