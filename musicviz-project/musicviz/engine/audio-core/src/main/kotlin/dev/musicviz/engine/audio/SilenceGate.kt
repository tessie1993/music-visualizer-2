package dev.musicviz.engine.audio

import kotlin.math.roundToInt

/** Whether a frame carries signal. One decision per frame, shared by every feature on it. */
sealed interface FrameActivity {
    data object Silent : FrameActivity

    data object Sounding : FrameActivity
}

/**
 * The frame-wide silence decision, with hysteresis and a tail.
 *
 * Silence is a property of the frame, not of each feature: the centroid of a
 * silent frame is not "0 Hz", it is unmeasurable, and the same is true of every
 * other descriptor computed from the same window. Deciding once and passing
 * [FrameActivity] to each normalizer keeps that one decision consistent, and
 * keeps a feature from having to infer silence from its own value — which
 * centroid, flatness and pan cannot do at all.
 *
 * ## Why a gate and not a threshold
 *
 * A bare threshold chatters. Music crosses any fixed level dozens of times
 * during a fade or between hits in a sparse passage, and each crossing would
 * flip every adaptive normalizer between training and not training. So:
 *
 * - it opens at [openAtRms] and only closes below [closeAtRms], 10 dB lower;
 * - once closed it stays open for [holdSeconds] more, covering the gap
 *   between hits and the tail of a decay.
 *
 * Both biases point the same way — toward Sounding — because the two mistakes
 * are not equal. Calling music silent freezes the adaptive scales and drops
 * every feature to rest for as long as the mistake lasts, which is a visible
 * glitch. Calling silence music lets a floor drift over seconds, which is not.
 */
class SilenceGate(
    hopRateHz: Float,
    private val openAtRms: Float = OPEN_AT_RMS,
    private val closeAtRms: Float = CLOSE_AT_RMS,
    holdSeconds: Float = HOLD_SECONDS,
) {
    init {
        require(hopRateHz > 0f) { "hopRateHz must be positive, was $hopRateHz" }
        require(openAtRms > 0f && closeAtRms > 0f) { "thresholds must be positive" }
        require(closeAtRms <= openAtRms) { "closeAtRms ($closeAtRms) must not be above openAtRms ($openAtRms)" }
        require(holdSeconds >= 0f) { "holdSeconds must not be negative, was $holdSeconds" }
    }

    private val holdFrames = (holdSeconds * hopRateHz).roundToInt().coerceAtLeast(0)
    private var remaining = 0

    /** The last decision; [Silent][FrameActivity.Silent] until something sounds. */
    var activity: FrameActivity = FrameActivity.Silent
        private set

    /** Decides on this frame from its [rms] and returns the decision. */
    fun update(rms: Float): FrameActivity {
        val open = rms >= openAtRms
        val stillOpen = activity == FrameActivity.Sounding && rms >= closeAtRms
        activity =
            when {
                open || stillOpen -> {
                    remaining = holdFrames
                    FrameActivity.Sounding
                }
                remaining > 0 -> {
                    remaining--
                    FrameActivity.Sounding
                }
                else -> FrameActivity.Silent
            }
        return activity
    }

    /** Back to silence, as at the start of a session. */
    fun reset() {
        remaining = 0
        activity = FrameActivity.Silent
    }

    companion object {
        /**
         * −60 dBFS RMS. Well above 16-bit quantisation noise (about −101 dBFS
         * RMS for a full-scale reference), and below the quietest passage of
         * anything mastered for listening.
         */
        const val OPEN_AT_RMS = 1e-3f

        /** −70 dBFS RMS: 10 dB of hysteresis under [OPEN_AT_RMS]. */
        const val CLOSE_AT_RMS = 3.1622776e-4f

        /**
         * How long a gap below [CLOSE_AT_RMS] stays "still playing".
         *
         * The two thresholds already cover ordinary playing — between hits a
         * track sits far above −70 dBFS on decay and room alone — so this is
         * for the gaps that really do go quiet: a stop, a breakdown, the space
         * between two phrases. A quarter second holds through those without
         * making the end of a track take noticeably long to register.
         */
        const val HOLD_SECONDS = 0.25f
    }
}
