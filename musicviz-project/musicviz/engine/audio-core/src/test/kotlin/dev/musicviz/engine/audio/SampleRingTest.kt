package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cases MASTER_PLAN §5.1 names: wrap, exact capacity, gap, interleaved
 * stereo, epoch, seek and simultaneous readers.
 *
 * Each one is a thing `PcmRingBuffer` either cannot express or answers with a
 * number that two different situations share.
 */
class SampleRingTest {
    private fun ring(
        frames: Int = 8,
        channels: Int = 2,
    ) = SampleRing(frames, channels)

    private fun out(
        frames: Int,
        channels: Int = 2,
    ) = Array(channels) { FloatArray(frames) }

    /** [frameCount] stereo frames whose left channel counts up from [from]. */
    private fun stereo(
        from: Int,
        frameCount: Int,
    ) = FloatArray(frameCount * 2) { i -> if (i % 2 == 0) (from + i / 2).toFloat() else -(from + i / 2).toFloat() }

    @Test
    fun `a reader with nothing to read is told so, not given zeros`() {
        assertEquals(RingReadResult.NotYetAvailable, RingReader(ring()).read(out(4)))
    }

    @Test
    fun `both channels survive the write`() {
        val r = ring()
        r.write(stereo(0, 4), 4, 2)
        val dst = out(4)
        val result = RingReader(r).read(dst)
        assertEquals(RingReadResult.Ok(0, 4, 0), result)
        assertEquals(listOf(0f, 1f, 2f, 3f), dst[0].toList())
        assertEquals("side content is not folded away", listOf(-0f, -1f, -2f, -3f), dst[1].toList())
    }

    @Test
    fun `a mono source fills the second channel with silence, not with the first`() {
        val r = ring()
        r.write(floatArrayOf(1f, 2f, 3f), 3, 1)
        val dst = out(3)
        RingReader(r).read(dst)
        assertEquals(listOf(1f, 2f, 3f), dst[0].toList())
        assertEquals("a mono signal genuinely has no second channel", listOf(0f, 0f, 0f), dst[1].toList())
    }

    @Test
    fun `reading across the wrap point returns the samples, in order`() {
        val r = ring(frames = 8)
        val reader = RingReader(r)
        r.write(stereo(0, 6), 6, 2)
        reader.read(out(6))
        r.write(stereo(6, 6), 6, 2)
        val dst = out(6)
        assertEquals(RingReadResult.Ok(6, 6, 0), reader.read(dst))
        assertEquals(listOf(6f, 7f, 8f, 9f, 10f, 11f), dst[0].toList())
    }

    @Test
    fun `exactly one capacity of unread audio is still readable`() {
        val r = ring(frames = 8)
        r.write(stereo(0, 8), 8, 2)
        val dst = out(8)
        assertEquals(RingReadResult.Ok(0, 8, 0), RingReader(r).read(dst))
        assertEquals(0f, dst[0].first(), 0f)
        assertEquals(7f, dst[0].last(), 0f)
    }

    @Test
    fun `one frame past capacity is a gap, not a short read`() {
        // The distinction PcmRingBuffer cannot make: it would return a count
        // and the caller could not tell it had lost the oldest frame.
        val r = ring(frames = 8)
        r.write(stereo(0, 9), 9, 2)
        assertEquals(RingReadResult.Gap(0, 1), RingReader(r).read(out(8)))
    }

    @Test
    fun `a gap leaves the cursor alone so the caller decides what to do`() {
        val r = ring(frames = 8)
        val reader = RingReader(r)
        r.write(stereo(0, 9), 9, 2)
        reader.read(out(8))
        assertEquals("the ring must not silently reposition a lagging reader", 0L, reader.nextSample)
        reader.skipToOldest()
        val dst = out(8)
        assertEquals(RingReadResult.Ok(1, 8, 0), reader.read(dst))
        assertEquals(1f, dst[0].first(), 0f)
    }

    @Test
    fun `a full buffer and a lost sample are different answers`() {
        // Both are "8" through copyNewSince. Here one is Ok and one is Gap.
        val full = ring(frames = 16)
        full.write(stereo(0, 12), 12, 2)
        val okResult = RingReader(full).read(out(8))
        assertTrue("a caller-sized read is Ok with more still queued", okResult is RingReadResult.Ok)

        val lapped = ring(frames = 8)
        lapped.write(stereo(0, 12), 12, 2)
        assertTrue("a lapped reader is Gap", RingReader(lapped).read(out(8)) is RingReadResult.Gap)
    }

