package dev.geode.engine.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Triangular mel filters over the power spectrum.
 *
 * The HTK mel formula, `2595 log10(1 + f/700)`, with unit-peak triangles —
 * the corpus oracle's `librosa.filters.mel(htk=True, norm=None)` stated as
 * arithmetic: `melCount + 2` points equally spaced in mel between 0 and
 * Nyquist, converted back to Hz, and filter `m` rising over
 * `[edge(m), edge(m+1)]` and falling over `[edge(m+1), edge(m+2)]`.
 *
 * Weights are precomputed per filter as a dense run of bins, so [power] is
 * one multiply-add per covered bin with no allocation.
 */
class MelBank(
    fftSize: Int,
    sampleRateHz: Int,
    val melCount: Int = 40,
) {
    init {
        require(melCount > 1) { "melCount must exceed 1, was $melCount" }
    }

    private val firstBin = IntArray(melCount)
    private val weights: Array<DoubleArray>

    init {
        val nyquist = sampleRateHz / 2.0
        val melTop = hzToMel(nyquist)
        val edges = DoubleArray(melCount + 2) { melToHz(melTop * it / (melCount + 1)) }
        val binHz = sampleRateHz.toDouble() / fftSize
        val bins = fftSize / 2 + 1

        weights =
            Array(melCount) { m ->
                val lo = edges[m]
                val mid = edges[m + 1]
                val hi = edges[m + 2]
                var start = -1
                var end = -1
                val scratch = DoubleArray(bins)
                for (k in 0 until bins) {
                    val f = k * binHz
                    val w = max(0.0, min((f - lo) / (mid - lo), (hi - f) / (hi - mid)))
                    if (w > 0.0) {
                        if (start < 0) start = k
                        end = k
                        scratch[k] = w
                    }
                }
                require(start >= 0) { "mel $m of $melCount covers no bin at fftSize $fftSize, $sampleRateHz Hz" }
                firstBin[m] = start
                DoubleArray(end - start + 1) { scratch[start + it] }
            }
    }

    /**
     * Sums each filter's weighted POWER (magnitude squared) into [out],
     * which must hold [melCount] values. [magnitudes] is [Spectrum]'s
     * layout, DC through Nyquist.
     */
    fun power(
        magnitudes: FloatArray,
        out: FloatArray,
    ) {
        require(out.size == melCount) { "expected $melCount mels, got ${out.size}" }
        for (m in 0 until melCount) {
            val w = weights[m]
            val base = firstBin[m]
            var acc = 0.0
            for (i in w.indices) {
                val mag = magnitudes[base + i].toDouble()
                acc += w[i] * mag * mag
            }
            out[m] = acc.toFloat()
        }
    }

    private companion object {
        fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)

        fun melToHz(mel: Double): Double = 700.0 * (Math.pow(10.0, mel / 2595.0) - 1.0)
    }
}
