package dev.geode.analysis

import kotlin.math.sqrt

/**
 * Stereo image measurements over a mid/side window from [dev.geode.audio.PcmRingBuffer].
 *
 * Two numbers, both of which a visual can use directly and neither of which
 * any amount of spectrum analysis can recover, because the mono downmix that
 * feeds the FFT has already destroyed them.
 *
 * Pure JVM, allocation-free, stateless.
 */
object StereoField {
    /**
     * One window's stereo image. A value rather than two loose floats so a
     * caller cannot pass them in the wrong order, and so [MONO] can name the
     * reading a mono source genuinely produces.
     */
    data class Reading(
        val width: Float,
        val correlation: Float,
    )

    /** What a mono source measures: no side energy, perfectly correlated. */
    val MONO = Reading(width = 0f, correlation = 1f)

    /**
     * Both measurements over one mid/side window.
     *
     * Take this over the FULL analysis window, not over the decimated
     * waveform a scene is handed: [correlation] is phase-sensitive, and
     * dropping fifteen of every sixteen samples without filtering aliases high
     * frequencies down onto low ones, which is exactly where a phase
     * relationship gets inverted.
     */
    fun of(
        mid: FloatArray,
        side: FloatArray,
        count: Int = minOf(mid.size, side.size),
    ): Reading = Reading(width(mid, side, count), correlation(mid, side, count))

    /**
     * Interchannel correlation in -1..1, the quantity a studio correlation
     * meter shows.
     *
     * +1 is mono or a hard-panned single source, 0 is fully decorrelated (wide
     * reverb, a doubled guitar), and negative means out of phase - which is
     * the reading engineers actually watch for, because it is what collapses
     * to silence on a mono speaker.
     *
     * Computed from mid/side without reconstructing L and R, using
     * L = m + s, R = m - s:
     *
     *     sum(L*R)  = sum(m^2) - sum(s^2)
     *     sum(L*L)  = sum(m^2) + 2*sum(m*s) + sum(s^2)
     *     sum(R*R)  = sum(m^2) - 2*sum(m*s) + sum(s^2)
     *
     * so one pass over the pair suffices.
     *
     * Silence returns 1 rather than 0: a silent passage is not "wide", and
     * returning 0 there would make every gap between tracks read as maximum
     * decorrelation and swing anything driven by it.
     */
    fun correlation(
        mid: FloatArray,
        side: FloatArray,
        count: Int = minOf(mid.size, side.size),
    ): Float {
        var mm = 0f
        var ss = 0f
        var ms = 0f
        for (i in 0 until count) {
            val m = mid[i]
            val s = side[i]
            mm += m * m
            ss += s * s
            ms += m * s
        }
        val ll = mm + 2f * ms + ss
        val rr = mm - 2f * ms + ss
        // Each is a sum of squares on paper, but computed as a difference of
        // near-equal float sums: a channel that is nearly-but-not-exactly
        // silent can cancel one a hair below zero, which would put NaN
        // through the sqrt. Floored at zero, that case lands in the silence
        // branch below, which already answers it - a channel that quiet has
        // no phase relationship to report.
        val denom = sqrt(ll.coerceAtLeast(0f) * rr.coerceAtLeast(0f))
        if (denom <= SILENCE) return 1f
        return ((mm - ss) / denom).coerceIn(-1f, 1f)
    }

    /**
     * Stereo width in 0..1: how much of the signal's energy is in the side
     * channel.
     *
     * `rms(S) / (rms(M) + rms(S))`, so 0 is exactly mono, 0.5 is a
     * hard-panned single source (equal mid and side energy), and 1 is a purely
     * out-of-phase difference signal with no mono content at all.
     *
     * Deliberately NOT `1 - correlation`: those two answer different
     * questions. A quiet, wide pad and a loud, wide pad have the same
     * correlation; this says how much of what is playing is the wide part,
     * which is the one that reads on screen. Silence returns 0 - no width,
     * for the same reason correlation returns 1 there.
     */
    fun width(
        mid: FloatArray,
        side: FloatArray,
        count: Int = minOf(mid.size, side.size),
    ): Float {
        if (count <= 0) return 0f
        var mm = 0f
        var ss = 0f
        for (i in 0 until count) {
            mm += mid[i] * mid[i]
            ss += side[i] * side[i]
        }
        val m = sqrt(mm / count)
        val s = sqrt(ss / count)
        val total = m + s
        if (total <= SILENCE) return 0f
        return (s / total).coerceIn(0f, 1f)
    }

    /**
     * Energy floor below which a window is treated as silence rather than as
     * a stereo image. Well under any dithered digital silence, and far under
     * anything audible.
     */
    private const val SILENCE = 1e-9f
}
