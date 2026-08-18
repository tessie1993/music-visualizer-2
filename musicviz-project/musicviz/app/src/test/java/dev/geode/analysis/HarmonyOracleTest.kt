package dev.geode.analysis

import dev.geode.engine.audio.AnalysisBranch
import dev.geode.engine.audio.Chromagram
import dev.geode.engine.audio.FrameGrid
import dev.geode.engine.audio.KeyDetector
import dev.geode.engine.audio.Spectrum
import dev.geode.engine.audio.WindowTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The harmony incumbents against fixtures whose answers are known by
 * construction. Numeric parity with any library's chroma is deliberately
 * not the bar — a chromagram filterbank is an implementation, not a
 * definition. What the product needs is musical: a C major triad reads
 * C-E-G, an A stays A, a drum track earns no confidence, and the key
 * detector names the key a musician would.
 */
class HarmonyOracleTest {
    /** Drives one fixture's frames through [Chromagram] and [KeyDetector]. */
    private class Driven(
        fixture: Corpus.Fixture,
    ) {
        val chroma: Chromagram
        val key: String
        val meanConfidence: Float

        init {
            val block = fixture.perFrame()
            val nFft = block.getInt("nFft")
            val hop = block.getInt("hop")
            val frames = block.getInt("frames")
            val mono = fixture.mono()
            val grid = FrameGrid(AnalysisBranch("oracle", nFft, hop))
            val spectrum = Spectrum(nFft)
            val window = WindowTable(nFft)
            val windowed = FloatArray(nFft)
            // The scale and layout ReactiveAnalyzer.spectrumInto hands the
            // production chromagram: DC zeroed, full-scale tone near 1.
            val scaled = FloatArray(nFft / 2)
            chroma = Chromagram(hopRateHz = fixture.sampleRateHz.toFloat() / hop)
            val detector = KeyDetector()
            var confidenceSum = 0f

            for (k in 0 until frames) {
                window.applyInto(mono, grid.firstSample(k.toLong()).toInt(), windowed)
                spectrum.compute(windowed)
                scaled[0] = 0f
                for (b in 1 until nFft / 2) scaled[b] = spectrum.magnitudes[b] * 2f / nFft
                chroma.step(scaled, fixture.sampleRateHz, nFft)
                detector.accumulate(scaled, fixture.sampleRateHz, nFft)
                confidenceSum += chroma.confidence
            }
            key = detector.finish()
            meanConfidence = confidenceSum / frames
        }
    }

    private fun topClasses(
        chroma: Chromagram,
        n: Int,
    ): Set<Int> {
        val out = IntArray(n)
        chroma.top(n, out)
        return out.toSet()
    }

    @Test
    fun `a C major triad reads C E G`() {
        val driven = Driven(Corpus.named("triad_c_major"))
        assertEquals(
            Corpus.named("triad_c_major").expectedInts("dominantPitchClasses").toSet(),
            topClasses(driven.chroma, 3),
        )
        assertTrue("confidence ${driven.chroma.confidence}", driven.chroma.confidence > 0.5f)
    }

    @Test
    fun `an A minor triad reads A C E`() {
        val driven = Driven(Corpus.named("triad_a_minor"))
        assertEquals(
            Corpus.named("triad_a_minor").expectedInts("dominantPitchClasses").toSet(),
            topClasses(driven.chroma, 3),
        )
        assertTrue("confidence ${driven.chroma.confidence}", driven.chroma.confidence > 0.5f)
    }

    @Test
    fun `a lone A440 is dominated by pitch class A`() {
        val fixture = Corpus.named("tone_440")
        val driven = Driven(fixture)
        assertEquals(fixture.expectedInts("dominantPitchClasses").single(), driven.chroma.dominantPitchClass)
        assertTrue("confidence ${driven.chroma.confidence}", driven.chroma.confidence > 0.5f)
    }

    @Test
    fun `the key detector names the keys a musician would`() {
        for (name in listOf("triad_c_major", "triad_a_minor", "arpeggio_g_major")) {
            val fixture = Corpus.named(name)
            assertEquals(name, fixture.expectedString("key"), Driven(fixture).key)
        }
    }

    @Test
    fun `percussion earns no harmonic confidence`() {
        // 0.35 is Chromagram's own documented hold-your-last-reading
        // threshold; a click track spending its average above it would tell
        // every harmony visual to follow a drum fill.
        val driven = Driven(Corpus.named("clicks_120bpm"))
        assertTrue("clicks averaged ${driven.meanConfidence}", driven.meanConfidence < 0.35f)
    }

    /** The empty marker has to be distinguishable from a silent reading. */
    @Test
    fun `AudioFeatures reports whether a chromagram ran`() {
        assertTrue(!AudioFeatures.empty().hasChroma)
        assertTrue(AudioFeatures.empty().copy(chroma = FloatArray(12)).hasChroma)
    }

    @Test
    fun `silence earns nothing at all`() {
        val driven = Driven(Corpus.named("silence"))
        assertEquals(0f, driven.chroma.confidence, 1e-6f)
        assertEquals("", driven.key)
    }
}
