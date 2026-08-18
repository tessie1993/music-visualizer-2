package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The stereo measurements, checked against signals whose correct answer is
 * known by construction. The ring-buffer plumbing that produces mid/side in
 * production is pinned app-side, next to the ring.
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

    /**
     * A channel that is nearly-but-not-exactly silent makes `sum(L*L)` a
     * difference of near-equal float sums: catastrophic cancellation can land
     * it a hair below zero, and an unfloored denominator turns that into
     * `sqrt(negative)` = NaN - which then poisons everything smoothed from
     * the reading. The window is FOUND, not assumed: seeds are searched until
     * the accumulation, replicated term for term, actually goes negative, so
     * this test fails loudly if the hazard it pins ever stops being
     * constructible instead of silently pinning nothing.
     */
    @Test
    fun `a nearly-silent channel cannot produce NaN`() {
        val mid = FloatArray(n)
        val side = FloatArray(n)
        var hazardous = false
        var seed = 0
        while (seed < 512 && !hazardous) {
            val rnd = java.util.Random(seed.toLong())
            for (i in 0 until n) {
                val v = (rnd.nextFloat() * 2f - 1f) * 0.8f
                mid[i] = v
                // L = mid + side: a whisper above exact silence, R carries it all.
                side[i] = -v + (rnd.nextFloat() * 2f - 1f) * 1e-7f
            }
            hazardous = llAsComputed(mid, side) < 0f
            seed++
        }
        assertTrue("no seed cancelled sum(L*L) below zero; the hazard this test pins is gone", hazardous)
        val c = StereoField.correlation(mid, side)
        assertFalse("correlation is NaN", c.isNaN())
        assertTrue("correlation $c out of range", c in -1f..1f)
    }

    /** `sum(L*L)` accumulated exactly as [StereoField.correlation] computes it. */
    private fun llAsComputed(
        mid: FloatArray,
        side: FloatArray,
    ): Float {
        var mm = 0f
        var ss = 0f
        var ms = 0f
        for (i in 0 until n) {
            val m = mid[i]
            val s = side[i]
            mm += m * m
            ss += s * s
            ms += m * s
        }
        return mm + 2f * ms + ss
    }

    @Test
    fun `readings stay in range on hostile input`() {
        val mid = FloatArray(n) { if (it % 3 == 0) 12f else -9f }
        val side = FloatArray(n) { if (it % 5 == 0) -14f else 11f }
        val r = StereoField.of(mid, side)
        assertTrue("width $r", r.width in 0f..1f)
        assertTrue("correlation $r", r.correlation in -1f..1f)
        assertTrue("pan $r", r.pan in -1f..1f)
    }

    // ---- pan ---------------------------------------------------------------

    @Test
    fun `everything on the left reads pan minus one`() {
        val t = tone(440f)
        val (mid, side) = ms(t) { 0f }
        assertEquals(-1f, StereoField.pan(mid, side), 1e-4f)
    }

    @Test
    fun `everything on the right reads pan plus one`() {
        val t = tone(440f)
        val (mid, side) = ms({ 0f }, t)
        assertEquals(1f, StereoField.pan(mid, side), 1e-4f)
    }

    @Test
    fun `a centred source and silence both read pan zero`() {
        val t = tone(440f)
        val (mid, side) = ms(t, t)
        assertEquals(0f, StereoField.pan(mid, side), 1e-4f)
        val zero = FloatArray(n)
        assertEquals(0f, StereoField.pan(zero, zero), 0f)
        assertEquals(0f, StereoField.MONO.pan, 0f)
    }

    /** Pan is a balance, not a level: quieter overall must not re-centre it. */
    @Test
    fun `pan is independent of level`() {
        val loud = ms(tone(440f, amp = 0.9f), tone(440f, amp = 0.45f))
        val quiet = ms(tone(440f, amp = 0.09f), tone(440f, amp = 0.045f))
        assertEquals(
            StereoField.pan(loud.first, loud.second),
            StereoField.pan(quiet.first, quiet.second),
            1e-3f,
        )
    }
}
