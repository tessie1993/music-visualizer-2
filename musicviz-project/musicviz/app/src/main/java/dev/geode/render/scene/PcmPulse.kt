package dev.geode.render.scene

import kotlin.math.abs

/**
 * How hard the music just hit, read from raw PCM.
 *
 * The analyser's bands arrive smoothed over an FFT window; a snare's first
 * millisecond is already averaged away by the time they update. This tracks
 * the true inter-frame sample peak instead and lets it decay, so a scene can
 * ask for the transient the bands cannot carry. One instance per scene, GL
 * thread only, shared by every family on the [PcmSink] feed.
 *
 * [accept] scans the chunk without keeping it (the caller reuses the buffer)
 * and [tick] reads the current level while applying the frame's decay. Both
 * allocate nothing.
 */
internal class PcmPulse(
    private val decayPerSecond: Float = 4f,
    private val ceiling: Float = 1.5f,
) {
    private var level = 0f

    fun accept(
        samples: FloatArray,
        count: Int,
    ) {
        var peak = 0f
        var i = 0
        while (i < count) {
            val s = samples[i]
            if (s.isFinite()) {
                val a = abs(s)
                if (a > peak) peak = a
            }
            i++
        }
        if (peak > level) level = peak.coerceAtMost(ceiling)
    }

    /** The level this frame, decaying [decayPerSecond] per second of [dt]. */
    fun tick(dt: Float): Float {
        val out = level
        level = (level - dt * decayPerSecond).coerceAtLeast(0f)
        return out
    }
}
