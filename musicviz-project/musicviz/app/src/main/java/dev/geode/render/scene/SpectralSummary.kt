package dev.geode.render.scene

class SpectralSummary {
    val levels: FloatArray = FloatArray(SIZE)

    private val target = FloatArray(SIZE)

    fun reset() {
        levels.fill(0f)
    }

    fun advance(
        bands: FloatArray,
        dt: Float,
    ) {
        summarize(bands, target)
        for (i in 0 until SIZE) {
            val goal = target[i]
            val k =
                HyperspaceMath.smoothing(
                    dt,
                    if (goal > levels[i]) ATTACK_SECONDS else RELEASE_SECONDS,
                )
            val next = levels[i] + (goal - levels[i]) * k
            levels[i] = if (next.isFinite()) next.coerceIn(0f, LEVEL_CEILING) else 0f
        }
    }

    companion object {
        const val SIZE: Int = 16

        const val ATTACK_SECONDS: Float = 0.06f
        const val RELEASE_SECONDS: Float = 0.32f
        const val LEVEL_CEILING: Float = 1.5f

        fun summarize(
            bands: FloatArray,
            out: FloatArray,
        ) {
            val n = out.size
            if (bands.isEmpty()) {
                out.fill(0f)
                return
            }
            for (i in 0 until n) {
                val lo = i * bands.size / n
                val hi = (((i + 1) * bands.size / n).coerceAtLeast(lo + 1)).coerceAtMost(bands.size)
                var sum = 0f
                for (j in lo until hi) sum += bands[j].coerceIn(0f, LEVEL_CEILING)
                out[i] = sum / (hi - lo)
            }
        }
    }
}
