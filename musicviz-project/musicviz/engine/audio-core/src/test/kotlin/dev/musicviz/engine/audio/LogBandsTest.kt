package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Log-spaced bands with the spectral tilt taken out.
 *
 * `docs/quality/bar-visualizer.md` §2.3 asks for both halves of this and the
 * legacy `FftProcessor` had only the first: it spaced bands logarithmically but
 * applied no equalization, so on real music — whose power density falls about
 * 3 dB per octave — the upper bands sat permanently near the floor. The doc's
 * own words: "without spectral tilt correction, treble bars barely move on real
 * music". The first test is that sentence as an assertion.
 *
 * It also reads band *energy* rather than the loudest bin in the band. A peak
 * is one bin's worth of a broadband sound, which is why the legacy numbers were
 * small in absolute terms as well as flat.
 */
class LogBandsTest {
    private val sampleRate = 48_000
    private val fftSize = 2048

    private fun bands(
        count: Int = 32,
        tiltDbPerOctave: Float = LogBands.PINK_TILT_DB_PER_OCTAVE,
    ) = LogBands(
        bandCount = count,
        fftSize = fftSize,
        sampleRateHz = sampleRate,
        tiltDbPerOctave = tiltDbPerOctave,
    )

    private fun binHz(k: Int) = k.toFloat() * sampleRate / fftSize

    /** Magnitudes whose power density goes as 1/f — the shape of real music. */
    private fun pinkSpectrum(): FloatArray =
        FloatArray(fftSize / 2 + 1) { k ->
            if (k == 0) 0f else (1.0 / sqrt(binHz(k).toDouble())).toFloat()
        }

    @Test
    fun `pink noise reads flat across the whole spectrum`() {
        val out = FloatArray(32)
        bands().energyDb(pinkSpectrum(), out)
        val spread = out.max() - out.min()
        assertTrue("bands spanned $spread dB: ${out.joinToString { "%.1f".format(it) }}", spread < 1.5f)
    }

    /** Without the tilt the same input falls away — the legacy behaviour. */
    @Test
    fun `without tilt correction the top of the spectrum sits far below the bottom`() {
        val out = FloatArray(32)
        bands(tiltDbPerOctave = 0f).energyDb(pinkSpectrum(), out)
        assertTrue("top ${out.last()} vs bottom ${out.first()}", out.first() - out.last() > 15f)
    }

    @Test
    fun `a sine lands in the band that contains its frequency`() {
        val magnitudes = FloatArray(fftSize / 2 + 1)
        val target = 1000f
        val bin = (target / sampleRate * fftSize).toInt()
        magnitudes[bin] = 1f

        val bander = bands()
        val out = FloatArray(32)
        bander.energyDb(magnitudes, out)

        val loudest = out.indices.maxBy { out[it] }
        assertTrue(
            "1 kHz landed in band $loudest spanning ${bander.lowerHz(loudest)}..${bander.upperHz(loudest)} Hz",
            target >= bander.lowerHz(loudest) && target <= bander.upperHz(loudest),
        )
    }

    @Test
    fun `band edges rise monotonically and cover the configured span`() {
        val bander = bands(count = 24)
        assertEquals(LogBands.DEFAULT_MIN_HZ, bander.lowerHz(0), 1f)
        for (b in 1 until 24) {
            assertTrue("band $b starts below band ${b - 1}", bander.lowerHz(b) > bander.lowerHz(b - 1))
        }
        assertTrue("top band ends at ${bander.upperHz(23)}", bander.upperHz(23) >= 15_000f)
    }

    /**
     * Energy, not peak: a band carrying eight bins of a broadband sound must
     * read louder than the same peak height in one bin alone.
     */
    @Test
    fun `a broadband band reads louder than a single bin at the same height`() {
        val bander = bands(count = 8)
        val out = FloatArray(8)

        val spike = FloatArray(fftSize / 2 + 1)
        val band = 6
        val first = (bander.lowerHz(band) / sampleRate * fftSize).toInt() + 1
        spike[first] = 1f
        bander.energyDb(spike, out)
        val spikeDb = out[band]

        val spread = FloatArray(fftSize / 2 + 1)
        for (k in first until first + 8) spread[k] = 1f
        bander.energyDb(spread, out)
        assertTrue("spike $spikeDb vs spread ${out[band]}", out[band] > spikeDb + 5f)
    }

    @Test
    fun `an empty spectrum reads at the silence floor`() {
        val out = FloatArray(32)
        bands().energyDb(FloatArray(fftSize / 2 + 1), out)
        assertTrue("read ${out.max()}", out.all { it <= AdaptiveRange.SILENCE_DB })
    }

    @Test
    fun `a changed sample rate rebuilds the edges`() {
        val bander = bands()
        val at48k = bander.upperHz(31)
        bander.sampleRateHz = 16_000
        assertTrue("48k top $at48k, 16k top ${bander.upperHz(31)}", bander.upperHz(31) < at48k)
        // Nyquist, not the configured ceiling, bounds a low rate.
        assertTrue(bander.upperHz(31) <= 8_000f)
    }
}
