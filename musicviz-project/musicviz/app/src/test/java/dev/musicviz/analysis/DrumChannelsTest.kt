package dev.musicviz.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

/**
 * The properties that make [DrumChannels] worth having: the band ranges point
 * where they claim to, a hit in one range does not fire the others, the gate
 * is rate-limited, and silence stays silent.
 */
class DrumChannelsTest {
    private val bandCount = 64
    private val hopRateHz = 60f
    private val rate = 48_000

    /** Zero everywhere except a raised block over [from]..[to). */
    private fun bands(
        from: Int,
        to: Int,
        level: Float = 1f,
    ) = FloatArray(bandCount) { if (it in from until to) level else 0f }

    private fun bandOf(hz: Float) = DrumChannels.bandIndexForHz(hz, rate, bandCount)

    // ---- band mapping ------------------------------------------------------

    /**
     * The whole design rests on `bandIndexForHz` being the exact inverse of the
     * spacing [FftProcessor.bandEdges] lays out. Checked against the real thing
     * rather than against a restatement of the same formula: for every band,
     * the frequency at its own lower edge must map back to that band.
     */
    @Test
    fun `band index inverts the FFT log spacing`() {
        val nyquist = rate / 2f
        val min = FftProcessor.MIN_FREQ_HZ
        val span = ln(nyquist / min)
        for (b in 0 until bandCount) {
            // The lower edge of band b, from the forward law, nudged inside it.
            val edgeHz = min * exp(span * b / bandCount) * 1.0001f
            assertEquals("band $b", b, DrumChannels.bandIndexForHz(edgeHz, rate, bandCount))
        }
    }

    @Test
    fun `band index clamps outside the spectrum`() {
        assertEquals(0, DrumChannels.bandIndexForHz(1f, rate, bandCount))
        assertEquals(0, DrumChannels.bandIndexForHz(-5f, rate, bandCount))
        assertEquals(bandCount, DrumChannels.bandIndexForHz(200_000f, rate, bandCount))
    }

    /**
     * A fraction of the band ARRAY is a different part of the SPECTRUM at a
     * different sample rate; that is why the ranges are derived from Hz. If
     * this ever stops holding, the fractions-of-bandCount shortcut is back and
     * the hat channel is pointing at the wrong octave on mic input.
     */
    @Test
    fun `the same frequency lands on different bands at different rates`() {
        val at48k = DrumChannels.bandIndexForHz(5_000f, 48_000, bandCount)
        val at16k = DrumChannels.bandIndexForHz(5_000f, 16_000, bandCount)
        assertTrue("5 kHz should sit higher in a 16 kHz spectrum", at16k > at48k)
    }

    // ---- separation --------------------------------------------------------

    /**
     * Drives one range hard, on a rhythm slow enough to clear every refractory,
     * and reports how many hits each channel saw. The warm-up fills the rolling
     * window so the first real hit is measured against a populated baseline.
     */
    private fun hits(
        from: Int,
        to: Int,
        beatsEvery: Int = 30,
        beats: Int = 8,
    ): Triple<Int, Int, Int> {
        val d = DrumChannels(bandCount, hopRateHz, rate)
        var k = 0
        var s = 0
        var h = 0
        val quiet = FloatArray(bandCount)
        repeat((hopRateHz * DrumChannels.HISTORY_SECONDS).toInt()) { d.step(quiet) }
        repeat(beats) {
            d.step(bands(from, to))
            if (d.kickImpulse > 0f) k++
            if (d.snareImpulse > 0f) s++
            if (d.hatImpulse > 0f) h++
            repeat(beatsEvery - 1) { d.step(quiet) }
        }
        return Triple(k, s, h)
    }

    @Test
    fun `a low hit fires the kick channel only`() {
        val (k, s, h) = hits(bandOf(50f), bandOf(110f))
        assertTrue("kick should fire, got $k", k >= 6)
        assertEquals("snare must stay silent", 0, s)
        assertEquals("hat must stay silent", 0, h)
    }

    @Test
    fun `a mid hit fires the snare channel only`() {
        val (k, s, h) = hits(bandOf(300f), bandOf(1_200f))
        assertEquals("kick must stay silent", 0, k)
        assertTrue("snare should fire, got $s", s >= 6)
        assertEquals("hat must stay silent", 0, h)
    }

    @Test
    fun `a high hit fires the hat channel only`() {
        val (k, s, h) = hits(bandOf(7_000f), bandOf(12_000f))
        assertEquals("kick must stay silent", 0, k)
        assertEquals("snare must stay silent", 0, s)
        assertTrue("hat should fire, got $h", h >= 6)
    }

    // ---- gating ------------------------------------------------------------

    @Test
    fun `silence never fires anything`() {
        val d = DrumChannels(bandCount, hopRateHz, rate)
        val quiet = FloatArray(bandCount)
        repeat(600) {
            d.step(quiet)
            assertEquals(0f, d.kickImpulse, 0f)
            assertEquals(0f, d.snareImpulse, 0f)
            assertEquals(0f, d.hatImpulse, 0f)
        }
    }

