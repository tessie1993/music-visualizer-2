package dev.musicviz.analysis

import dev.musicviz.audio.PcmRingBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRingBufferTest {
    @Test
    fun `snapshot fails until enough data written`() {
        val ring = PcmRingBuffer(capacity = 1024)
        val out = FloatArray(256)
        assertFalse(ring.snapshotLatest(out))
        ring.writeInterleaved(FloatArray(512), frameCount = 256, channelCount = 2)
        assertTrue(ring.snapshotLatest(out))
    }

    @Test
    fun `stereo frames are downmixed to mono`() {
        val ring = PcmRingBuffer(capacity = 1024)
        val interleaved = floatArrayOf(1f, 0f, 0.5f, 0.5f, -1f, 1f, 0.25f, 0.75f)
        ring.writeInterleaved(interleaved, frameCount = 4, channelCount = 2)
        val out = FloatArray(4)
        assertTrue(ring.snapshotLatest(out))
        assertEquals(0.5f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
        assertEquals(0.5f, out[3], 1e-6f)
    }

    @Test
    fun `snapshot returns most recent window after wraparound`() {
        val ring = PcmRingBuffer(capacity = 256)
        repeat(10) { round ->
            val chunk = FloatArray(100) { round.toFloat() }
            ring.writeInterleaved(chunk, frameCount = 100, channelCount = 1)
        }
        val out = FloatArray(50)
        assertTrue(ring.snapshotLatest(out))
        for (v in out) assertEquals(9f, v, 1e-6f)
    }
}
