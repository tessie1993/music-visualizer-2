package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The corners the corpus cannot reach; the oracle sweep lives app-side. */
class MfccTest {
    private val nFft = 1024
    private val sampleRate = 22_050

    @Test
    fun `silence is a flat cepstrum - level in c0, nothing anywhere else`() {
        val mfcc = Mfcc(40, 13)
        mfcc.compute(FloatArray(40))
        assertTrue("c0 must be finite on silence", mfcc.coefficients[0].isFinite())
        for (c in 1 until 13) {
            assertEquals("c$c of a constant log-mel must be 0", 0f, mfcc.coefficients[c], 1e-3f)
        }
    }

    @Test
    fun `delta is the difference of successive frames and starts at zero`() {
        val mfcc = Mfcc(40, 13)
        val a = FloatArray(40) { 0.1f + it * 0.01f }
        val b = FloatArray(40) { 0.5f - it * 0.005f }
        mfcc.compute(a)
        for (c in 0 until 13) assertEquals("first frame has no predecessor", 0f, mfcc.delta[c], 0f)
        val first = mfcc.coefficients.copyOf()
        mfcc.compute(b)
        for (c in 0 until 13) {
            assertEquals("delta[$c]", mfcc.coefficients[c] - first[c], mfcc.delta[c], 1e-6f)
        }
    }

    @Test
    fun `timbre flux ignores level`() {
        val mfcc = Mfcc(40, 13)
        val shape = FloatArray(40) { 0.2f + 0.1f * (it % 5) }
        mfcc.compute(shape)
        // The same shape four times louder: c0 moves, c1..c12 do not.
        mfcc.compute(FloatArray(40) { shape[it] * 4f })
        assertTrue("flux should ignore a pure level change, read ${mfcc.timbreFlux}", mfcc.timbreFlux < 1e-3f)
        assertTrue("delta c0 must see the level change", kotlin.math.abs(mfcc.delta[0]) > 1f)
    }

    @Test
    fun `reset forgets the previous frame`() {
        val mfcc = Mfcc(40, 13)
        mfcc.compute(FloatArray(40) { 0.3f })
        mfcc.reset()
        mfcc.compute(FloatArray(40) { 0.9f })
        assertEquals("flux after reset", 0f, mfcc.timbreFlux, 0f)
        for (c in 0 until 13) assertEquals(0f, mfcc.delta[c], 0f)
    }

    @Test
    fun `the mel bank covers the spectrum without gaps`() {
        val mel = MelBank(nFft, sampleRate, 40)
        // A flat spectrum must reach every filter: a mel whose triangle fell
        // between bins would read 0 and silently zero its cepstral share.
        val flat = FloatArray(nFft / 2 + 1) { 1f }
        val out = FloatArray(40)
        mel.power(flat, out)
        for (m in 0 until 40) assertTrue("mel $m collected nothing", out[m] > 0f)
    }
}