    @Test
    fun `a seek ends the numbering rather than continuing it`() {
        val r = ring()
        val reader = RingReader(r)
        r.write(stereo(0, 4), 4, 2)
        reader.read(out(4))
        r.beginEpoch()
        r.write(stereo(100, 4), 4, 2)
        // Without an epoch the cursor at frame 4 would read post-seek audio as
        // though it continued the pre-seek timeline.
        assertEquals(RingReadResult.Discontinuity(0, 1), reader.read(out(4)))
    }

    @Test
    fun `a reader recovers from a discontinuity by rejoining the new epoch`() {
        val r = ring()
        val reader = RingReader(r)
        r.write(stereo(0, 4), 4, 2)
        reader.read(out(4))
        r.beginEpoch()
        r.write(stereo(100, 4), 4, 2)
        reader.read(out(4))
        reader.rewindToEpoch()
        val dst = out(4)
        assertEquals(RingReadResult.Ok(0, 4, 1), reader.read(dst))
        assertEquals(100f, dst[0].first(), 0f)
    }

    @Test
    fun `two readers advance independently`() {
        // The property one shared lastCopyEndIndex cannot have: the analysis
        // worker and a second consumer each see the whole stream.
        val r = ring(frames = 16)
        val fast = RingReader(r)
        val slow = RingReader(r)
        r.write(stereo(0, 8), 8, 2)
        val fastOut = out(8)
        fast.read(fastOut)
        assertEquals(8L, fast.nextSample)
        assertEquals("one reader's progress is not the other's", 0L, slow.nextSample)

        r.write(stereo(8, 4), 4, 2)
        assertEquals(RingReadResult.Ok(8, 4, 0), fast.read(out(4)))
        val slowOut = out(12)
        assertEquals("the slow reader still gets the stream from its own position", RingReadResult.Ok(0, 12, 0), slow.read(slowOut))
        assertEquals(0f, slowOut[0].first(), 0f)
        assertEquals(11f, slowOut[0][11], 0f)
    }

    @Test
    fun `capacity must be a power of two and channels positive`() {
        listOf({ SampleRing(6, 2) }, { SampleRing(8, 0) }).forEach { build ->
            try {
                build()
                error("expected a rejected construction")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().isNotEmpty())
            }
        }
    }
}

/** Holes found by re-reading the implementation, not by a failing run. */
class SampleRingConcurrencyTest {
    @Test
    fun `a reader racing the writer never returns torn audio as Ok`() {
        // Self-describing data: frame i holds the value i. Any Ok(first,
        // count) must therefore satisfy out[k] == first + k, so a window
        // partly overwritten mid-copy cannot pass as ordinary audio.
        //
        // The single-threaded version of this test was vacuous - writing past
        // the reader trips the lag check at the top of read() and never
        // reaches the copy. Only a writer advancing *during* the copy gets
        // there, which needs a second thread.
        val ring = SampleRing(1024, 1)
        val reader = RingReader(ring)
        val frames = 200_000
        val writer =
            Thread {
                val chunk = FloatArray(64)
                var next = 0
                while (next < frames) {
                    for (i in chunk.indices) chunk[i] = (next + i).toFloat()
                    ring.write(chunk, chunk.size, 1)
                    next += chunk.size
                }
            }

        val torn = mutableListOf<String>()
        writer.start()
        val out = arrayOf(FloatArray(512))
        var reads = 0
        while (writer.isAlive || reader.nextSample < ring.writtenFrames) {
            when (val result = reader.read(out)) {
                is RingReadResult.Ok -> {
                    reads++
                    for (k in 0 until result.sampleCount) {
                        val want = (result.firstSample + k).toFloat()
                        if (out[0][k] != want) {
                            torn += "at ${result.firstSample}+$k expected $want got ${out[0][k]}"
                            break
                        }
                    }
                }
                is RingReadResult.Gap -> reader.skipToOldest()
                is RingReadResult.Discontinuity -> reader.rewindToEpoch()
                RingReadResult.NotYetAvailable -> Thread.yield()
            }
        }
        writer.join()
        assertTrue("the reader never actually read anything", reads > 0)
        assertEquals("torn windows returned as Ok", emptyList<String>(), torn)
    }

    @Test
    fun `a mismatched channel count is rejected rather than half-filled`() {
        val ring = SampleRing(8, 2)
        ring.write(FloatArray(8), 4, 2)
        try {
            RingReader(ring).read(arrayOf(FloatArray(4)))
            error("expected a rejected read")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("channels"))
        }
    }
}
