package dev.musicviz.analysis

import dev.musicviz.engine.audio.LogBands
import dev.musicviz.engine.audio.Spectrum
import dev.musicviz.engine.audio.WindowShape
import dev.musicviz.engine.audio.WindowTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The app's audio measurements against an independent oracle.
 *
 * `StereoField` reaches interchannel correlation through mid/side identities -
 * `sum(L*R) = sum(m^2) - sum(s^2)`, and so on - so it never reconstructs L and
 * R. That is a real saving and a real risk: if the algebra were wrong, every
 * width and correlation the app has ever drawn would be wrong together, and
 * nothing inside the app could tell. librosa/numpy computes the same quantity
 * the obvious way, in another language, over the same bytes.
 *
 * Corpus and expectations come from `tools/oracle/generate_corpus.py`. librosa
 * is ORACLE tier: it never enters the runtime.
 */
class CorpusOracleTest {
    @Test
    fun `the corpus on disk is the corpus the manifest describes`() {
        // The manifest is the only description of these bytes - raw PCM has no
        // header to disagree with - so a fixture regenerated without its
        // expectations, or an expectation edited by hand, has to fail here or
        // every comparison below silently measures the wrong thing.
        assertTrue("the corpus is empty", Corpus.fixtures.isNotEmpty())
        val broken =
            Corpus.fixtures.filterNot { it.checksumMatches() }.map { it.name } +
                Corpus.fixtures.filter { it.declaredByteLength() != it.actualByteLength() }.map { "${it.name}: length" }
        assertEquals("regenerate with tools/oracle/generate_corpus.py", emptyList<String>(), broken)
    }

    @Test
    fun `the corpus records what produced it`() {
        // A tolerance without a generator version is a number nobody can
        // reproduce. §2.1 rule 8's spirit: evidence names its origin.
        assertTrue(Corpus.generatorVersion >= 1)
        assertTrue("librosa version not recorded", Corpus.libraryVersion("librosa").isNotBlank())
        assertTrue("numpy version not recorded", Corpus.libraryVersion("numpy").isNotBlank())
        assertTrue(Corpus.tolerance("stereoCorrelation") > 0.0)
    }

    @Test
    fun `interchannel correlation agrees with the oracle on every stereo fixture`() {
        val tolerance = Corpus.tolerance("stereoCorrelation").toFloat()
        val stereo = Corpus.fixtures.filter { it.has("stereoCorrelation") }
        assertTrue("no stereo fixtures to compare", stereo.size >= 3)
        for (fixture in stereo) {
            val ours = StereoField.correlation(fixture.mono(), fixture.side())
            assertEquals(fixture.name, fixture.expected("stereoCorrelation").toFloat(), ours, tolerance)
        }
    }

    @Test
    fun `stereo width agrees with the oracle, including where mid is zero`() {
        // Anti-phase is the case a naive width ratio gets exactly backwards:
        // there is no mid at all, so `side / mid` divides by zero and reports
        // the widest possible signal as narrow. The oracle got this wrong
        // first; the fixture is here so nothing can get it wrong quietly.
        val tolerance = Corpus.tolerance("stereoWidth").toFloat()
        for (fixture in Corpus.fixtures.filter { it.has("stereoWidth") }) {
            val ours = StereoField.width(fixture.mono(), fixture.side())
            assertEquals(fixture.name, fixture.expected("stereoWidth").toFloat(), ours, tolerance)
        }
        val antiphase = Corpus.named("stereo_antiphase")
        assertEquals(
            "anti-phase is maximum width",
            1f,
            StereoField.width(antiphase.mono(), antiphase.side()),
            1e-4f,
        )
    }

    @Test
    fun `an anti-phase pair collapses to silence in mono`() {
        // The reading engineers actually watch for, and the reason correlation
        // is measured at all. The oracle says RMS 0; so must our downmix.
        val fixture = Corpus.named("stereo_antiphase")
        val mono = fixture.mono()
        val rms = sqrt(mono.map { (it * it).toDouble() }.average())
        assertEquals(fixture.expected("rms"), rms, Corpus.tolerance("rms"))
        assertEquals(-1f, StereoField.correlation(mono, fixture.side()), 1e-4f)
    }

