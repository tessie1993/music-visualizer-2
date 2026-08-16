package dev.musicviz.engine.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Time-domain measurements over one frame: how loud, how peaky, how noisy.
 *
 * Deliberately not "loudness". BS.1770 / EBU R128 is a K-weighted, gated,
 * multi-block measurement with its own oracle (libebur128, ORACLE tier) and it
 * is a slice of its own; calling an RMS "loudness" is how a level meter ends up
 * disagreeing with every other one on the planet.
 */
object FrameLevels {
    /** Root mean square over the frame. */
    fun rms(
        frame: FloatArray,
        count: Int = frame.size,
    ): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val x = frame[i].toDouble()
            sum += x * x
        }
        return sqrt(sum / count)
    }

    /** Largest absolute sample: what actually clips. */
    fun peak(
        frame: FloatArray,
        count: Int = frame.size,
    ): Float {
        var top = 0f
        for (i in 0 until count) {
            val magnitude = abs(frame[i])
            if (magnitude > top) top = magnitude
        }
        return top
    }

    /**
     * Sign changes per sample interval — a cheap noisiness proxy, high for
     * cymbals and fricatives, low for a bass note.
     *
     * Divided by `count - 1` because that is how many intervals a frame of
     * `count` samples has. librosa's `zero_crossing_rate` instead forces the
     * first sample to count as a crossing whatever its sign, then divides by
     * `count` — an API convention that inflates every frame by one crossing.
     * The corpus records the honest figure, computed independently, rather
     * than reproducing that quirk here.
     *
     * Zero is treated as positive, matching `numpy.signbit`, so a run of
     * silence reports no crossings instead of one per sample.
     */
    fun zeroCrossingRate(
        frame: FloatArray,
        count: Int = frame.size,
    ): Double {
        if (count < 2) return 0.0
        var crossings = 0
        var previousNegative = frame[0] < 0f
        for (i in 1 until count) {
            val negative = frame[i] < 0f
            if (negative != previousNegative) crossings++
            previousNegative = negative
        }
        return crossings.toDouble() / (count - 1)
    }
}
