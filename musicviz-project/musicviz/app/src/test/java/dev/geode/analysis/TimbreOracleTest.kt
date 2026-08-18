package dev.geode.analysis

import dev.geode.engine.audio.AnalysisBranch
import dev.geode.engine.audio.FrameGrid
import dev.geode.engine.audio.MelBank
import dev.geode.engine.audio.Mfcc
import dev.geode.engine.audio.SpectralContrast
import dev.geode.engine.audio.Spectrum
import dev.geode.engine.audio.WindowTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timbre block, frame by frame, against the oracle — MFCC, timbre flux
 * and spectral contrast, in the same pointwise discipline as
 * [DescriptorOracleTest] and for the same reason: an aggregate hides a
 * formula that is wrong only where the energy is not.
 */
class TimbreOracleTest {
    /** One fixture's timbre frames, computed the way the engine will. */
    private class Measured(
        fixture: Corpus.Fixture,
        frames: Int,
        nFft: Int,
        hop: Int,
        nMels: Int,
        nMfcc: Int,
        contrastBands: Int,
        contrastFminHz: Double,
        contrastAlpha: Double,
    ) {
        val mfcc = Array(frames) { FloatArray(nMfcc) }
        val timbreFlux = DoubleArray(frames)
        val contrast = Array(frames) { FloatArray(contrastBands) }

        init {
            val mono = fixture.mono()
            val grid = FrameGrid(AnalysisBranch("oracle", nFft, hop))
            val spectrum = Spectrum(nFft)
            val window = WindowTable(nFft)
            val windowed = FloatArray(nFft)
            val mel = MelBank(nFft, fixture.sampleRateHz, nMels)
            val melPower = FloatArray(nMels)
            val cepstrum = Mfcc(nMels, nMfcc)
            val contrastNode =
                SpectralContrast(
                    fftSize = nFft,
                    sampleRateHz = fixture.sampleRateHz,
                    bands = contrastBands,
                    fminHz = contrastFminHz.toFloat(),
                    alpha = contrastAlpha.toFloat(),
                )

            for (k in 0 until frames) {
                val start = grid.firstSample(k.toLong()).toInt()
                window.applyInto(mono, start, windowed)
                spectrum.compute(windowed)
                mel.power(spectrum.magnitudes, melPower)
                cepstrum.compute(melPower)
                cepstrum.coefficients.copyInto(mfcc[k])
                timbreFlux[k] = cepstrum.timbreFlux.toDouble()
                contrastNode.compute(spectrum.magnitudes, contrast[k])
            }
        }
    }

    private fun measured(fixture: Corpus.Fixture): Pair<Measured, org.json.JSONObject> {
        val block = fixture.perFrame()
        return Measured(
            fixture,
            frames = block.getInt("frames"),
            nFft = block.getInt("nFft"),
            hop = block.getInt("hop"),
            nMels = block.getInt("nMels"),
            nMfcc = block.getInt("nMfcc"),
            contrastBands = block.getInt("contrastBands"),
            contrastFminHz = block.getDouble("contrastFminHz"),
            contrastAlpha = block.getDouble("contrastAlpha"),
        ) to block
    }

    @Test
    fun `mfcc matches the oracle on every frame and coefficient`() {
        val tolerance = Corpus.tolerance("mfcc")
        var compared = 0
        for (fixture in Corpus.fixtures) {
            val (measured, block) = measured(fixture)
            val oracle = block.getJSONArray("mfcc")
            for (k in 0 until block.getInt("frames")) {
                val frame = oracle.getJSONArray(k)
                for (c in 0 until frame.length()) {
                    val want = frame.getDouble(c)
                    assertEquals(
                        "${fixture.name} frame $k mfcc[$c]",
                        want,
                        measured.mfcc[k][c].toDouble(),
                        tolerance * maxOf(1.0, kotlin.math.abs(want)),
                    )
                    compared++
                }
            }
        }
        assertTrue("nothing was compared", compared > 5_000)
    }

    @Test
    fun `timbre flux matches the oracle on every frame`() {
        val tolerance = Corpus.tolerance("timbreFlux")
        var compared = 0
        for (fixture in Corpus.fixtures) {
            val (measured, block) = measured(fixture)
            val oracle = block.getJSONArray("timbreFlux")
            for (k in 0 until block.getInt("frames")) {
                val want = oracle.getDouble(k)
                assertEquals(
                    "${fixture.name} frame $k timbreFlux",
                    want,
                    measured.timbreFlux[k],
                    tolerance * maxOf(1.0, kotlin.math.abs(want)),
                )
                compared++
            }
        }
        assertTrue("nothing was compared", compared > 500)
    }

    @Test
    fun `spectral contrast matches the oracle on every frame and band`() {
        val tolerance = Corpus.tolerance("spectralContrast")
        var compared = 0
        for (fixture in Corpus.fixtures) {
            val (measured, block) = measured(fixture)
            val oracle = block.getJSONArray("spectralContrast")
            for (k in 0 until block.getInt("frames")) {
                val frame = oracle.getJSONArray(k)
                for (b in 0 until frame.length()) {
                    val want = frame.getDouble(b)
                    assertEquals(
                        "${fixture.name} frame $k contrast[$b]",
                        want,
                        measured.contrast[k][b].toDouble(),
                        tolerance * maxOf(1.0, kotlin.math.abs(want)),
                    )
                    compared++
                }
            }
        }
        assertTrue("nothing was compared", compared > 2_000)
    }

    @Test
    fun `a tone carries more contrast in its own band than noise does`() {
        // Oracle-free sanity: whatever the numbers, a 440 Hz tone must stand
        // further above its band's valley (band 1, 400..800 Hz — the tone's
        // energy leaks there while its floor stays empty) than wideband noise
        // stands above its own, or the measure is not measuring contrast.
        val (tone, toneBlock) = measured(Corpus.named("tone_440"))
        val (wide, wideBlock) = measured(Corpus.named("stereo_wide"))
        val toneMid = tone.contrast[toneBlock.getInt("frames") / 2][1]
        val wideMid = wide.contrast[wideBlock.getInt("frames") / 2][1]
        assertTrue("tone $toneMid dB vs noise $wideMid dB", toneMid > wideMid)
    }
}
