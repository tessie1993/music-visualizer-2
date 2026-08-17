package dev.musicviz.analysis

import dev.musicviz.engine.audio.ReactiveAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Driven through the real [FftProcessor] rather than by handing [Chromagram]
 * synthetic magnitude arrays: the binning, the window and the pitch mapping
 * all have to agree, and a test that fakes the spectrum cannot see them
 * disagree.
 */
class ChromagramTest {
    private val rate = 48_000
    private val fftSize = 2048

    private fun midiHz(midi: Int): Float = (440.0 * 2.0.pow((midi - 69) / 12.0)).toFloat()

    /** Pitch class of a MIDI note, 0 = C. */
    private fun pc(midi: Int) = ((midi % 12) + 12) % 12

    /** Runs [frames] frames of a chord (MIDI notes) through the real FFT. */
    private fun run(
        notes: List<Int>,
        frames: Int = 40,
        amp: Float = 0.5f,
    ): Chromagram {
        val fft = SpectrumSource()
        val chroma = Chromagram(hopRateHz = 60f)
        val buf = FloatArray(fftSize)
        var phase = 0
        repeat(frames) {
            for (i in 0 until fftSize) {
                var v = 0.0
                for (m in notes) v += sin(2.0 * PI * midiHz(m) * (phase + i) / rate)
                buf[i] = (v * amp / maxOf(1, notes.size)).toFloat()
            }
            phase += fftSize
            fft.feed(buf, chroma)
        }
        return chroma
    }

    // ---- pitch identification ---------------------------------------------

    /**
     * A single sustained note lands on its own pitch class. Chosen above C5
     * (MIDI 72), where a 2048-point FFT at 48 kHz actually resolves semitones
     * - the class documents that limit and this test respects it rather than
     * pretending it is not there.
     */
    @Test
    fun `a single note lands on its own pitch class`() {
        for (midi in listOf(72, 74, 76, 77, 79, 81, 83, 84)) {
            val c = run(listOf(midi))
            assertEquals(
                "MIDI $midi (${Chromagram.NAMES[pc(midi)]}) -> ${Chromagram.NAMES[c.dominantPitchClass]}",
                pc(midi),
                c.dominantPitchClass,
            )
        }
    }

    @Test
    fun `bins are normalised with the peak at one`() {
        val c = run(listOf(76))
        var peak = 0f
        for (v in c.bins) {
            assertTrue("bin out of range: $v", v in 0f..1f)
            peak = maxOf(peak, v)
        }
        assertEquals("the loudest bin should reach 1", 1f, peak, 0.02f)
    }

    /**
     * A C major triad should light C, E and G above everything else. Only the
     * SET is asserted, not the ordering between them: which of the three is
     * loudest depends on where each partial falls relative to a bin edge, and
     * pinning that would be pinning FFT leakage.
     */
    @Test
    fun `a major triad lights its three pitch classes`() {
        val c = run(listOf(72, 76, 79)) // C5, E5, G5
        val top = IntArray(3)
        assertEquals(3, c.top(3, top))
        assertEquals(
            "got ${top.map { Chromagram.NAMES[it] }}",
            setOf(pc(72), pc(76), pc(79)),
            top.toSet(),
        )
    }

    /** Two chords a tone apart must not produce the same reading. */
    @Test
    fun `different chords read differently`() {
        val cmaj = run(listOf(72, 76, 79)).bins.copyOf()
        val dmaj = run(listOf(74, 78, 81)).bins.copyOf()
        var diff = 0f
        for (i in 0 until 12) diff += abs(cmaj[i] - dmaj[i])
        assertTrue("the two chords differ by only $diff", diff > 1.5f)
    }

    // ---- confidence --------------------------------------------------------

    @Test
    fun `a sustained chord is confident`() {
        assertTrue(
            "chord confidence too low",
            run(listOf(72, 76, 79)).confidence > 0.5f,
        )
    }

