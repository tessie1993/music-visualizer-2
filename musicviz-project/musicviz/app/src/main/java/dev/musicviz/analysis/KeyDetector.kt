package dev.musicviz.analysis

import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Musical key estimation: accumulates a 12-bin chromagram over the whole
 * track, then correlates it against the Krumhansl-Schmuckler major/minor
 * key profiles across all 24 rotations. The best-correlating rotation is
 * the key. This is the standard baseline used by DJ tools; sections in a
 * different mode average out, which matches what players like Rekordbox
 * display for a track.
 */
class KeyDetector {
    private val chroma = DoubleArray(12)
    private var frames = 0L

    /** Folds one FFT magnitude frame into the running chromagram. */
    fun accumulate(
        magnitudes: FloatArray,
        sampleRateHz: Int,
        fftSize: Int,
    ) {
        val minBin = ceil(60f * fftSize / sampleRateHz).toInt().coerceAtLeast(1)
        val maxBin = (5000f * fftSize / sampleRateHz).toInt().coerceAtMost(magnitudes.size - 1)
        for (k in minBin..maxBin) {
            val f = k.toFloat() * sampleRateHz / fftSize
            val midi = 69.0 + 12.0 * log2(f / 440.0)
            val pc = ((midi.roundToInt() % 12) + 12) % 12
            chroma[pc] += magnitudes[k].toDouble()
        }
        frames++
    }

    /** "A minor", "F# major", … or "" when nothing was accumulated. */
    fun finish(): String {
        if (frames == 0L || chroma.all { it == 0.0 }) return ""
        var bestScore = Double.NEGATIVE_INFINITY
        var bestPc = 0
        var bestMinor = false
        for (minor in booleanArrayOf(false, true)) {
            val profile = if (minor) MINOR else MAJOR
            for (root in 0 until 12) {
                var score = 0.0
                for (i in 0 until 12) score += chroma[(root + i) % 12] * profile[i]
                score /= norm(chroma) * norm(profile)
                if (score > bestScore) {
                    bestScore = score
                    bestPc = root
                    bestMinor = minor
                }
            }
        }
        return NAMES[bestPc] + if (bestMinor) " minor" else " major"
    }

    private fun norm(v: DoubleArray): Double = sqrt(v.sumOf { it * it }).coerceAtLeast(1e-9)

    companion object {
        private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        // Krumhansl-Schmuckler probe-tone profiles.
        private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

        /** "A minor" -> "Am", "F# major" -> "F#" - compact badge form. */
        fun compact(key: String): String =
            when {
                key.isBlank() -> ""
                key.endsWith(" minor") -> key.removeSuffix(" minor") + "m"
                else -> key.removeSuffix(" major")
            }
    }
}
