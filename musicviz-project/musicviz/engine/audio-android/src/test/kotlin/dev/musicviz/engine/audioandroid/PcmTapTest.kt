package dev.musicviz.engine.audioandroid

import androidx.media3.common.C
import dev.musicviz.engine.audio.PcmSink
import dev.musicviz.engine.audio.RingReadResult
import dev.musicviz.engine.audio.RingReader
import dev.musicviz.engine.audio.SampleRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The tap's two obligations: deliver exactly the samples that were captured,
 * in order, and allocate nothing doing it.
 */
class PcmTapTest {
    /** Everything the sink was handed, flattened. Allocates; not used in the allocation test. */
    private class Recorder : PcmSink {
        val frames = mutableListOf<Float>()
        var calls = 0

        override fun write(
            interleaved: FloatArray,
            frameCount: Int,
            sourceChannelCount: Int,
        ) {
            calls++
            for (i in 0 until frameCount * sourceChannelCount) frames += interleaved[i]
        }
    }

    private fun pcm16(
        values: ShortArray,
        order: ByteOrder = ByteOrder.LITTLE_ENDIAN,
    ): ByteBuffer =
        ByteBuffer.allocate(values.size * Short.SIZE_BYTES).order(order).apply {
            values.forEach { putShort(it) }
            flip()
        }

