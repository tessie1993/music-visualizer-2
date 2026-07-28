package dev.musicviz.analysis

import kotlin.math.sqrt

/** One analysis frame at a fixed hop, produced by offline analysis. */
data class TimelineFrame(
    val timeMs: Long,
    val features: AudioFeatures,
)

/**
 * Full-track analysis result: frames at a fixed hop plus track-level summary.
 * Used by the intelligence modes and by deterministic export.
 */
class FeatureTimeline(
    val frames: List<TimelineFrame>,
    val hopMs: Long,
    /** Estimated musical key, e.g. "A minor"; empty when unknown. */
    val key: String = "",
) {
    val durationMs: Long = frames.lastOrNull()?.timeMs ?: 0L
    val averageEnergy: Float = if (frames.isEmpty()) 0f else frames.map { it.features.rms }.average().toFloat()
    val averageCentroid: Float = if (frames.isEmpty()) 0f else frames.map { it.features.centroid }.average().toFloat()
    val bpm: Float = frames.lastOrNull()?.features?.bpm ?: 0f
    val beatDensity: Float =
        if (frames.isEmpty()) 0f else frames.count { it.features.beat } / (frames.size / 60f + 1e-6f)

    fun featuresAt(timeMs: Long): AudioFeatures {
        if (frames.isEmpty()) return AudioFeatures.empty()
        // Index by the frames' actual spacing (durationMs / (n-1)), not the
        // nominal hopMs: the offline hop is sampleRate/60 samples (16.67 ms),
        // so dividing by a truncated 16 ms would drift ~4% over a track.
        val spacing = if (frames.size > 1) durationMs.toDouble() / (frames.size - 1) else hopMs.toDouble()
        val index =
            if (spacing > 0.0) {
                Math.round(timeMs / spacing).toInt().coerceIn(0, frames.size - 1)
            } else {
                0
            }
        return frames[index].features
    }

    /**
     * [featuresAt] plus track-position context (progress + section index)
     * for progression-driven scenes. [sections] is a detectSections() result
     * the caller computed once - recomputing per frame would be O(n) each.
     * Deterministic, so live playback and export agree exactly.
     */
    fun progressionAt(
        timeMs: Long,
        sections: List<Long>,
    ): AudioFeatures {
        val f = featuresAt(timeMs)
        if (durationMs <= 0L) return f
        var idx = 0
        for (s in sections) {
            if (s <= timeMs) idx++ else break
        }
        return f.copy(
            progress = (timeMs.toFloat() / durationMs).coerceIn(0f, 1f),
            sectionIndex = idx,
            sectionCount = sections.size + 1,
        )
    }

    /**
     * Section boundaries (ms) from a novelty curve: distance between averaged
     * band vectors before/after each frame, peak-picked.
     */
    fun detectSections(
        windowFrames: Int = 90,
        minGapFrames: Int = 300,
    ): List<Long> {
        if (frames.size < windowFrames * 2) return emptyList()
        val novelty = FloatArray(frames.size)
        val bandCount = frames[0].features.bands.size
        val before = FloatArray(bandCount)
        val after = FloatArray(bandCount)
        for (i in windowFrames until frames.size - windowFrames) {
            java.util.Arrays.fill(before, 0f)
            java.util.Arrays.fill(after, 0f)
            for (w in 0 until windowFrames) {
                val fb = frames[i - 1 - w].features.bands
                val fa = frames[i + w].features.bands
                for (b in 0 until bandCount) {
                    before[b] += fb[b]
                    after[b] += fa[b]
                }
            }
            var dist = 0f
            for (b in 0 until bandCount) {
                val d = (after[b] - before[b]) / windowFrames
                dist += d * d
            }
            novelty[i] = sqrt(dist)
        }
        val mean = novelty.average().toFloat()
        val threshold = mean * 2f
        val boundaries = mutableListOf<Long>()
        var lastPeak = -minGapFrames
        for (i in 1 until novelty.size - 1) {
            val isPeak = novelty[i] > threshold && novelty[i] >= novelty[i - 1] && novelty[i] >= novelty[i + 1]
            if (isPeak && i - lastPeak >= minGapFrames) {
                boundaries += frames[i].timeMs
                lastPeak = i
            }
        }
        return boundaries
    }
}
