package dev.musicviz.analysis

import dev.musicviz.engine.audio.AnalysisBranch
import dev.musicviz.engine.audio.FrameGrid
import dev.musicviz.engine.audio.FrameLevels
import dev.musicviz.engine.audio.SpectralDescriptors
import dev.musicviz.engine.audio.SpectralFlux
import dev.musicviz.engine.audio.Spectrum
import dev.musicviz.engine.audio.WindowTable
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every descriptor, frame by frame, against librosa.
 *
 * Pointwise rather than aggregate on purpose. A mean hides exactly the
 * failures that matter here — a formula wrong only at the edges of the
 * spectrum, or a framing off by one hop — because both leave the average
 * roughly right while every individual frame is wrong.
 *
 * The framing this drives is the engine's own [FrameGrid]: frame *k* centred
 * at `k * hop`, zeros outside the signal. That it coincides with librosa's
 * `center=True, pad_mode="constant"` was verified against `librosa.stft`
 * before any of this was written, not assumed from the documentation.
 */
class DescriptorOracleTest {
    private val branch = AnalysisBranch.GENERAL

    /** One fixture's frames, computed the way the engine will compute them. */
    private class Measured(
        fixture: Corpus.Fixture,
        frames: Int,
        nFft: Int,
        hop: Int,
    ) {
        val centroid = DoubleArray(frames)
        val bandwidth = DoubleArray(frames)
        val rolloff = DoubleArray(frames)
        val flatness = DoubleArray(frames)
        val flux = DoubleArray(frames)
        val zcr = DoubleArray(frames)
        val rms = DoubleArray(frames)
        val peak = DoubleArray(frames)

        init {
            val mono = fixture.mono()
            val grid = FrameGrid(AnalysisBranch("oracle", nFft, hop))
            val spectrum = Spectrum(nFft)
            val window = WindowTable(nFft)
            val fluxNode = SpectralFlux(spectrum.magnitudes.size)
            val windowed = FloatArray(nFft)
            val raw = FloatArray(nFft)
            val binHz = spectrum.binHz(fixture.sampleRateHz)

            for (k in 0 until frames) {
                val start = grid.firstSample(k.toLong()).toInt()
                // Rectangular first, so the time-domain measurements see the
                // frame itself rather than a tapered copy of it.
                WindowTable(nFft, dev.musicviz.engine.audio.WindowShape.RECTANGULAR).applyInto(mono, start, raw)
                window.applyInto(mono, start, windowed)
                spectrum.compute(windowed)

                centroid[k] = SpectralDescriptors.centroidHz(spectrum.magnitudes, binHz)
                bandwidth[k] = SpectralDescriptors.bandwidthHz(spectrum.magnitudes, binHz, centroid[k])
                rolloff[k] = SpectralDescriptors.rolloffHz(spectrum.magnitudes, binHz)
                flatness[k] = SpectralDescriptors.flatness(spectrum.magnitudes)
                flux[k] = fluxNode.next(spectrum.magnitudes)
                zcr[k] = FrameLevels.zeroCrossingRate(raw)
                rms[k] = FrameLevels.rms(raw)
                peak[k] = FrameLevels.peak(raw).toDouble()
            }
        }
    }

    private fun perFrame(fixture: Corpus.Fixture): JSONObject = fixture.perFrame()

    private fun compare(
        feature: String,
        pick: (Measured) -> DoubleArray,
        tolerance: Double = Corpus.tolerance(feature),
    ) {
        var compared = 0
        for (fixture in Corpus.fixtures) {
            val expected = perFrame(fixture)
            val frames = expected.getInt("frames")
            val measured = pick(Measured(fixture, frames, expected.getInt("nFft"), expected.getInt("hop")))
            val oracle = expected.getJSONArray(feature)
            for (k in 0 until frames) {
                assertEquals(
                    "${fixture.name} frame $k of $feature",
                    oracle.getDouble(k),
                    measured[k],
                    tolerance * maxOf(1.0, kotlin.math.abs(oracle.getDouble(k))),
                )
                compared++
            }
        }
        assertTrue("nothing was compared for $feature", compared > 500)
    }

    @Test
    fun `the corpus carries per-frame expectations for every fixture`() {
        // Guards the sweep below: a manifest regenerated without the per-frame
        // block would make every comparison vacuous by having nothing to
        // compare against, and the loop would pass in an instant.
        assertTrue("the manifest predates per-frame expectations", Corpus.generatorVersion >= 2)
        for (fixture in Corpus.fixtures) {
            val block = perFrame(fixture)
            assertEquals(branch.windowFrames, block.getInt("nFft"))
            assertEquals(branch.hopFrames, block.getInt("hop"))
            assertTrue("${fixture.name} has no frames", block.getInt("frames") > 0)
        }
    }

    @Test
    fun `spectral centroid matches the oracle on every frame`() = compare("centroidHz", { it.centroid })

    @Test
    fun `spectral bandwidth matches the oracle on every frame`() = compare("bandwidthHz", { it.bandwidth })

    @Test
    fun `spectral rolloff matches the oracle on every frame`() = compare("rolloffHz", { it.rolloff })

    @Test
    fun `spectral flatness matches the oracle on every frame`() = compare("flatness", { it.flatness })

    @Test
    fun `spectral flux matches the oracle on every frame`() = compare("flux", { it.flux })

    @Test
    fun `zero crossing rate matches the oracle on every frame`() = compare("zeroCrossingRate", { it.zcr })

    @Test
    fun `frame RMS matches the oracle on every frame`() = compare("frameRms", { it.rms }, Corpus.tolerance("frameRms"))

    @Test
    fun `frame peak matches the oracle on every frame`() = compare("framePeak", { it.peak }, Corpus.tolerance("framePeak"))

    @Test
    fun `a silent frame has no spectral shape rather than a ratio of two zeros`() {
        // Every descriptor divides by total magnitude. The silence fixture is
        // the one that would return NaN, and a NaN reaching a shader is a
        // black frame rather than an error.
        val silence = Corpus.named("silence")
        val block = perFrame(silence)
        val measured = Measured(silence, block.getInt("frames"), block.getInt("nFft"), block.getInt("hop"))
        for (k in 0 until block.getInt("frames")) {
            assertEquals(0.0, measured.centroid[k], 0.0)
            assertEquals(0.0, measured.bandwidth[k], 0.0)
            assertEquals(0.0, measured.rolloff[k], 0.0)
            assertEquals(0.0, measured.rms[k], 0.0)
            assertTrue("flatness must stay finite on silence", measured.flatness[k].isFinite())
        }
    }

    @Test
    fun `the descriptors separate a tone from noise`() {
        // A sanity check that does not depend on the oracle at all: whatever
        // the numbers are, flatness must rank these two the right way round,
        // or the measure is not measuring what its name says.
        val tone = Corpus.named("tone_440")
        val wide = Corpus.named("stereo_wide")
        val toneBlock = perFrame(tone)
        val wideBlock = perFrame(wide)
        val toneFlat = Measured(tone, toneBlock.getInt("frames"), toneBlock.getInt("nFft"), toneBlock.getInt("hop")).flatness
        val wideFlat = Measured(wide, wideBlock.getInt("frames"), wideBlock.getInt("nFft"), wideBlock.getInt("hop")).flatness
        val toneMiddle = toneFlat[toneFlat.size / 2]
        val wideMiddle = wideFlat[wideFlat.size / 2]
        assertTrue("a pure tone read flatter ($toneMiddle) than noise ($wideMiddle)", toneMiddle < wideMiddle)
    }
}