    /**
     * A constant spectrum has no positive flux at all - the sustain of a pad
     * must not read as a repeated hit, which is the failure a plain
     * level-threshold version of this would have.
     */
    @Test
    fun `a sustained tone fires once, not continuously`() {
        val d = DrumChannels(bandCount, hopRateHz, rate)
        val quiet = FloatArray(bandCount)
        repeat(180) { d.step(quiet) }
        val held = bands(bandOf(300f), bandOf(1_200f))
        var fired = 0
        repeat(240) {
            d.step(held)
            if (d.snareImpulse > 0f) fired++
        }
        assertTrue("a held tone fired $fired times", fired <= 1)
    }

    /** The refractory is what stops a dense roll strobing. */
    @Test
    fun `the refractory rate-limits a burst`() {
        val d = DrumChannels(bandCount, hopRateHz, rate)
        val quiet = FloatArray(bandCount)
        repeat(180) { d.step(quiet) }
        var fired = 0
        // Alternating on/off is a 30 Hz hit rate - far past every window.
        repeat(120) { i ->
            d.step(if (i % 2 == 0) bands(bandOf(50f), bandOf(110f)) else quiet)
            if (d.kickImpulse > 0f) fired++
        }
        val ceiling = 120f / hopRateHz * 1000f / DrumChannels.KICK_REFRACTORY_MS + 1
        assertTrue("fired $fired times, ceiling ${ceiling.toInt()}", fired <= ceiling)
    }

    @Test
    fun `impulses stay inside zero and one`() {
        val d = DrumChannels(bandCount, hopRateHz, rate)
        val quiet = FloatArray(bandCount)
        repeat(180) { d.step(quiet) }
        repeat(600) { i ->
            d.step(if (i % 17 == 0) bands(0, bandCount, 4f) else quiet)
            for (v in listOf(d.kickImpulse, d.snareImpulse, d.hatImpulse)) {
                assertTrue("out of range: $v", v in 0f..1f)
            }
        }
    }

    /**
     * Strength follows how hard the hit was, relative to the channel's own
     * recent dynamics.
     *
     * Swept rather than compared as a pair: against a quiet baseline the
     * z-score of almost any real hit clears [DrumChannels.STRENGTH_SPAN_SIGMA]
     * and grades to 1, so a two-point test passes or fails on where the
     * baseline happened to sit rather than on the grading. A monotone sweep
     * that has to contain more than one distinct value cannot be satisfied by
     * a saturating implementation.
     */
    @Test
    fun `strength follows how hard the hit was`() {
        // A varied baseline, so the rolling std is wide enough that the low end
        // of the sweep lands inside the graded band instead of above it.
        val baseline = floatArrayOf(0.3f, 1.1f, 0.5f, 1.6f, 0.8f, 2.2f, 0.4f, 1.3f)

        fun peak(level: Float): Float {
            val d = DrumChannels(bandCount, hopRateHz, rate)
            val quiet = FloatArray(bandCount)
            repeat(24) { i ->
                d.step(bands(bandOf(50f), bandOf(110f), baseline[i % baseline.size]))
                repeat(29) { d.step(quiet) }
            }
            d.step(bands(bandOf(50f), bandOf(110f), level))
            return d.kickImpulse
        }
        val sweep = listOf(0.2f, 0.6f, 1.2f, 2.0f, 3.2f, 6f).map(::peak)
        for (i in 1 until sweep.size) {
            assertTrue("not monotone at $i: $sweep", sweep[i] >= sweep[i - 1] - 1e-6f)
        }
        assertTrue("grading is saturated or dead: $sweep", sweep.distinct().size >= 2)
        assertEquals("the hardest hit should reach full strength", 1f, sweep.last(), 1e-4f)
    }

    @Test
    fun `reset forgets the previous audio`() {
        val d = DrumChannels(bandCount, hopRateHz, rate)
        repeat(300) { d.step(bands(0, bandCount, 2f)) }
        d.reset()
        assertEquals(0f, d.kickImpulse, 0f)
        assertEquals(0f, d.snareImpulse, 0f)
        assertEquals(0f, d.hatImpulse, 0f)
        val quiet = FloatArray(bandCount)
        d.step(quiet)
        assertEquals("a fresh instance must not fire on silence", 0f, d.kickImpulse, 0f)
    }

    /** Same input, same output - live and any replay must not drift apart. */
    @Test
    fun `stepping is deterministic`() {
        fun run(): List<Float> {
            val d = DrumChannels(bandCount, hopRateHz, rate)
            val out = ArrayList<Float>()
            repeat(400) { i ->
                d.step(bands(bandOf(50f), bandOf(110f), if (i % 23 == 0) 1.5f else 0.05f))
                out += d.kickImpulse
            }
            return out
        }
        assertEquals(run(), run())
    }
}
