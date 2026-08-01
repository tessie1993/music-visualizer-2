package dev.musicviz.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Brings microphone samples up to the level the analysis was tuned for.
 *
 * Everything downstream - the FFT bands, the beat tracker's threshold, the
 * fluid emitters, the drop amplitudes - was calibrated against the playback
 * tap, whose samples are a decoded track at something near full scale. A
 * microphone in a room is nowhere near that: speech and music across a room
 * land around -35 dBFS, and [MicCapture] asks for `UNPROCESSED` on top of
 * that, which switches OFF the automatic gain the voice sources would have
 * applied. The samples are correct and they are tiny, so the visuals barely
 * moved - the "mic input is registered too soft" report.
 *
 * Rather than re-tune every downstream threshold for a second source, this
 * lifts the source to meet them. Two stages:
 *
 *  - **Automatic**, tracking the room's own level toward [TARGET_RMS] so a
 *    quiet flat and a loud party both arrive usable. Levels move over seconds
 *    here, not milliseconds: this is not a compressor and must not squash the
 *    beat-to-beat dynamics the beat tracker keys off, so it follows the
 *    ENVELOPE and leaves the transients alone.
 *  - **Manual** ([process]'s `userGain`), the Settings "Mic sensitivity"
 *    slider, applied on top so the user always has the last word.
 *
 * Pure and allocation-free so the mapping can be pinned without a microphone;
 * [process] rewrites the block in place on the capture thread.
 */
class MicGain {
    /** Smoothed room level, in RMS. */
    private var envelope = 0f

    /** Gain actually being applied, smoothed so it never steps audibly. */
    private var gain = 1f

    /** The automatic gain in force, for a level meter. 1 until the first block. */
    val autoGain: Float get() = gain

    /**
     * Scales [n] frames of [buf] in place and returns the automatic gain used.
     *
     * [userGain] is the user's own multiplier, applied after the automatic
     * stage and NOT fed back into it - otherwise turning the slider up would
     * make the follower turn itself down to compensate, and the control would
     * do nothing.
     */
    fun process(
        buf: FloatArray,
        n: Int,
        userGain: Float,
    ): Float {
        if (n <= 0) return gain
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        val rms = sqrt(sum / n).toFloat()
        // Asymmetric follower: rise fast so a track starting is not clipped
        // through by a stale low envelope, fall slowly so a gap between songs
        // does not ramp the room noise up into the visuals.
        val k = if (rms > envelope) ATTACK else RELEASE
        envelope += (rms - envelope) * k
        // Silence must not be amplified: with no floor, a quiet room would
        // drive the gain to its ceiling and turn the preamp hiss into a
        // light show.
        val target =
            if (envelope < NOISE_FLOOR) {
                1f
            } else {
                (TARGET_RMS / envelope).coerceIn(1f, MAX_AUTO_GAIN)
            }
        gain += (target - gain) * GAIN_SMOOTHING
        val total = gain * userGain.coerceIn(MIN_USER_GAIN, MAX_USER_GAIN)
        for (i in 0 until n) buf[i] = softClip(buf[i] * total)
        return gain
    }

    /** Forgets the room, for a fresh start when the microphone reopens. */
    fun reset() {
        envelope = 0f
        gain = 1f
    }

    private companion object {
        /**
         * Level the automatic stage aims the room at.
         *
         * Chosen to match the RMS a decoded track presents at the playback tap,
         * since matching it is the entire point - the downstream thresholds
         * cannot tell the two sources apart and must not need to.
         */
        const val TARGET_RMS = 0.18f

        /** Below this the input is treated as silence and left alone. */
        const val NOISE_FLOOR = 0.0015f

        /** Ceiling on the automatic stage: ~30 dB, enough for a quiet room. */
        const val MAX_AUTO_GAIN = 32f

        /** Slider travel: from "already loud" to "hearing it through a wall". */
        const val MIN_USER_GAIN = 0.25f
        const val MAX_USER_GAIN = 8f

        /** Per-block envelope coefficients (a block is ~23 ms). */
        const val ATTACK = 0.35f
        const val RELEASE = 0.02f

        /** Per-block gain smoothing - slower still, so nothing pumps. */
        const val GAIN_SMOOTHING = 0.08f

        /** Where the soft knee starts; below this the signal is untouched. */
        const val KNEE = 0.85f
    }

    /**
     * Keeps a boosted peak inside +-1 without the flat top a hard clamp
     * leaves.
     *
     * A clamp turns every over-range peak into a square edge, and a square
     * edge is broadband noise - it would show up in the FFT as energy across
     * every band at once, which reads as a bright flash on every beat. The
     * knee below is linear up to [KNEE] and compresses the rest asymptotically,
     * so loud input gets quieter rather than crunchy.
     */
    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val over = a - KNEE
        val room = 1f - KNEE
        val shaped = KNEE + room * (over / (over + room))
        return if (x < 0f) -shaped else shaped
    }
}
