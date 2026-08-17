package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stowell & Plumbley's adaptive whitening, as the onset branch's preprocessor.
 *
 * The published claim is that normalizing each band by a decaying record of its
 * own recent peak "allow[s] each bin to achieve a similar dynamic range over
 * time and mitigate[s] against spectral roll-off and strongly-varying
 * dynamics", and is worth up to ten points of peak F-measure on flux-based
 * detectors. The tests below are the two halves of that sentence: equal
 * dynamic range across bands of very different levels, and equal response
 * across masters of very different levels.
 */
class AdaptiveWhiteningTest {
    private val dt = 1f / 60f

    private fun drive(
        whitening: AdaptiveWhitening,
        frames: Int,
        value: (band: Int, frame: Int) -> Float,
    ): FloatArray {
        val input = FloatArray(whitening.bandCount)
        val out = FloatArray(whitening.bandCount)
        repeat(frames) { f ->
            for (b in input.indices) input[b] = value(b, f)
            whitening.whiten(input, dt, out)
        }
        return out
    }

    /** A held tone whitens to 1 whatever its level — the loudness-independence half. */
    @Test
    fun `a held level whitens to one at any amplitude`() {
        val loud = drive(AdaptiveWhitening(1), 120) { _, _ -> 1e-2f }
        val quiet = drive(AdaptiveWhitening(1), 120) { _, _ -> 1e-6f }
        assertEquals(1f, loud[0], 1e-3f)
        assertEquals(1f, quiet[0], 1e-3f)
    }

    /** Bands 60 dB apart end up with the same range — the roll-off half. */
    @Test
    fun `bands of very different levels reach the same range`() {
        val out = drive(AdaptiveWhitening(3), 120) { b, _ -> 1e-2f / (1000f * b + 1f) }
        assertEquals(out[0], out[1], 1e-3f)
        assertEquals(out[1], out[2], 1e-3f)
    }

    /**
     * The peak profile has to decay, or one loud moment deafens the band for
     * the rest of the track. After a hit and a drop to a quarter, the band
     * should climb back toward 1 as the profile forgets.
     */
    @Test
    fun `the peak profile decays so a quieter passage recovers`() {
        val whitening = AdaptiveWhitening(1, peakDecaySeconds = 0.5f)
        drive(whitening, 30) { _, _ -> 1f }
        val input = FloatArray(1)
        val out = FloatArray(1)

        input[0] = 0.25f
        whitening.whiten(input, dt, out)
        val immediatelyAfter = out[0]

        repeat(120) { whitening.whiten(input, dt, out) }
        assertTrue(
            "straight after the hit $immediatelyAfter, two seconds later ${out[0]}",
            out[0] > immediatelyAfter + 0.5f,
        )
        assertEquals(1f, out[0], 0.05f)
    }

    /**
     * The floor is what stops the whitening from turning a noise floor into a
     * full-scale signal — the failure that makes naive per-bin normalizers
     * unusable on quiet material.
     */
    @Test
    fun `a band below the floor stays near zero instead of being amplified`() {
        val whitening = AdaptiveWhitening(1, floor = 1e-6f)
        val out = drive(whitening, 300) { _, _ -> 1e-9f }
        assertTrue("read ${out[0]}", out[0] < 0.01f)
    }

    @Test
    fun `output is bounded to zero and one`() {
        val whitening = AdaptiveWhitening(2)
        val input = FloatArray(2)
        val out = FloatArray(2)
        repeat(200) { f ->
            input[0] = if (f % 7 == 0) 5f else 1e-8f
            input[1] = f.toFloat()
            whitening.whiten(input, dt, out)
            assertTrue("band 0 read ${out[0]}", out[0] in 0f..1f)
            assertTrue("band 1 read ${out[1]}", out[1] in 0f..1f)
        }
    }

    /** A rise above the current profile is tracked instantly: onsets are peaks. */
    @Test
    fun `a new peak is adopted immediately`() {
        val whitening = AdaptiveWhitening(1)
        val input = FloatArray(1)
        val out = FloatArray(1)
        input[0] = 0.1f
        repeat(60) { whitening.whiten(input, dt, out) }
        input[0] = 10f
        whitening.whiten(input, dt, out)
        assertEquals(1f, out[0], 1e-6f)
    }

    @Test
    fun `the decay is the same over a wall-clock second at any hop rate`() {
        fun recoveryAt(hopHz: Int): Float {
            val whitening = AdaptiveWhitening(1, peakDecaySeconds = 0.5f)
            val input = FloatArray(1)
            val out = FloatArray(1)
            input[0] = 1f
            whitening.whiten(input, 1f / hopHz, out)
            input[0] = 0.1f
            repeat(hopHz) { whitening.whiten(input, 1f / hopHz, out) }
            return out[0]
        }
        assertEquals(recoveryAt(30), recoveryAt(240), 0.02f)
    }

    @Test
    fun `reset forgets the profile`() {
        val whitening = AdaptiveWhitening(1)
        val input = FloatArray(1)
        val out = FloatArray(1)
        input[0] = 1f
        whitening.whiten(input, dt, out)
        whitening.reset()
        input[0] = 1e-4f
        whitening.whiten(input, dt, out)
        assertEquals(1f, out[0], 1e-6f)
    }
}