    private fun pcmFloat(values: FloatArray): ByteBuffer =
        ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            values.forEach { putFloat(it) }
            flip()
        }

    private fun tapInto(
        sink: PcmSink,
        channels: Int = 2,
        encoding: Int = C.ENCODING_PCM_16BIT,
    ): PcmTap = PcmTap(sink).apply { flush(48_000, channels, encoding) }

    @Test
    fun `16-bit samples arrive as floats in capture order`() {
        val recorder = Recorder()
        val values = shortArrayOf(0, 16384, -16384, Short.MIN_VALUE, Short.MAX_VALUE, 1)
        tapInto(recorder).handleBuffer(pcm16(values))
        assertEquals(values.map { it / 32768f }, recorder.frames)
    }

    @Test
    fun `float samples pass through unchanged`() {
        val recorder = Recorder()
        val values = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f, 0.125f)
        tapInto(recorder, encoding = C.ENCODING_PCM_FLOAT).handleBuffer(pcmFloat(values))
        assertEquals(values.toList(), recorder.frames)
    }

    @Test
    fun `a buffer longer than the staging window keeps its order`() {
        // The staging array is fixed so the callback never allocates, which
        // means a big buffer is delivered as several writes. Order across that
        // seam is the thing that would silently corrupt every feature.
        val recorder = Recorder()
        val values = ShortArray(20_000) { (it % 30_000).toShort() }
        tapInto(recorder).handleBuffer(pcm16(values))
        assertTrue("a 10000-frame buffer fitted one 4096-frame chunk", recorder.calls > 1)
        assertEquals(values.map { it / 32768f }, recorder.frames)
    }

    @Test
    fun `the wire's byte order wins over the buffer's`() {
        // The tap is first in the chain, so this is the decoder's own buffer
        // and its order is whatever the decoder left. Android PCM is
        // little-endian; reading it as big-endian turns 1 into 256.
        val recorder = Recorder()
        val values = shortArrayOf(1, 2, 3, 4)
        tapInto(recorder).handleBuffer(pcm16(values, ByteOrder.BIG_ENDIAN))
        assertEquals(values.map { java.lang.Short.reverseBytes(it) / 32768f }, recorder.frames)
    }

    @Test
    fun `a buffer is read from its position, not from zero`() {
        val recorder = Recorder()
        val buffer = pcm16(shortArrayOf(9, 9, 1, 2, 3, 4))
        buffer.position(2 * Short.SIZE_BYTES)
        tapInto(recorder).handleBuffer(buffer)
        assertEquals(listOf(1, 2, 3, 4).map { it / 32768f }, recorder.frames)
    }

    @Test
    fun `a trailing partial frame is left rather than delivered half-filled`() {
        val recorder = Recorder()
        tapInto(recorder).handleBuffer(pcm16(shortArrayOf(1, 2, 3)))
        assertEquals(listOf(1, 2).map { it / 32768f }, recorder.frames)
    }

    @Test
    fun `an unsupported encoding is dropped rather than misread`() {
        // 8-bit and 24-bit buffers would otherwise be read at the wrong stride
        // and arrive as noise, which looks like audio and is not.
        val recorder = Recorder()
        tapInto(recorder, encoding = C.ENCODING_PCM_8BIT).handleBuffer(pcm16(shortArrayOf(1, 2, 3, 4)))
        assertEquals(emptyList<Float>(), recorder.frames)
    }

    @Test
    fun `the sample width is resolved when the format is, not per buffer`() {
        // The callback branches on this enum; if it were still parsing the
        // media3 encoding constant per buffer, format adaptation would be
        // living on the audio thread.
        assertEquals(PcmSampleWidth.SIGNED_16, format(C.ENCODING_PCM_16BIT).sampleWidth)
        assertEquals(PcmSampleWidth.FLOAT_32, format(C.ENCODING_PCM_FLOAT).sampleWidth)
        assertNull(format(C.ENCODING_PCM_8BIT).sampleWidth)
        assertNull(format(C.ENCODING_PCM_24BIT).sampleWidth)
        assertEquals(Short.SIZE_BYTES, PcmSampleWidth.SIGNED_16.bytes)
        assertEquals(Float.SIZE_BYTES, PcmSampleWidth.FLOAT_32.bytes)
    }

    private fun format(encoding: Int) = PcmTapFormat(48_000, 2, encoding, 1)

    @Test
    fun `nothing is delivered before the first configuration`() {
        val recorder = Recorder()
        PcmTap(recorder).handleBuffer(pcm16(shortArrayOf(1, 2)))
        assertEquals(emptyList<Float>(), recorder.frames)
        assertNull(PcmTap(recorder).format)
    }

    @Test
    fun `each configuration is a new generation and restarts the frame count`() {
        // Media3 flushes the chain on seek as well as on format change, so a
        // generation bump is exactly §5.1's discontinuity: sample counts either
        // side of it belong to different spans.
        val recorder = Recorder()
        val tap = tapInto(recorder)
        tap.handleBuffer(pcm16(ShortArray(8)))
        assertEquals(4L, tap.framesWritten)
        assertEquals(PcmTapFormat(48_000, 2, C.ENCODING_PCM_16BIT, 1), tap.format)

        tap.flush(44_100, 1, C.ENCODING_PCM_FLOAT)
        assertEquals(PcmTapFormat(44_100, 1, C.ENCODING_PCM_FLOAT, 2), tap.format)
        assertEquals(0L, tap.framesWritten)
    }

    @Test
    fun `the format is published for a consumer that attaches late`() {
        val seen = mutableListOf<PcmTapFormat>()
        val tap = PcmTap(Recorder()) { seen += it }
        tap.flush(48_000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(seen, listOfNotNull(tap.format))
    }

    @Test
    fun `the callback allocates nothing`() {
        var delivered = 0L
        val counting = PcmSink { _, frameCount, _ -> delivered += frameCount }
        val tap = tapInto(counting)
        val buffer = pcm16(ShortArray(2048) { it.toShort() })

        val perCallback = AllocationMeter.perRun(RUNS) { tap.handleBuffer(buffer) }
        assertTrue("the tap allocated $perCallback bytes per callback", perCallback < BUDGET_BYTES)
        assertTrue("the sink was never called, so nothing was measured", delivered > 0)

        // Without this the assertion above is indistinguishable from a meter
        // that reports zero for everything. The old tap measured 120 bytes per
        // callback on this meter; one small array per run is less than that.
        val control = AllocationMeter.perRun(RUNS) { delivered += FloatArray(1).size }
        assertTrue("the meter reads $control bytes for a loop that allocates; it sees nothing", control > BUDGET_BYTES)
    }

    @Test
    fun `frames reach the sample ring in order and read back contiguous`() {
        // The point of the whole slice: the tap that feeds :app's legacy buffer
        // can feed V2's ring with no adapter, because both are a PcmSink.
        val ring = SampleRing(capacityFrames = 1024, channelCount = 2)
        val tap = tapInto(ring)
        val values = ShortArray(512) { it.toShort() }
        tap.handleBuffer(pcm16(values))

        val out = Array(2) { FloatArray(256) }
        val result = RingReader(ring).read(out)
        assertEquals(RingReadResult.Ok(0L, 256, 0), result)
        assertEquals(List(256) { (it * 2) / 32768f }, out[0].toList())
        assertEquals(List(256) { (it * 2 + 1) / 32768f }, out[1].toList())
    }

    private companion object {
        const val RUNS = 20_000

        /** Per callback. The empty-loop floor on this meter measures ~0.003 bytes. */
        const val BUDGET_BYTES = 8.0
    }
}
