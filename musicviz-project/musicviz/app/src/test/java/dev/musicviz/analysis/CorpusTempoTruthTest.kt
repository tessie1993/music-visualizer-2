package dev.musicviz.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The tempo fixtures say what they were built to be, not what a reader made
 * of them.
 *
 * Until generator version 3 the corpus recorded `tempoBpm = 117.45` for a
 * click track that is 120.000 BPM by construction. That number is not a
 * measurement of the fixture: it is `60 · frameRate / 22`, the nearest tempo
 * librosa's integer autocorrelation lag can express at this rate and hop. An
 * estimator scored against it would have been penalised for being right, and
 * the whole point of V2-3-04's tempo work is to be scored.
 */
class CorpusTempoTruthTest {
    private val librosaFrameRateHz = Corpus.named("clicks_120bpm").sampleRateHz / 512.0

    @Test
    fun `the click track's tempo is its construction value`() {
        assertEquals(120.0, Corpus.named("clicks_120bpm").expected("tempoBpm"), 0.0)
    }

    @Test
    fun `its beats are where a 120 BPM grid puts them`() {
        val beats = Corpus.named("clicks_120bpm").expectedSeries("beatTimesSeconds")
        assertTrue("no beat times were recorded", beats.size >= 20)
        for ((i, t) in beats.withIndex()) {
            assertEquals("beat $i", i * 0.5, t, 1e-9)
        }
    }

    @Test
    fun `librosa's own reading is kept, and is exactly its lag quantum`() {
        // Not deleted, because it is a useful cross-check — but recorded under
        // a name that says whose reading it is. This test is the evidence for
        // the claim above: the value it reports is precisely what an integer
        // lag can express, and nothing about the fixture.
        val fixture = Corpus.named("clicks_120bpm")
        val reading = fixture.expected("librosaTempoBpm")
        val lag = (60.0 * librosaFrameRateHz / reading).roundToInt()
        assertEquals("librosa's reading is not on the lag grid", 60.0 * librosaFrameRateHz / lag, reading, 1e-9)
        assertNotEquals("the reading happens to be exact, so this proves nothing", 120.0, reading, 0.5)
        assertEquals("expected the lag-22 quantum", 22, lag)
    }

    @Test
    fun `an integer lag cannot express the tempo this fixture actually has`() {
        // Why sub-lag resolution is a requirement and not a refinement: the
        // two lags either side of 120 BPM are 2.6 and 3.0 BPM away from it.
        val below = 60.0 * librosaFrameRateHz / 21
        val above = 60.0 * librosaFrameRateHz / 22
        assertTrue("lag 21 reads $below", below > 120.0 && below - 120.0 > 2.0)
        assertTrue("lag 22 reads $above", above < 120.0 && 120.0 - above > 2.0)
    }

    @Test
    fun `the ramp is the ramp its name claims`() {
        // It was not: the generator added 4 BPM per beat and stopped at six
        // seconds, ending at 130 while the comment beside it said 150.
        val ramp = Corpus.named("tempo_ramp")
        assertEquals(90.0, ramp.expected("tempoBpmStart"), 0.0)
        assertEquals(150.0, ramp.expected("tempoBpmEnd"), 0.0)

        val beats = ramp.expectedSeries("beatTimesSeconds")
        val bpm = ramp.expectedSeries("beatBpm")
        assertEquals("one BPM per beat", beats.size, bpm.size)
        assertTrue("too few beats to be a ramp", beats.size >= 20)
        assertEquals("the ramp does not start where it says", 90.0, bpm.first(), 1e-9)
        assertTrue("the ramp does not reach its end", bpm.last() > 145.0)

        // Monotone, and linear in time rather than in beat number.
        for (i in 1 until bpm.size) {
            assertTrue("beat $i went backwards", bpm[i] > bpm[i - 1])
            assertTrue("beat $i is out of order", beats[i] > beats[i - 1])
            val slope = (bpm[i] - bpm[i - 1]) / (beats[i] - beats[i - 1])
            assertEquals("the ramp is not linear in time at beat $i", 5.0, slope, 1e-6)
        }
    }

    @Test
    fun `the beat times really are where that ramp's beats fall`() {
        // Independent of the closed form the generator used: integrating the
        // stated tempo between two beats must advance the phase by exactly one.
        val ramp = Corpus.named("tempo_ramp")
        val beats = ramp.expectedSeries("beatTimesSeconds")
        val start = ramp.expected("tempoBpmStart")
        val slope = (ramp.expected("tempoBpmEnd") - start) / 12.0
        for (i in 1 until beats.size) {
            val a = beats[i - 1]
            val b = beats[i]
            // Integral of (start + slope*t)/60 from a to b.
            val phase = (start * (b - a) + slope * (b * b - a * a) / 2.0) / 60.0
            assertTrue("beat $i spans ${abs(phase)} of a beat", abs(phase - 1.0) < 1e-9)
        }
    }

    @Test
    fun `both tempo fixtures are long enough to estimate a tempo from`() {
        // Twelve seconds: an estimator needs a window at least twice its
        // slowest lag (50 BPM is 1.2 s) before it can say anything, and then
        // room to settle inside the fixture.
        for (name in listOf("clicks_120bpm", "tempo_ramp")) {
            val fixture = Corpus.named(name)
            val seconds = fixture.frames.toDouble() / fixture.sampleRateHz
            assertEquals("$name is $seconds s", 12.0, seconds, 0.01)
        }
    }

    @Test
    fun `the corpus still checksums against its own manifest`() {
        // The regeneration must not have disturbed anything else: nine of the
        // eleven fixtures are byte-identical to the version before it.
        for (fixture in Corpus.fixtures) {
            assertTrue("${fixture.name} does not match its checksum", fixture.checksumMatches())
            assertEquals("${fixture.name} length", fixture.declaredByteLength(), fixture.actualByteLength())
        }
        assertTrue("the manifest predates construction truth", Corpus.generatorVersion >= 3)
    }
}
