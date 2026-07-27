package dev.musicviz.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyDetectorTest {
    /** Synthesizes magnitudes with energy at the given pitch classes. */
    private fun frameWith(
        pcs: Set<Int>,
        sampleRate: Int = 44100,
        fftSize: Int = 2048,
    ): FloatArray {
        val mags = FloatArray(fftSize / 2)
        for (k in 1 until mags.size) {
            val f = k.toFloat() * sampleRate / fftSize
            if (f < 60f || f > 5000f) continue
            val midi = (69.0 + 12.0 * kotlin.math.log2(f / 440.0))
            val pc = ((Math.round(midi) % 12) + 12) % 12
            if (pc.toInt() in pcs) mags[k] = 1f
        }
        return mags
    }

    @Test
    fun detectsCMajorFromTriadPlusScale() {
        val d = KeyDetector()
        // C major scale pitch classes: C D E F G A B, tonic-heavy.
        repeat(40) { d.accumulate(frameWith(setOf(0, 4, 7)), 44100, 2048) }
        repeat(10) { d.accumulate(frameWith(setOf(0, 2, 4, 5, 7, 9, 11)), 44100, 2048) }
        assertEquals("C major", d.finish())
    }

    @Test
    fun detectsAMinor() {
        val d = KeyDetector()
        // A minor triad (A C E) with the natural-minor scale colour.
        repeat(40) { d.accumulate(frameWith(setOf(9, 0, 4)), 44100, 2048) }
        repeat(10) { d.accumulate(frameWith(setOf(9, 11, 0, 2, 4, 5, 7)), 44100, 2048) }
        assertEquals("A minor", d.finish())
    }

    @Test
    fun compactLabels() {
        assertEquals("Am", KeyDetector.compact("A minor"))
        assertEquals("F#", KeyDetector.compact("F# major"))
        assertEquals("", KeyDetector.compact(""))
    }
}
