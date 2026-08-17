package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/**
 * The node that makes the engine audio-*reactive* rather than audio-*measuring*.
 *
 * The legacy path mapped an absolute dB level onto 0..1 against a fixed -72 dB
 * floor. Measured over pink noise at a normal master level that put the band
 * drivers at 0.02..0.08 — every scene multiplying by them was being driven at
 * a twentieth of the amplitude it was written for — and at -30 dBFS the mid and
 * treble drivers were identically zero. The first two tests here are those two
 * failures, written as the properties that must hold instead.
 */
class AdaptiveRangeTest {
    private fun rangeOf(bandCount: Int = 1) = AdaptiveRange(bandCount)

    /** Feeds one band a dB curve and returns the normalized output per frame. */
    private fun drive(
        range: AdaptiveRange,
        curveDb: FloatArray,
        dt: Float = 1f / 60f,
    ): FloatArray {
        val input = FloatArray(1)
        val out = FloatArray(1)
        return FloatArray(curveDb.size) { i ->
            input[0] = curveDb[i]
            range.normalize(input, dt, out)
            out[0]
        }
    }

    /** A 2 Hz musical swell, 24 dB peak-to-peak, around [centreDb]. */
    private fun swell(
        centreDb: Float,
        frames: Int = 600,
    ) = FloatArray(frames) { i -> centreDb + 12f * sin(2.0 * Math.PI * 2.0 * i / 60.0).toFloat() }

    /**
     * THE property. The same musical dynamics at two master levels 30 dB apart
     * must drive the visuals identically — that is what "reactive" means, and
     * it is what an absolute floor cannot do.
     */
    @Test
    fun `the same dynamics normalize the same way at any master level`() {
        val loud = drive(rangeOf(), swell(centreDb = -12f))
        val quiet = drive(rangeOf(), swell(centreDb = -42f))
        // Compare after the ranges have settled, skipping the warmup.
        for (i in 300 until loud.size) {
            assertTrue(
                "frame $i: loud=${loud[i]} quiet=${quiet[i]}",
                abs(loud[i] - quiet[i]) < 0.02f,
            )
        }
    }

    /** The other half of the same failure: the output must use its whole range. */
    @Test
    fun `a settled signal spans most of zero to one`() {
        val out = drive(rangeOf(), swell(centreDb = -35f))
        val settled = out.copyOfRange(300, out.size)
        assertTrue("max was ${settled.max()}", settled.max() > 0.9f)
        assertTrue("min was ${settled.min()}", settled.min() < 0.1f)
    }

    @Test
    fun `silence reads zero rather than amplified noise`() {
        val out = drive(rangeOf(), FloatArray(600) { AdaptiveRange.SILENCE_DB - 10f })
        assertTrue("last was ${out.last()}", out.all { it == 0f })
    }

    /**
     * A silent gap must not destroy what the range learned: the visuals should
     * come back where they left off, not re-learn from scratch over the first
     * bar after the break.
     */
    @Test
    fun `a silent gap freezes adaptation instead of resetting it`() {
        val range = rangeOf()
        val before = drive(range, swell(centreDb = -20f))
        drive(range, FloatArray(120) { AdaptiveRange.SILENCE_DB - 10f })
        val after = drive(range, swell(centreDb = -20f), dt = 1f / 60f)
        assertTrue(
            "before=${before.last()} after=${after.last()}",
            abs(before.last() - after.last()) < 0.05f,
        )
    }

    /**
     * A band with no dynamics must sit still. Without a minimum span the
     * division amplifies whatever dither is present to full scale, which is
     * the classic adaptive-normalizer failure: a dead channel that strobes.
     */
    @Test
    fun `a near-constant band does not get amplified to full scale`() {
        val dither = FloatArray(900) { i -> -30f + 0.05f * sin(i.toDouble()).toFloat() }
        val out = drive(rangeOf(), dither)
        val settled = out.copyOfRange(600, out.size)
        assertTrue("span reached ${settled.max() - settled.min()}", settled.max() - settled.min() < 0.15f)
    }

    @Test
    fun `bands adapt independently`() {
        val range = AdaptiveRange(bandCount = 2)
        val input = FloatArray(2)
        val out = FloatArray(2)
        repeat(600) { i ->
            input[0] = -20f + 12f * sin(2.0 * Math.PI * 2.0 * i / 60.0).toFloat()
            input[1] = -60f // dead band
            range.normalize(input, 1f / 60f, out)
        }
        assertTrue("live band read ${out[0]}", out[0] > 0.05f || out[0] < 0.95f)
        assertTrue("dead band read ${out[1]}", out[1] < 0.15f)
    }

    @Test
    fun `reset forgets the learned range`() {
        val range = rangeOf()
        drive(range, swell(centreDb = -12f))
        range.reset()
        val out = drive(range, swell(centreDb = -12f), dt = 1f / 60f)
        // First frame after a reset is the centre of a fresh window, never a
        // full-scale flash inherited from the previous track.
        assertEquals(0.5f, out.first(), 0.2f)
    }

    @Test
    fun `warmup is reported until the range has opened up`() {
        val range = rangeOf()
        assertTrue(range.warmup < 1f)
        drive(range, swell(centreDb = -20f))
        assertEquals(1f, range.warmup, 0f)
    }
}
