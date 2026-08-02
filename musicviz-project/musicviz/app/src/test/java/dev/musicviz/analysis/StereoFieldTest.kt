package dev.musicviz.analysis

import dev.musicviz.audio.PcmRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The stereo measurements, checked against signals whose correct answer is
 * known by construction, plus the ring-buffer plumbing that produces them.
 */
class StereoFieldTest {
    private val n = 2048

    /** Mid/side for a given L and R generator, as the ring buffer computes them. */
    private fun ms(
        left: (Int) -> Float,
        right: (Int) -> Float,
    ): Pair<FloatArray, FloatArray> {
        val mid = FloatArray(n)
        val side = FloatArray(n)
        for (i in 0 until n) {
            val l = left(i)
            val r = right(i)
            mid[i] = (l + r) * 0.5f
            side[i] = (l - r) * 0.5f
        }
        return mid to side
    }

    private fun tone(
        hz: Float,
        phase: Float = 0f,
        amp: Float = 0.8f,
    ): (Int) -> Float = { i -> (amp * sin(2.0 * PI * hz * i / 48_000.0 + phase)).toFloat() }

    // ---- correlation -------------------------------------------------------

    @Test
    fun `identical channels are perfectly correlated and have no width`() {
        val t = tone(440f)
        val (mid, side) = ms(t, t)
        assertEquals(1f, StereoField.correlation(mid, side), 1e-4f)
        assertEquals(0f, StereoField.width(mid, side), 1e-4f)
    }

    @Test
    fun `inverted channels are anti-correlated and fully wide`() {
        val t = tone(440f)
        val (mid, side) = ms(t) { i -> -t(i) }
        assertEquals(-1f, StereoField.correlation(mid, side), 1e-4f)
        assertEquals(1f, StereoField.width(mid, side), 1e-4f)
    }

    /**
     * A source panned hard to one side: the other channel is silent, so mid
     * and side carry equal energy. Correlation is +1 - one signal cannot be
     * out of phase with itself - and width sits at exactly a half, which is
     * why width and `1 - correlation` are not interchangeable.
     */
    @Test
    fun `a hard-panned source is half wide and still correlated`() {
        val t = tone(440f)
        val (mid, side) = ms(t) { 0f }
        assertEquals(1f, StereoField.correlation(mid, side), 1e-4f)
        assertEquals(0.5f, StereoField.width(mid, side), 1e-4f)
    }

    /**
     * Two different frequencies, chosen so they are orthogonal over the
     * window: decorrelated, but with equal energy in both channels.
     */
    @Test
    fun `decorrelated channels sit near zero correlation`() {
        val (mid, side) = ms(tone(375f), tone(1_125f))
        assertTrue(
            "expected near-zero, got ${StereoField.correlation(mid, side)}",
            kotlin.math.abs(StereoField.correlation(mid, side)) < 0.05f,
        )
        val w = StereoField.width(mid, side)
        assertTrue("expected mid width, got $w", w > 0.3f && w < 0.7f)
    }

    /** Width says how much of what is playing is wide; level must not change it. */
    @Test
    fun `width is independent of level`() {
        val quiet = ms(tone(440f, amp = 0.02f), tone(440f, phase = 1.2f, amp = 0.02f))
        val loud = ms(tone(440f, amp = 0.9f), tone(440f, phase = 1.2f, amp = 0.9f))
        assertEquals(
            StereoField.width(quiet.first, quiet.second),
            StereoField.width(loud.first, loud.second),
            1e-3f,
        )
    }

    // ---- degenerate inputs -------------------------------------------------

    /**
     * Silence must read as mono, not as maximum decorrelation - otherwise
     * every gap between tracks swings anything driven by these.
     */
    @Test
    fun `silence reads as mono`() {
        val zero = FloatArray(n)
        assertEquals(1f, StereoField.correlation(zero, zero), 0f)
        assertEquals(0f, StereoField.width(zero, zero), 0f)
    }

