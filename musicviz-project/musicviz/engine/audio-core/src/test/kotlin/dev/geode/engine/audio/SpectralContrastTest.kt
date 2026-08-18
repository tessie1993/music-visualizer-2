package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The corners the corpus cannot reach; the oracle sweep lives app-side. */
class SpectralContrastTest {
    private val nFft = 1024
    private val sampleRate = 22_050

    private fun node() = SpectralContrast(nFft, sampleRate)

    @Test
    fun `silence reads zero contrast in every band, finitely`() {
        val out = FloatArray(6)
        node().compute(FloatArray(nFft / 2 + 1), out)
        for (b in 0 until 6) {
            assertTrue("band $b not finite", out[b].isFinite())
            assertEquals("band $b of silence", 0f, out[b], 1e-6f)
        }
    }

    @Test
    fun `a flat band has no contrast and a spiked band has plenty`() {
        val magnitudes = FloatArray(nFft / 2 + 1) { 0.01f }
        val binHz = sampleRate.toFloat() / nFft
        // One loud bin inside band 2 (800..1600 Hz).
        magnitudes[(1000f / binHz).toInt()] = 1f
        val out = FloatArray(6)
        node().compute(magnitudes, out)
        assertTrue("spiked band read ${out[2]} dB", out[2] > 20f)
        assertTrue("flat band read ${out[4]} dB", out[4] < 1f)
    }

    @Test
    fun `every band holds at least one bin at this configuration`() {
        // The top band is clipped by Nyquist; if a configuration ever leaves
        // a band empty the node must refuse at construction, not divide by
        // zero per frame. This pins the refusal.
        node()
        var refused = false
        try {
            SpectralContrast(64, 8_000, bands = 6)
        } catch (e: IllegalArgumentException) {
            refused = true
        }
        assertTrue("an empty band must refuse at construction", refused)
    }
}
