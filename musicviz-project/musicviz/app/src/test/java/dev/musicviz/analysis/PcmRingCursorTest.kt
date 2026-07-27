package dev.musicviz.analysis

import dev.musicviz.audio.PcmRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRingCursorTest {
    @Test
    fun `copyNewSince returns only fresh samples`() {
        val ring = PcmRingBuffer(capacity = 256)
        val out = FloatArray(256)
        ring.writeInterleaved(FloatArray(100) { 1f }, frameCount = 100, channelCount = 1)
        var cursor = 0L
        var n = ring.copyNewSince(cursor, out)
        assertEquals(100, n)
        cursor = ring.currentWriteIndex()
        n = ring.copyNewSince(cursor, out)
        assertEquals(0, n)
        ring.writeInterleaved(FloatArray(40) { 2f }, frameCount = 40, channelCount = 1)
        n = ring.copyNewSince(cursor, out)
        assertEquals(40, n)
        for (i in 0 until 40) assertEquals(2f, out[i], 1e-6f)
    }

    @Test
    fun `copyNewSince clamps to newest window when behind`() {
        val ring = PcmRingBuffer(capacity = 128)
        val out = FloatArray(64)
        repeat(10) { round ->
            ring.writeInterleaved(FloatArray(50) { round.toFloat() }, frameCount = 50, channelCount = 1)
        }
        val n = ring.copyNewSince(0L, out)
        assertEquals(64, n)
        assertEquals(9f, out[63], 1e-6f)
    }
}
