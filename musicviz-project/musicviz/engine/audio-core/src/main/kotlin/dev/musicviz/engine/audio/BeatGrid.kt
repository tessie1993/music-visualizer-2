package dev.musicviz.engine.audio

import kotlin.math.abs

/**
 * A phase-locked beat grid over the onset stream: which onsets are *beats*,
 * where the next one is due, and how far through the current one we are.
 *
 * An onset detector is reactive — every hit that clears the threshold fires,
 * and to a scene they all look alike. On real material that means syncopated
 * hits, fills, vocal consonants and hand percussion all trigger full-strength
 * visual events, which reads as flicker rather than rhythm. Both standard beat
 * trackers (Scheirer's resonators, Ellis' dynamic programming) fix it the same
 * way: model a *period* and a *phase*, then treat onsets as evidence for that
 * grid rather than as beats in themselves.
 *
 * Three rules follow, and between them they are the whole class:
 *
 * - While the grid is **locked**, an onset far from a predicted beat is not a
 *   beat. This is the single rule that turns "everything flashes" into
 *   something a listener recognises as the pulse.
 * - While it is **unlocked** — ambient, rubato, anything with no stable pulse —
 *   every onset passes, and the grid re-anchors to it. Material with no beat
 *   should breathe with what is played, not wait for a grid that will never
 *   arrive.
 * - A predicted beat that **no onset supports** does not fire. The grid
 *   advances silently, so a breakdown stays calm instead of stamping out a
 *   pulse the music has stopped playing.
 *
 * Deterministic and ordered: [step] must see every frame, in order.
 */
class BeatGrid {
    /** Position within the current beat, 0 on the beat rising to 1 before the next. */
    var phase: Float = 0f
        private set

    /** Whether the last [step] was a beat. */
    var beat: Boolean = false
        private set

    /** Whether the grid is currently trusted enough to suppress off-grid onsets. */
    var locked: Boolean = false
        private set

    /**
     * Advances the grid one frame and decides whether this frame is a beat.
     *
     * @param periodFrames the tempo period from [TempoTracker]; 0 or less means
     *   no tempo is known, in which case every onset is a beat.
     * @param confidence [TempoTracker.confidence], which decides locking.
     * @param onset whether [OnsetPeakPicker] accepted this frame.
     */
    fun step(
        periodFrames: Float,
        confidence: Float,
        onset: Boolean,
    ): Boolean {
        locked = confidence >= LOCK_CONFIDENCE && periodFrames > 0f

        if (periodFrames > 0f) {
            phase += 1f / periodFrames
            while (phase >= 1f) phase -= 1f
        }

        beat =
            when {
                !onset -> false
                !locked -> {
                    // No grid worth keeping: take the onset and start the beat here.
                    phase = 0f
                    true
                }
                else -> {
                    // Distance to the nearest grid point, which is at phase 0 —
                    // and phase 1 is the same place, so an onset landing just
                    // *early* is as on-grid as one landing just late.
                    val error = if (phase > 0.5f) phase - 1f else phase
                    if (abs(error) <= ON_GRID_TOLERANCE) {
                        // Pull the grid toward the evidence rather than onto it:
                        // snapping would make the grid follow every lazy hit
                        // instead of averaging them into a tempo.
                        phase -= error * PHASE_CORRECTION
                        if (phase < 0f) phase += 1f
                        true
                    } else {
                        false
                    }
                }
            }
        return beat
    }

    /** Forgets the phase and the lock; call on a track change or a seek. */
    fun reset() {
        phase = 0f
        beat = false
        locked = false
    }

    companion object {
        /**
         * [TempoTracker.confidence] at which the grid starts suppressing
         * off-grid onsets. Below it the tracker is guessing, and suppressing
         * real hits on a guess is worse than letting texture through.
         */
        const val LOCK_CONFIDENCE: Float = 0.4f

        /**
         * How far from a predicted beat an onset may land and still count, as a
         * fraction of the period. About a sixteenth note at 4/4 — wide enough
         * for human timing and a swung eighth, narrow enough to reject the
         * off-beat.
         */
        const val ON_GRID_TOLERANCE: Float = 0.12f

        /** Fraction of the observed phase error fed back into the grid. */
        const val PHASE_CORRECTION: Float = 0.25f
    }
}
