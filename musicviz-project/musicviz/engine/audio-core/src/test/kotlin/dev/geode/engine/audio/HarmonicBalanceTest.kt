package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Harmonic against percussive, ranked on material built to be each. */
class HarmonicBalanceTest {
    private val fftSize = 1024
    private val hopRateHz = 62.5f
    private val hop = 353

    /** Drives [frames] hops of [sample] through a real spectrum. */
    private fun drive(
        frames: Int,
        node: HarmonicBalance = HarmonicBalance(fftSize / 2 + 1, hopRateHz),
        sample: (Int) -> Float,
    ): HarmonicBalance {
        val spectrum = Spectrum(fftSize)
        val window = WindowTable(fftSize)
        val buffer = FloatArray(fftSize)
        val windowed = FloatArray(fftSize)
        var clock = 0
        repeat(frames) {
            System.arraycopy(buffer, hop, buffer, 0, fftSize - hop)
            for (i in fftSize - hop until fftSize) buffer[i] = sample(clock++)
            window.applyInto(buffer, 0, windowed)
            spectrum.compute(windowed)
            node.step(spectrum.magnitudes)
        }
        return node
    }

    @Test
    fun `a held tone is harmonic and a click train is percussive`() {
        val tone = drive(120) { i -> (0.5 * sin(2.0 * PI * 440.0 * i / 22_050.0)).toFloat() }
        val clicks = drive(120) { i -> if (i % 3675 < 40) 0.9f else 0f }
        assertTrue("tone read ${tone.balance}", tone.balance > 0.8f)
        // The absolute is an approximation; the RANKING below is the
        // contract. Clicks must at least lean percussive.
        assertTrue("clicks read ${clicks.balance}", clicks.balance < HarmonicBalance.UNDECIDED)
        val noise = Random(7)
        val wideband = drive(120) { (noise.nextFloat() * 2f - 1f) * 0.4f }
        assertTrue(
            "ordering broke: tone ${tone.balance}, noise ${wideband.balance}, clicks ${clicks.balance}",
            tone.balance > wideband.balance && wideband.balance > clicks.balance,
        )
    }

    @Test
    fun `silence holds the last reading rather than inventing one`() {
        val node = drive(120) { i -> (0.5 * sin(2.0 * PI * 440.0 * i / 22_050.0)).toFloat() }
        val settled = node.balance
        drive(60, node) { 0f }
        assertEquals("silence moved the balance", settled, node.balance, 0.1f)
    }

    @Test
    fun `reset forgets the spectral history`() {
        val node = drive(120) { i -> (0.5 * sin(2.0 * PI * 440.0 * i / 22_050.0)).toFloat() }
        node.reset()
        assertEquals(0.5f, node.balance, 0f)
    }
}