    @Test
    fun `silence reads as correlated, not as maximally decorrelated`() {
        // Documented behaviour with a real consequence: returning 0 here makes
        // every gap between tracks read as maximum decorrelation and swings
        // anything driven by it.
        val silence = Corpus.named("silence")
        assertEquals(0.0, silence.expected("rms"), 0.0)
        assertEquals(1f, StereoField.correlation(silence.mono(), silence.side()), 0f)
        assertEquals(0f, StereoField.width(silence.mono(), silence.side()), 0f)
    }

    @Test
    fun `a pure tone lands in the band that contains it`() {
        // Ties our log band layout to the oracle's spectral centroid, which for
        // a pure tone is the tone. A band table off by a few bands shows up
        // here as the peak sitting somewhere else.
        val fixture = Corpus.named("tone_440")
        val fftSize = 2048
        val windowed = FloatArray(fftSize)
        WindowTable(fftSize, WindowShape.HANN).applyInto(fixture.mono(), 0, windowed)
        val spectrum = Spectrum(fftSize)
        spectrum.compute(windowed)
        val bander = LogBands(64, fftSize, fixture.sampleRateHz)
        val bands = FloatArray(64)
        bander.energyDb(spectrum.magnitudes, bands)

        val peak = bands.indices.maxBy { bands[it] }
        val low = bander.lowerHz(peak).toDouble()
        val high = bander.upperHz(peak).toDouble()
        val centroid = fixture.expected("spectralCentroidHz")

        assertTrue(
            "peak band $peak covers $low..$high Hz, which does not contain the oracle's centroid $centroid",
            centroid in low..high,
        )
        assertTrue("the tone excited every band, so this measures nothing", bands.count { it > 0.2f } < bands.size / 2)
    }

    @Test
    fun `the V2 spectrum node puts the tone where the oracle hears it`() {
        // Ties the new engine nodes to the external oracle rather than to the
        // legacy FftProcessor. A bin index is a much sharper claim than a band
        // index: at 22,050 Hz and a 4,096-point transform each bin is 5.4 Hz,
        // so a framing or windowing mistake moves the peak visibly.
        val fixture = Corpus.named("tone_440")
        val spectrum = dev.musicviz.engine.audio.Spectrum(4096)
        val windowed = FloatArray(4096)
        dev.musicviz.engine.audio.WindowTable(4096).applyInto(fixture.mono(), 0, windowed)
        spectrum.compute(windowed)

        val peakHz = spectrum.peakBin() * spectrum.binHz(fixture.sampleRateHz)
        assertEquals("the peak bin is not the tone", 440.0, peakHz, spectrum.binHz(fixture.sampleRateHz))
        // The oracle's centroid sits a little above the tone because a real
        // spectrum has skirts; the peak must still be the tone itself.
        assertTrue("the oracle's centroid disagrees with the peak", abs(fixture.expected("spectralCentroidHz") - peakHz) < 50.0)
    }

    @Test
    fun `the mono downmix of every fixture matches the oracle's RMS`() {
        // Cheap, and it catches the whole class of interleaving mistakes: a
        // channel-stride error turns stereo into noise at exactly the right
        // length, and nothing else here would see it.
        val tolerance = Corpus.tolerance("rms")
        for (fixture in Corpus.fixtures) {
            val mono = fixture.mono()
            assertEquals(fixture.name, fixture.frames, mono.size)
            val rms = sqrt(mono.map { (it * it).toDouble() }.average())
            assertEquals(fixture.name, fixture.expected("rms"), rms, tolerance)
        }
    }

    @Test
    fun `a mono fixture has no side content and a stereo one does`() {
        val mono = Corpus.named("tone_440")
        assertTrue("a mono fixture invented side content", mono.side().all { it == 0f })
        val wide = Corpus.named("stereo_wide")
        assertTrue("a decorrelated pair reported no side content", wide.side().any { abs(it) > 1e-3f })
    }
}
