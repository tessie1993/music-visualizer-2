package dev.musicviz

import dev.musicviz.analysis.FftProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * What [FftProcessor] actually measures, verified against known input rather
 * than against its own documentation.
 *
 * Two questions a visualizer lives or dies by, neither of which is answerable
 * by reading the class: does a tone at frequency F land in the band that
 * claims to cover F, and how much of the published 0..1 range does realistic
 * material actually occupy? A band map that is off, or a normalisation that
 * squeezes real music into the bottom tenth of its range, produces visuals
 * that technically respond and visibly do not.
 */
class FftContractTest {
    private fun sine(
        hz: Float,
        sampleRate: Int,
        n: Int,
        amplitude: Float = 1f,
    ) = FloatArray(n) { i -> (amplitude * sin(2.0 * PI * hz * i / sampleRate)).toFloat() }

    /** Index of the loudest band for a pure tone at [hz]. */
    private fun peakBand(
        processor: FftProcessor,
        hz: Float,
        sampleRate: Int,
        amplitude: Float = 1f,
    ): Int {
        val bands = FloatArray(processor.bandCount)
        processor.process(sine(hz, sampleRate, processor.fftSize, amplitude), sampleRate, bands)
        return bands.indices.maxByOrNull { bands[it] } ?: -1
    }

    @Test
    fun `a tone lands in the band whose frequency range contains it`() {
        val sampleRate = 44_100
        val processor = FftProcessor()
        val edges = processor.bandEdges(sampleRate)
        val binHz = sampleRate / 2f / (processor.fftSize / 2)

        val report = StringBuilder("\n=== FFT BAND MAP (44.1 kHz, fftSize=2048, 64 bands) ===\n")
        report.append("bin width = ${"%.1f".format(binHz)} Hz\n")
        report.append("band  binRange      Hz range\n")
        for (b in 0 until processor.bandCount step 8) {
            val lo = edges[b] * binHz
            val hi = edges[b + 1] * binHz
            report.append(
                "%4d  %4d-%-6d  %7.0f - %.0f Hz\n".format(b, edges[b], edges[b + 1], lo, hi),
            )
        }

        report.append("\ntone -> peak band (and that band's Hz range)\n")
        var mismatches = 0
        for (hz in floatArrayOf(60f, 100f, 200f, 440f, 1000f, 3000f, 8000f, 15000f)) {
            val b = peakBand(processor, hz, sampleRate)
            val lo = edges[b] * binHz
            val hi = edges[b + 1] * binHz
            val contains = hz >= lo * 0.75f && hz <= hi * 1.34f
            if (!contains) mismatches++
            report.append(
                "%7.0f Hz -> band %2d  (%7.0f - %-8.0f Hz) %s\n"
                    .format(hz, b, lo, hi, if (contains) "ok" else "MISMATCH"),
            )
        }
        println(report)
        assertTrue("tones did not land in their own bands ($mismatches mismatches)", mismatches == 0)
    }

    @Test
    fun `report how much of the published range realistic levels occupy`() {
        val sampleRate = 44_100
        val processor = FftProcessor()
        val bands = FloatArray(processor.bandCount)
        val report = StringBuilder("\n=== DYNAMIC RANGE UTILISATION ===\n")
        report.append("a 1 kHz tone at descending amplitude, value of its peak band\n")
        report.append(" amplitude    dBFS   band value\n")
        for (amp in floatArrayOf(1f, 0.5f, 0.25f, 0.1f, 0.05f, 0.01f, 0.001f)) {
            processor.process(sine(1000f, sampleRate, processor.fftSize, amp), sampleRate, bands)
            val peak = bands.max()
            val dbfs = 20.0 * kotlin.math.log10(amp.toDouble())
            report.append("%9.3f  %6.1f   %.4f\n".format(amp, dbfs, peak))
        }
        // Broadband noise is what percussion actually looks like, and it
        // spreads its energy over every bin rather than concentrating it in
        // one - so a per-band PEAK reads far lower for a drum hit than for a
        // sine of the same amplitude.
        val random = java.util.Random(1)
        val noise = FloatArray(processor.fftSize) { (random.nextFloat() * 2f - 1f) }
        processor.process(noise, sampleRate, bands)
        report.append("\nfull-scale broadband noise: mean band = ${"%.4f".format(bands.average())}")
        report.append(", max band = ${"%.4f".format(bands.max())}\n")
        println(report)
        assertTrue(
            "silence should not register",
            run {
                processor.process(FloatArray(processor.fftSize), sampleRate, bands)
                bands.all { it <= 0f }
            },
        )
    }
}