    @Test
    fun `an empty window reads as mono`() {
        val zero = FloatArray(0)
        assertEquals(StereoField.MONO.correlation, StereoField.correlation(zero, zero), 0f)
        assertEquals(StereoField.MONO.width, StereoField.width(zero, zero), 0f)
    }

    @Test
    fun `readings stay in range on hostile input`() {
        val mid = FloatArray(n) { if (it % 3 == 0) 12f else -9f }
        val side = FloatArray(n) { if (it % 5 == 0) -14f else 11f }
        val r = StereoField.of(mid, side)
        assertTrue("width $r", r.width in 0f..1f)
        assertTrue("correlation $r", r.correlation in -1f..1f)
    }

    // ---- ring-buffer plumbing ---------------------------------------------

    /**
     * The mono channel must be BYTE-IDENTICAL to what it was before the side
     * channel existed, because every downstream stage - FFT, bands, flux,
     * tempo - reads it and none of them were meant to change.
     */
    @Test
    fun `the mid channel is still the plain mono downmix`() {
        val ring = PcmRingBuffer(1 shl 12)
        val frames = 512
        val interleaved = FloatArray(frames * 2)
        for (i in 0 until frames) {
            interleaved[i * 2] = tone(440f)(i)
            interleaved[i * 2 + 1] = tone(660f)(i)
        }
        ring.writeInterleaved(interleaved, frames, 2)
        val mid = FloatArray(frames)
        assertTrue(ring.snapshotLatest(mid))
        for (i in 0 until frames) {
            assertEquals("frame $i", (interleaved[i * 2] + interleaved[i * 2 + 1]) / 2f, mid[i], 1e-6f)
        }
    }

    @Test
    fun `the ring buffer recovers left and right exactly`() {
        val ring = PcmRingBuffer(1 shl 12)
        val frames = 512
        val interleaved = FloatArray(frames * 2)
        for (i in 0 until frames) {
            interleaved[i * 2] = tone(440f)(i)
            interleaved[i * 2 + 1] = tone(660f, phase = 0.7f)(i)
        }
        ring.writeInterleaved(interleaved, frames, 2)
        val mid = FloatArray(frames)
        val side = FloatArray(frames)
        assertTrue(ring.snapshotLatest(mid))
        assertTrue(ring.snapshotLatestSide(side))
        for (i in 0 until frames) {
            assertEquals("L @$i", interleaved[i * 2], mid[i] + side[i], 1e-6f)
            assertEquals("R @$i", interleaved[i * 2 + 1], mid[i] - side[i], 1e-6f)
        }
    }

    @Test
    fun `a mono source has an all-zero side channel`() {
        val ring = PcmRingBuffer(1 shl 12)
        val frames = 256
        val interleaved = FloatArray(frames) { tone(440f)(it) }
        ring.writeInterleaved(interleaved, frames, 1)
        val side = FloatArray(frames)
        assertTrue(ring.snapshotLatestSide(side))
        for (i in 0 until frames) assertEquals("frame $i", 0f, side[i], 0f)
        val mid = FloatArray(frames)
        assertTrue(ring.snapshotLatest(mid))
        assertEquals(StereoField.MONO, StereoField.of(mid, side))
    }

    /**
     * Side comes from the front pair on a surround source, not from a fold of
     * every channel - the surrounds are not part of the image two speakers
     * will reproduce.
     */
    @Test
    fun `side uses the front pair of a surround source`() {
        val ring = PcmRingBuffer(1 shl 12)
        val channels = 6
        val frames = 128
        val interleaved = FloatArray(frames * channels)
        for (i in 0 until frames) {
            val base = i * channels
            interleaved[base] = 0.5f // L
            interleaved[base + 1] = -0.3f // R
            for (c in 2 until channels) interleaved[base + c] = 0.9f // surrounds
        }
        ring.writeInterleaved(interleaved, frames, channels)
        val side = FloatArray(frames)
        assertTrue(ring.snapshotLatestSide(side))
        for (i in 0 until frames) assertEquals("frame $i", (0.5f - -0.3f) / 2f, side[i], 1e-6f)
    }
}
