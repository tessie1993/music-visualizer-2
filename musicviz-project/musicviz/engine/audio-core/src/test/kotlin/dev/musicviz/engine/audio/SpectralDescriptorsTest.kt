package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundaries the corpus cannot reach.
 *
 * `DescriptorOracleTest` compares these against librosa over ~1,700 real
 * frames, which is the stronger check for anything a real signal produces.
 * What it cannot exercise is the exactly-degenerate input — a single non-zero
 * bin, a perfectly flat spectrum, a threshold landing exactly on a cumulative
 * sum — because float magnitudes from a real FFT never land there. Those are
 * here, and one of them was a fault injection that survived the oracle.
 */
class SpectralDescriptorsTest {
    private val binHz = 10.0

    private fun spike(
        bins: Int,
        at: Int,
        value: Float = 1f,
    ) = FloatArray(bins).also { it[at] = value }

    @Test
    fun `a single bin is its own centroid, with no spread`() {
        val spectrum = spike(bins = 16, at = 5)
        assertEquals(50.0, SpectralDescriptors.centroidHz(spectrum, binHz), 0.0)
        assertEquals(0.0, SpectralDescriptors.bandwidthHz(spectrum, binHz, 50.0), 1e-12)
        assertEquals(50.0, SpectralDescriptors.rolloffHz(spectrum, binHz), 0.0)
    }

    @Test
    fun `two equal bins put the centroid between them and the spread at their half-distance`() {
        val spectrum =
            FloatArray(16).also {
                it[4] = 1f
                it[8] = 1f
            }
        assertEquals(60.0, SpectralDescriptors.centroidHz(spectrum, binHz), 1e-12)
        assertEquals(20.0, SpectralDescriptors.bandwidthHz(spectrum, binHz, 60.0), 1e-12)
    }

    @Test
    fun `asking for all of the energy returns the highest bin that carries any`() {
        // The case a strict `>` comparison gets wrong: at fraction 1.0 the
        // running sum reaches the threshold exactly at the last bin with
        // energy, so `> threshold` never fires and the function falls through
        // to its bound. A fault injection swapping `>=` for `>` survived the
        // whole corpus, because real float magnitudes never land exactly on a
        // threshold.
        val spectrum =
            FloatArray(16).also {
                it[2] = 1f
                it[7] = 1f
            }
        assertEquals(70.0, SpectralDescriptors.rolloffHz(spectrum, binHz, fraction = 1.0), 0.0)
        assertEquals("half the energy is reached at the first bin", 20.0, SpectralDescriptors.rolloffHz(spectrum, binHz, 0.5), 0.0)
    }

    @Test
    fun `rolloff refuses a fraction outside its range`() {
        listOf(0.0, -0.5, 1.5).forEach { fraction ->
            try {
                SpectralDescriptors.rolloffHz(spike(8, 1), binHz, fraction)
                error("expected $fraction to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("fraction"))
            }
        }
    }

    @Test
    fun `flatness is one for a flat spectrum and near zero for a spike`() {
        // The definition, at its two extremes: geometric over arithmetic mean
        // is 1 only when every bin is equal.
        val flat = FloatArray(32) { 0.5f }
        assertEquals(1.0, SpectralDescriptors.flatness(flat), 1e-12)

        val tonal = spike(bins = 32, at = 10, value = 1f)
        assertTrue("a single spike should read as tonal", SpectralDescriptors.flatness(tonal) < 1e-3)
    }

    @Test
    fun `one silent bin does not collapse the whole flatness measure`() {
        // Without the power floor, ln(0) is -infinity and the geometric mean
        // goes to zero, so any spectrum containing a single exactly-zero bin
        // reads as perfectly tonal. Real spectra contain them.
        val nearlyFlat = FloatArray(32) { 0.5f }.also { it[7] = 0f }
        val flatness = SpectralDescriptors.flatness(nearlyFlat)
        assertTrue("one zero bin collapsed flatness to $flatness", flatness > 0.3)
        assertTrue(flatness.isFinite())
    }

    @Test
    fun `a silent spectrum has no shape rather than a NaN`() {
        val silent = FloatArray(16)
        assertEquals(0.0, SpectralDescriptors.centroidHz(silent, binHz), 0.0)
        assertEquals(0.0, SpectralDescriptors.bandwidthHz(silent, binHz, 0.0), 0.0)
        assertEquals(0.0, SpectralDescriptors.rolloffHz(silent, binHz), 0.0)
        assertTrue(SpectralDescriptors.flatness(silent).isFinite())
    }

    @Test
    fun `flux reports nothing on its first frame and only rises after`() {
        val flux = SpectralFlux(binCount = 4)
        val quiet = floatArrayOf(0f, 0f, 0f, 0f)
        val loud = floatArrayOf(1f, 1f, 1f, 1f)

        assertEquals("the first frame has no predecessor to differ from", 0.0, flux.next(loud), 0.0)
        assertEquals("a fall is not an onset", 0.0, flux.next(quiet), 0.0)
        assertEquals("a rise of 1 across 4 bins", 1.0, flux.next(loud), 1e-12)

        flux.reset()
        assertEquals("after a reset there is no predecessor again", 0.0, flux.next(quiet), 0.0)
    }

    @Test
    fun `zero counts as positive, so silence has no crossings`() {
        // numpy.signbit's convention, and the one the corpus is generated
        // with. Treating zero as negative would make every silent frame report
        // a crossing at every sample.
        assertEquals(0.0, FrameLevels.zeroCrossingRate(FloatArray(64)), 0.0)
        assertEquals(1.0, FrameLevels.zeroCrossingRate(floatArrayOf(1f, -1f, 1f, -1f)), 1e-12)
        assertEquals("three intervals, one sign change", 1.0 / 3.0, FrameLevels.zeroCrossingRate(floatArrayOf(1f, 1f, -1f, -1f)), 1e-12)
    }

    @Test
    fun `levels describe a frame the obvious way`() {
        val frame = floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f)
        assertEquals(0.5, FrameLevels.rms(frame), 1e-12)
        assertEquals(0.5f, FrameLevels.peak(frame), 0f)
        assertEquals("an empty frame has no level", 0.0, FrameLevels.rms(FloatArray(0)), 0.0)
    }
}