    /**
     * Broadband noise has no pitch, so the reading must say so - this is the
     * value a scene uses to hold its last harmony through a drum fill instead
     * of following the noise.
     */
    @Test
    fun `noise is not confident`() {
        val fft = SpectrumSource()
        val chroma = Chromagram(hopRateHz = 60f)
        val buf = FloatArray(fftSize)
        var seed = 1234567
        repeat(40) {
            for (i in 0 until fftSize) {
                seed = seed * 1103515245 + 12345
                buf[i] = ((seed ushr 16) and 0x7fff) / 16384f - 1f
            }
            fft.feed(buf, chroma)
        }
        assertTrue("noise scored ${chroma.confidence}", chroma.confidence < 0.35f)
    }

    @Test
    fun `silence is not confident and decays to nothing`() {
        val fft = SpectrumSource()
        val chroma = Chromagram(hopRateHz = 60f)
        // A real chord first, so this measures DECAY rather than a cold start.
        val buf = FloatArray(fftSize)
        repeat(40) { f ->
            for (i in 0 until fftSize) {
                buf[i] = (0.5 * sin(2.0 * PI * midiHz(76) * (f * fftSize + i) / rate)).toFloat()
            }
            fft.feed(buf, chroma)
        }
        assertTrue("should have been confident first", chroma.confidence > 0.5f)

        java.util.Arrays.fill(buf, 0f)
        repeat(180) {
            fft.feed(buf, chroma)
        }
        assertEquals("silence must report no confidence", 0f, chroma.confidence, 0f)
        for (v in chroma.bins) assertTrue("bin did not decay: $v", v < 0.05f)
    }

    // ---- behaviour ---------------------------------------------------------

    /** Loud and quiet renderings of one chord must read the same shape. */
    @Test
    fun `the reading is independent of level`() {
        val loud = run(listOf(72, 76, 79), amp = 0.9f).bins.copyOf()
        val quiet = run(listOf(72, 76, 79), amp = 0.02f).bins.copyOf()
        for (i in 0 until 12) {
            assertEquals("bin $i", loud[i], quiet[i], 0.05f)
        }
    }

    @Test
    fun `reset forgets the previous audio`() {
        val c = run(listOf(72, 76, 79))
        assertTrue(c.confidence > 0.5f)
        c.reset()
        assertEquals(0f, c.confidence, 0f)
        assertEquals(0, c.dominantPitchClass)
        for (v in c.bins) assertEquals(0f, v, 0f)
    }

    @Test
    fun `top returns the loudest classes in order`() {
        val c = run(listOf(72, 76, 79))
        val top = IntArray(12)
        assertEquals(12, c.top(12, top))
        for (i in 1 until 12) {
            assertTrue(
                "not descending at $i",
                c.bins[top[i - 1]] >= c.bins[top[i]],
            )
        }
        assertEquals("top(0) should write nothing", 0, c.top(0, top))
    }

    @Test
    fun `an unfed chromagram is empty and unconfident`() {
        val c = Chromagram()
        assertEquals(0f, c.confidence, 0f)
        for (v in c.bins) assertEquals(0f, v, 0f)
    }

    /** The empty marker has to be distinguishable from a silent reading. */
    @Test
    fun `AudioFeatures reports whether a chromagram ran`() {
        assertTrue(!AudioFeatures.empty().hasChroma)
        assertTrue(AudioFeatures.empty().copy(chroma = FloatArray(12)).hasChroma)
    }

    /**
     * Produces the half-spectrum [Chromagram] consumes, on the scale it was
     * written against. Wraps [ReactiveAnalyzer] rather than an FFT of its own
     * so the tests exercise the same spectrum the app feeds the chromagram.
     */
    private inner class SpectrumSource {
        private val analyzer = ReactiveAnalyzer(fftSize = fftSize, sampleRateHz = rate, hopRateHz = 60f)
        private val magnitudes = FloatArray(fftSize / 2)

        fun feed(
            samples: FloatArray,
            chroma: Chromagram,
        ) {
            analyzer.analyze(samples, 1f / 60f)
            chroma.step(analyzer.spectrumInto(magnitudes), rate, fftSize)
        }
    }
}
