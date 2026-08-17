package dev.geode.render.scene

/**
 * Resamples raw PCM (or the analyzer's fallback waveform) onto a fixed-width
 * row, min/max-preserving so transients survive decimation.
 *
 * Naive stride sampling of 2048 samples onto 512 texels keeps one sample in
 * four and can step straight over the drum hit the row exists to show. Each
 * texel here takes the extreme of its bucket — the value furthest from zero —
 * which is what an oscilloscope column would light.
 *
 * Pure and allocation-free, shared by every scene that draws a waveform.
 */
object PcmRow {
    fun fill(
        dst: FloatArray,
        source: FloatArray,
        count: Int,
    ) {
        val n = count.coerceIn(0, source.size)
        if (n == 0) {
            dst.fill(0f)
            return
        }
        for (i in dst.indices) {
            val from = i * n / dst.size
            val to = ((i + 1) * n / dst.size).coerceAtLeast(from + 1).coerceAtMost(n)
            var extreme = 0f
            for (j in from until to) {
                val v = source[j]
                val safe = if (v.isFinite()) v else 0f
                if (kotlin.math.abs(safe) > kotlin.math.abs(extreme)) extreme = safe
            }
            dst[i] = extreme
        }
    }
}
