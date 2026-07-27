package dev.musicviz.analysis

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Windowed FFT -> log-spaced frequency bands, normalized to [0, 1].
 *
 * Pure JVM (JTransforms), so it is unit-testable without Android.
 * Not thread-safe; confine one instance to one worker.
 */
class FftProcessor(
    val fftSize: Int = 2048,
    val bandCount: Int = 64,
    private val minFreqHz: Float = 40f,
    private val floorDb: Float = -72f,
) {
    private val fft = FloatFFT_1D(fftSize.toLong())
    private val window = FloatArray(fftSize) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat() }
    private val work = FloatArray(fftSize)
    private val magnitudes = FloatArray(fftSize / 2)

    /** Folds the magnitudes of the LAST [process] call into [detector]. */
    fun accumulateChroma(
        detector: KeyDetector,
        sampleRateHz: Int,
    ) {
        detector.accumulate(magnitudes, sampleRateHz, fftSize)
    }

    /** Maps each band to an inclusive range of FFT bin indices; recomputed per sample rate. */
    internal fun bandEdges(sampleRateHz: Int): IntArray {
        val nyquist = sampleRateHz / 2f
        val bins = fftSize / 2
        val edges = IntArray(bandCount + 1)
        val logMin = ln(minFreqHz.toDouble())
        val logMax = ln(nyquist.toDouble())
        for (b in 0..bandCount) {
            val f = kotlin.math.exp(logMin + (logMax - logMin) * b / bandCount)
            edges[b] = min(bins - 1, max(1, (f / nyquist * bins).toInt()))
        }
        for (b in 1..bandCount) {
            if (edges[b] <= edges[b - 1]) edges[b] = min(bins - 1, edges[b - 1] + 1)
        }
        return edges
    }

    /**
     * Computes band magnitudes for [samples] (size must equal [fftSize]) into [outBands]
     * (size must equal [bandCount]), normalized 0..1 on a dB scale.
     */
    fun process(
        samples: FloatArray,
        sampleRateHz: Int,
        outBands: FloatArray,
    ) {
        require(samples.size == fftSize) { "expected $fftSize samples" }
        require(outBands.size == bandCount) { "expected $bandCount bands" }
        for (i in 0 until fftSize) work[i] = samples[i] * window[i]
        fft.realForward(work)
        magnitudes[0] = 0f
        for (k in 1 until fftSize / 2) {
            val re = work[2 * k]
            val im = work[2 * k + 1]
            magnitudes[k] = sqrt(re * re + im * im) / (fftSize / 2f)
        }
        val edges = edgesFor(sampleRateHz)
        for (b in 0 until bandCount) {
            var peak = 0f
            for (k in edges[b]..edges[b + 1]) peak = max(peak, magnitudes[k])
            val db = 20f * log10(max(peak, 1e-9f))
            outBands[b] = ((db - floorDb) / -floorDb).coerceIn(0f, 1f)
        }
    }

    private var cachedRate = -1
    private lateinit var cachedEdges: IntArray

    private fun edgesFor(sampleRateHz: Int): IntArray {
        if (sampleRateHz != cachedRate) {
            cachedEdges = bandEdges(sampleRateHz)
            cachedRate = sampleRateHz
        }
        return cachedEdges
    }
}
