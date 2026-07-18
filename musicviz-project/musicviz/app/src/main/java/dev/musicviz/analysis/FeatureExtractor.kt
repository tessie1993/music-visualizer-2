package dev.musicviz.analysis

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Derives musical features from per-frame band spectra: RMS proxy, band
 * energy groups, spectral-flux onsets, beat pulses and a BPM estimate via
 * autocorrelation of the onset envelope. Pure JVM; one instance per worker.
 */
class FeatureExtractor(
    private val bandCount: Int = 64,
    private val hopRateHz: Float = 60f,
    historySeconds: Float = 6f,
) {
    private val prevBands = FloatArray(bandCount)
    private val historySize = (hopRateHz * historySeconds).toInt()
    private val fluxHistory = FloatArray(historySize)
    private var historyIndex = 0
    private var historyFilled = 0
    private var framesSinceBeat = 100
    private var bpmSmoothed = 0f

    fun extract(
        bands: FloatArray,
        waveform: FloatArray,
        sampleRateHz: Int,
    ): AudioFeatures {
        var flux = 0f
        var sum = 0f
        var sumSq = 0f
        var weighted = 0f
        for (i in 0 until bandCount) {
            val v = bands[i]
            flux += max(0f, v - prevBands[i])
            prevBands[i] = v
            sum += v
            sumSq += v * v
            weighted += v * i
        }
        val centroid = if (sum > 1e-6f) weighted / (sum * bandCount) else 0f
        val rms = sqrt(sumSq / bandCount)

        fluxHistory[historyIndex] = flux
        historyIndex = (historyIndex + 1) % historySize
        historyFilled = minOf(historyFilled + 1, historySize)

        val (mean, std) = fluxStats()
        val threshold = mean + 1.6f * std
        val isBeat = flux > threshold && flux > 0.02f && framesSinceBeat > (hopRateHz / 5f).toInt()
        framesSinceBeat = if (isBeat) 0 else framesSinceBeat + 1

        if (historyFilled >= historySize / 2) {
            val bpm = estimateBpm()
            if (bpm > 0f) bpmSmoothed = if (bpmSmoothed == 0f) bpm else bpmSmoothed + (bpm - bpmSmoothed) * 0.1f
        }

        return AudioFeatures(
            bands = bands.copyOf(),
            waveform = waveform.copyOf(),
            rms = rms,
            bass = groupEnergy(bands, 0, bandCount / 8),
            mid = groupEnergy(bands, bandCount / 8, bandCount / 2),
            treble = groupEnergy(bands, bandCount / 2, bandCount),
            onset = if (std > 1e-6f) ((flux - mean) / (3f * std)).coerceIn(0f, 1f) else 0f,
            beat = isBeat,
            bpm = bpmSmoothed,
            centroid = centroid,
        )
    }

    private fun groupEnergy(
        bands: FloatArray,
        from: Int,
        to: Int,
    ): Float {
        var acc = 0f
        for (i in from until to) acc += bands[i]
        return acc / (to - from)
    }

    private fun fluxStats(): Pair<Float, Float> {
        if (historyFilled == 0) return 0f to 0f
        var mean = 0f
        for (i in 0 until historyFilled) mean += fluxHistory[i]
        mean /= historyFilled
        var variance = 0f
        for (i in 0 until historyFilled) {
            val d = fluxHistory[i] - mean
            variance += d * d
        }
        return mean to sqrt(variance / historyFilled)
    }

    /** Autocorrelation of the onset envelope over lags covering 60-200 BPM. */
    internal fun estimateBpm(): Float {
        val minLag = (hopRateHz * 60f / 200f).toInt().coerceAtLeast(2)
        val maxLag = (hopRateHz * 60f / 60f).toInt().coerceAtMost(historyFilled / 2)
        if (maxLag <= minLag) return 0f
        var bestLag = 0
        var bestScore = 0f
        for (lag in minLag..maxLag) {
            var score = 0f
            for (i in 0 until historyFilled - lag) {
                score += chronological(i) * chronological(i + lag)
            }
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        return if (bestLag > 0) hopRateHz * 60f / bestLag else 0f
    }

    private fun chronological(i: Int): Float {
        val start = if (historyFilled < historySize) 0 else historyIndex
        return fluxHistory[(start + i) % historySize]
    }
}
