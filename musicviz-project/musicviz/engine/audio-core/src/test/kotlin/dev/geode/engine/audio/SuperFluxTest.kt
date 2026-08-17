package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sin

/**
 * Böck & Widmer's SuperFlux: spectral flux with a maximum filter across
 * frequency, so that a wobbling pitch stops looking like a run of onsets.
 *
 * `docs/quality/bar-visualizer.md` §2.4 names it as the state of the art and
 * the bonus bar for this repo. The property it buys over plain flux is the
 * third test here, and it is not a subtlety: vibrato and tremolo on strings,
 * vocals and synth leads move energy between adjacent bands every few frames,
 * and a plain difference counts every one of those as a rise.
 */
class SuperFluxTest {
    private val bandCount = 24

    /** A tone of width [width] bands centred at [centre]. */
    private fun tone(
        centre: Float,
        amplitude: Float = 1f,
        width: Float = 0.7f,
    ) = FloatArray(bandCount) { k ->
        amplitude * exp(-((k - centre) * (k - centre)) / (2f * width * width))
    }

    private fun silence() = FloatArray(bandCount)

    @Test
    fun `a steady spectrum produces no flux`() {
        val flux = SuperFlux(bandCount)
        val held = tone(centre = 12f)
        repeat(10) { flux.next(held) }
        assertEquals(0f, flux.next(held), 1e-6f)
    }

    @Test
    fun `the first frames report nothing rather than a phantom onset`() {
        val flux = SuperFlux(bandCount)
        assertEquals(0f, flux.next(tone(centre = 12f)), 0f)
    }

    /**
     * THE property. A tone whose centre wobbles by half a band — vibrato —
     * against a tone that actually starts. Plain flux cannot tell them apart;
     * the max filter must.
     */
    @Test
    fun `vibrato produces far less flux than a real onset`() {
        fun vibratoFlux(maxFilterBands: Int): Float {
            val flux = SuperFlux(bandCount, maxFilterBands = maxFilterBands)
            var total = 0f
            repeat(120) { f ->
                val centre = 12f + 0.5f * sin(2.0 * Math.PI * f / 10.0).toFloat()
                val value = flux.next(tone(centre))
                if (f >= 10) total += value
            }
            return total
        }

        val plain = vibratoFlux(maxFilterBands = 1)
        val superFlux = vibratoFlux(maxFilterBands = 3)
        assertTrue(
            "plain flux accumulated $plain, SuperFlux $superFlux",
            superFlux < plain * 0.4f,
        )
    }

    @Test
    fun `a real onset still registers through the max filter`() {
        val flux = SuperFlux(bandCount, maxFilterBands = 3)
        repeat(10) { flux.next(silence()) }
        val onset = flux.next(tone(centre = 12f))
        assertTrue("onset read $onset", onset > 0.01f)
    }

    /** Only rises are onsets: a note ending must not fire one. */
    @Test
    fun `a falling spectrum is rectified away`() {
        val flux = SuperFlux(bandCount, maxFilterBands = 3)
        repeat(10) { flux.next(tone(centre = 12f)) }
        assertEquals(0f, flux.next(silence()), 1e-6f)
    }

    /**
     * The lag is what makes the detector work at short hops: differencing
     * against the immediately previous frame of a slow attack sees a series of
     * small rises rather than one clear one.
     */
    @Test
    fun `a longer lag compares against an older frame`() {
        fun rampFlux(lag: Int): Float {
            val flux = SuperFlux(bandCount, maxFilterBands = 3, lagFrames = lag)
            var last = 0f
            repeat(12) { f -> last = flux.next(tone(centre = 12f, amplitude = 0.1f * f)) }
            return last
        }
        assertTrue("lag 1 gave ${rampFlux(1)}, lag 3 gave ${rampFlux(3)}", rampFlux(3) > rampFlux(1) * 2f)
    }

    @Test
    fun `reset clears the history`() {
        val flux = SuperFlux(bandCount, maxFilterBands = 3)
        repeat(10) { flux.next(tone(centre = 12f)) }
        flux.reset()
        assertEquals(0f, flux.next(tone(centre = 12f)), 0f)
    }

    @Test
    fun `an even max filter width is rejected rather than silently rounded`() {
        val error =
            runCatching { SuperFlux(bandCount, maxFilterBands = 4) }.exceptionOrNull()
        assertTrue("threw $error", error is IllegalArgumentException)
    }
}
