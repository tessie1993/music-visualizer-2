package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class OnsetStrengthTest {
    /** BTT's own onset rate: 44.1 kHz over a hop of 128. */
    private val bttRateHz = 44_100f / 128f

    /** The general branch's, which is where this would actually run. */
    private val generalRateHz = 48_000f / 512f

    private fun magnitudeAt(
        node: OnsetStrength,
        hz: Float,
        rateHz: Float,
    ): Double {
        var re = 0.0
        var im = 0.0
        for (k in 0 until node.taps) {
            val phase = -2.0 * PI * hz * k / rateHz
            re += node.coefficientAt(k) * cos(phase)
            im += node.coefficientAt(k) * sin(phase)
        }
        return hypot(re, im)
    }

    /** Autocorrelation at one lag, normalised by the signal's own energy. */
    private fun autocorrelationAt(
        signal: FloatArray,
        lag: Int,
    ): Double {
        var num = 0.0
        var den = 0.0
        for (i in signal.indices) {
            den += signal[i].toDouble() * signal[i]
            if (i >= lag) num += signal[i].toDouble() * signal[i - lag]
        }
        return if (den == 0.0) 0.0 else num / den
    }

    @Test
    fun `the kernel is odd, symmetric and unit gain at DC`() {
        for (rate in listOf(bttRateHz, generalRateHz, 43f, 1_000f)) {
            val node = OnsetStrength(rate)
            assertEquals("taps must be odd at $rate", 1, node.taps % 2)
            assertEquals("taps must bracket the delay", 2 * node.delayFrames + 1, node.taps)
            var sum = 0.0
            for (k in 0 until node.taps) {
                sum += node.coefficientAt(k)
                assertEquals(
                    "tap $k is not the mirror of ${node.taps - 1 - k} at $rate",
                    node.coefficientAt(k),
                    node.coefficientAt(node.taps - 1 - k),
                    0f,
                )
            }
            assertEquals("DC gain at $rate", 1.0, sum, 1e-6)
        }
    }

    @Test
    fun `an impulse comes out exactly delayFrames later`() {
        // The property the odd tap count buys, and the one a 16-tap kernel
        // cannot have: the peak lands on a frame, not between two.
        for (rate in listOf(bttRateHz, generalRateHz, 43f)) {
            val node = OnsetStrength(rate)
            val out = FloatArray(4 * node.taps)
            out[0] = node.next(1f)
            for (i in 1 until out.size) out[i] = node.next(0f)
            var peak = 0
            for (i in out.indices) if (out[i] > out[peak]) peak = i
            assertEquals("at $rate the peak moved", node.delayFrames, peak)
            // And it is a strict peak, not a plateau the argmax picked from.
            assertTrue("the peak at $rate is not strict", out[peak] > out[peak - 1] && out[peak] > out[peak + 1])
        }
    }

    @Test
    fun `the kernel spans the same time at every hop rate`() {
        // The BandSmoother mistake, not repeated: a fixed tap count would make
        // this the same number of frames and so a different amount of music at
        // every branch. Delay in seconds is what stays put.
        for (rate in listOf(bttRateHz, generalRateHz, 172f, 43f)) {
            val node = OnsetStrength(rate)
            assertEquals("at $rate", OnsetStrength.DELAY_SECONDS.toDouble(), node.delaySecondsActual.toDouble(), 0.004)
        }
        // At BTT's own rate the kernel comes out 17 taps against its 16, which
        // is what makes the two comparable in the first place.
        assertEquals(17, OnsetStrength(bttRateHz).taps)
        assertEquals(8, OnsetStrength(bttRateHz).delayFrames)
    }

    @Test
    fun `it passes a steady flux through untouched`() {
        // Unit DC gain, in the units a caller sees. BTT's kernel sums to 0.451
        // and would return 0.9 here, which is why its threshold minimum is a
        // raw number that only means anything against its own scaling.
        val node = OnsetStrength(bttRateHz)
        repeat(200) { node.next(2f) }
        assertEquals(2f, node.next(2f), 1e-5f)
    }

    @Test
    fun `a non-negative flux stream cannot ring negative`() {
        val node = OnsetStrength(generalRateHz)
        for (k in 0 until node.taps) assertTrue("tap $k is negative", node.coefficientAt(k) >= 0f)
        val random = Random(4)
        repeat(20_000) {
            // Flux is a half-wave-rectified sum, so it is never negative; a
            // kernel with negative lobes would still undershoot after a spike.
            val flux = if (random.nextInt(20) == 0) 40f * random.nextFloat() else 0f
            assertTrue("the onset strength went negative", node.next(flux) >= 0f)
        }
    }

    @Test
    fun `it keeps the beat rates a tracker looks for and drops what is above them`() {
        // 300 BPM is 5 Hz, the fastest pulse the app's own gate admits. The
        // design cutoff is not the half-power point, so both are measured.
        val node = OnsetStrength(bttRateHz)
        assertTrue("5 Hz was attenuated", magnitudeAt(node, 5f, bttRateHz) > 0.94)
        assertEquals("10 Hz", 0.85, magnitudeAt(node, 10f, bttRateHz), 0.02)
        assertTrue("40 Hz survived", magnitudeAt(node, 40f, bttRateHz) < 0.06)
        assertTrue("80 Hz survived", magnitudeAt(node, 80f, bttRateHz) < 0.01)
    }

    @Test
    fun `smoothing is what lets a jittered pulse still show its period`() {
        // The reason the stage exists, measured. Raw flux impulses are one
        // frame wide, so autocorrelation at the true lag only counts the pairs
        // whose onsets landed on exactly the same frame — real playing, and
        // real frame quantisation, does not. Widened to the kernel's span,
        // neighbouring frames overlap and the period survives.
        val node = OnsetStrength(bttRateHz)
        val periodFrames = 86 // 240 BPM at this rate
        val frames = 4_000
        val random = Random(4)

        val jittered = FloatArray(frames)
        var click = 0
        while (click * periodFrames < frames) {
            jittered[minOf(frames - 1, maxOf(0, click * periodFrames + random.nextInt(3) - 1))] = 10f
            click++
        }
        val smoothed = FloatArray(frames) { node.next(jittered[it]) }

        val rawScore = autocorrelationAt(jittered, periodFrames)
        val smoothScore = autocorrelationAt(smoothed, periodFrames)
        assertTrue("a jittered click track already correlated at $rawScore raw", rawScore < 0.6)
        assertTrue("smoothing did not recover the period ($smoothScore)", smoothScore > 0.85)
        assertTrue("smoothing gained nothing ($rawScore -> $smoothScore)", smoothScore > 2 * rawScore)
    }

    @Test
    fun `and costs nothing when the pulse is already exact`() {
        // The other half: the smoothing is not buying the correlation, the
        // jitter tolerance is. With no jitter the two score the same.
        val node = OnsetStrength(bttRateHz)
        val periodFrames = 86
        val frames = 4_000
        val exact = FloatArray(frames) { if (it % periodFrames == 0) 10f else 0f }
        val smoothed = FloatArray(frames) { node.next(exact[it]) }
        assertEquals(autocorrelationAt(exact, periodFrames), autocorrelationAt(smoothed, periodFrames), 5e-3)
    }

    @Test
    fun `reset returns it to a fresh stream`() {
        val node = OnsetStrength(generalRateHz)
        repeat(500) { node.next(9f) }
        node.reset()
        assertEquals("the previous stream bled through", 0f, node.next(0f), 0f)
    }

    @Test
    fun `one frame allocates nothing`() {
        val node = OnsetStrength(bttRateHz)
        var i = 0
        val perRun = JvmAllocationMeter.perRun(20_000) { node.next((i++ % 13).toFloat()) }
        assertEquals("next allocated $perRun bytes per frame", 0.0, perRun, 1.0)
    }

    @Test
    fun `a malformed node is refused at construction`() {
        val bad =
            listOf(
                { OnsetStrength(0f) },
                { OnsetStrength(generalRateHz, delaySeconds = 0f) },
                { OnsetStrength(generalRateHz, cutoffHz = 0f) },
                { OnsetStrength(generalRateHz, cutoffHz = generalRateHz) },
            )
        for (make in bad) {
            try {
                make()
                throw AssertionError("a malformed node was accepted")
            } catch (expected: IllegalArgumentException) {
                assertTrue("the message says nothing useful", expected.message!!.isNotEmpty())
            }
        }
    }
}
