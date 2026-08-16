package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The window and spectrum nodes, and the properties an STFT rests on. */
class SpectrumTest {
    private val rate = 48_000

    private fun tone(
        hz: Double,
        frames: Int,
        amp: Float = 1f,
    ) = FloatArray(frames) { i -> amp * sin(2.0 * PI * hz * i / rate).toFloat() }

    @Test
    fun `overlapping periodic Hann windows sum to a constant`() {
        // The property that makes the periodic form the right one for an STFT:
        // at a hop dividing the window, the overlapped windows add to unity, so
        // successive frames neither ripple nor need a correction factor. The
        // symmetric form - dividing by size - 1 - does not have it.
        val size = 1024
        val hop = size / 4
        val table = WindowTable(size)
        val sum = FloatArray(size * 4)
        var offset = 0
        while (offset + size <= sum.size) {
            for (i in 0 until size) sum[offset + i] += table.coefficient(i)
            offset += hop
        }
        // Sample well inside the overlap-add region, away from the ramp at
        // either end where fewer windows contribute.
        for (i in size until sum.size - size) {
            assertEquals("overlap-add rippled at $i", 2f, sum[i], 1e-5f)
        }
    }

    @Test
    fun `the periodic window differs from the symmetric one, deliberately`() {
        val size = 8
        val periodic = WindowTable(size)
        val symmetric = FloatArray(size) { i -> (0.5 - 0.5 * kotlin.math.cos(2.0 * PI * i / (size - 1))).toFloat() }
        assertEquals("both start at zero", 0f, periodic.coefficient(0), 1e-7f)
        assertEquals("the symmetric form closes on zero; the periodic one does not", 0f, symmetric[size - 1], 1e-7f)
        assertTrue("the two must not be the same table", abs(periodic.coefficient(size - 1) - symmetric[size - 1]) > 0.1f)
    }

    @Test
    fun `a window reads zero outside the source rather than wrapping`() {
        // Frames near the start of a stream begin before sample zero by
        // construction. Reading out of bounds must give silence, not a wrapped
        // sample from the far end, which would put a phantom transient into
        // the first frame of every track.
        val table = WindowTable(8, WindowShape.RECTANGULAR)
        val out = FloatArray(8)
        table.applyInto(FloatArray(8) { 1f }, sourceOffset = -4, out = out)
        assertEquals(listOf(0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f), out.toList())
    }

    @Test
    fun `a tone lands in the bin that holds it`() {
        val size = 4096
        val spectrum = Spectrum(size)
        val binHz = spectrum.binHz(rate)
        // Exactly on a bin centre, so there is no leakage to argue about.
        val bin = 100
        val windowed = FloatArray(size)
        WindowTable(size).applyInto(tone(bin * binHz, size), 0, windowed)
        spectrum.compute(windowed)

        assertEquals(bin, spectrum.peakBin())
        assertTrue(
            "the peak should dominate its neighbours",
            spectrum.magnitudes[bin] > 10f * spectrum.magnitudes[bin + 4],
        )
    }

    @Test
    fun `the spectrum spans DC to Nyquist inclusive`() {
        // The legacy FftProcessor zeroes DC and stops one bin short, which is
        // harmless for its log bands and wrong for anything that integrates
        // across the axis. Asserted here so the new nodes cannot inherit it.
        val size = 64
        val spectrum = Spectrum(size)
        assertEquals(size / 2 + 1, spectrum.magnitudes.size)

        val dc = FloatArray(size) { 1f }
        spectrum.compute(dc)
        assertTrue("DC must be measurable, not zeroed", spectrum.magnitudes[0] > 1f)

        // Nyquist: alternating +1/-1 is the highest representable frequency.
        val nyquist = FloatArray(size) { if (it % 2 == 0) 1f else -1f }
        spectrum.compute(nyquist)
        assertEquals("all the energy belongs in the last bin", size / 2, spectrum.magnitudes.indices.maxBy { spectrum.magnitudes[it] })
    }

    @Test
    fun `Parseval holds, so the magnitudes are scaled consistently`() {
        // An independent check that the unpacking is right: the energy in the
        // spectrum must equal the energy in the frame. A mis-packed real
        // transform - the classic error with JTransforms' shared first pair -
        // fails this even when the peak bin still looks plausible.
        val size = 1024
        val spectrum = Spectrum(size)
        val frame = tone(1_000.0, size, amp = 0.7f)
        spectrum.compute(frame)

        val timeEnergy = frame.sumOf { (it * it).toDouble() }
        var specEnergy = 0.0
        for (k in spectrum.magnitudes.indices) {
            val m = spectrum.magnitudes[k].toDouble()
            // DC and Nyquist appear once; every other bin stands for a
            // conjugate pair.
            specEnergy += if (k == 0 || k == size / 2) m * m else 2.0 * m * m
        }
        assertEquals(timeEnergy, specEnergy / size, timeEnergy * 1e-4)
    }

    @Test
    fun `computing a frame allocates nothing`() {
        val size = 1024
        val spectrum = Spectrum(size)
        val frame = tone(440.0, size)
        val perFrame = JvmAllocationMeter.perRun(RUNS) { spectrum.compute(frame) }
        assertTrue("a spectrum allocated $perFrame bytes per frame", perFrame < BUDGET_BYTES)
        val control = JvmAllocationMeter.perRun(RUNS) { FloatArray(2) }
        assertTrue("the meter reads $control bytes for a loop that allocates; it sees nothing", control > BUDGET_BYTES)
    }

    @Test
    fun `an unusable size is refused at construction`() {
        listOf(1000, 0, 1).forEach { size ->
            try {
                Spectrum(size)
                error("expected $size to be rejected")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("power of two"))
            }
        }
    }

    private companion object {
        const val RUNS = 5_000
        const val BUDGET_BYTES = 8.0
    }
}
