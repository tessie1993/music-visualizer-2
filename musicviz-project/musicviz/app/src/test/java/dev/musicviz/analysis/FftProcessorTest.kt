package dev.musicviz.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftProcessorTest {
    private val sampleRate = 44100

    private fun sine(
        freqHz: Float,
        size: Int,
    ): FloatArray = FloatArray(size) { i -> sin(2.0 * PI * freqHz * i / sampleRate).toFloat() }

    private fun bandFor(
        processor: FftProcessor,
        freqHz: Float,
    ): Int {
        val edges = processor.bandEdges(sampleRate)
        val bin = (freqHz / (sampleRate / 2f) * (processor.fftSize / 2)).toInt()
        for (b in 0 until processor.bandCount) {
            if (bin in edges[b]..edges[b + 1]) return b
        }
        return processor.bandCount - 1
    }

    @Test
    fun `sine energy lands in expected band`() {
        val processor = FftProcessor()
        val freq = 1000f
        val bands = FloatArray(processor.bandCount)
        processor.process(sine(freq, processor.fftSize), sampleRate, bands)
        val expected = bandFor(processor, freq)
        val peakBand = bands.indices.maxBy { bands[it] }
        assertTrue(
            "peak band $peakBand should be within 1 of expected $expected",
            kotlin.math.abs(peakBand - expected) <= 1,
        )
    }

    @Test
    fun `silence produces near-zero bands`() {
        val processor = FftProcessor()
        val bands = FloatArray(processor.bandCount)
        processor.process(FloatArray(processor.fftSize), sampleRate, bands)
        for (v in bands) assertTrue("expected near zero, got $v", v < 0.05f)
    }

    @Test
    fun `band edges are monotonic and in range`() {
        val processor = FftProcessor()
        val edges = processor.bandEdges(sampleRate)
        assertEquals(processor.bandCount + 1, edges.size)
        for (b in 1 until edges.size) {
            assertTrue("edges must strictly increase", edges[b] > edges[b - 1] || edges[b] == processor.fftSize / 2 - 1)
            assertTrue(edges[b] < processor.fftSize / 2)
        }
    }

    @Test
    fun `bands are normalized to unit range`() {
        val processor = FftProcessor()
        val bands = FloatArray(processor.bandCount)
        processor.process(sine(440f, processor.fftSize), sampleRate, bands)
        for (v in bands) assertTrue(v in 0f..1f)
    }
}
