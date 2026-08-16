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
    /**
     * Rings here name their runway explicitly. The default is a quarter of
     * capacity, which at test sizes is a frame or two and would reject every
     * write below; production capacities make it thousands.
     */
    private fun ring(
        frames: Int = 16,
        channels: Int = 2,
        maxWrite: Int = frames / 2,
    ) = SampleRing(frames, channels, maxWrite)

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
        val r = ring(frames = 16, maxWrite = 6)
        val reader = RingReader(r)
        r.write(stereo(0, 6), 6, 2)
        reader.read(out(6))
        r.write(stereo(6, 6), 6, 2)
        reader.read(out(6))
        r.write(stereo(12, 6), 6, 2)
        val dst = out(6)
        // Frames 12..17 occupy slots 12,13,14,15,0,1 - the wrap is inside the
        // window, not at its edge.
        assertEquals(RingReadResult.Ok(12, 6, 0), reader.read(dst))
        assertEquals(listOf(12f, 13f, 14f, 15f, 16f, 17f), dst[0].toList())
    }

    @Test
    fun `the deepest safe read is a capacity less the writer's runway`() {
        // NOT a full capacity, which is what this test asserted until
        // `SampleRingConcurrencyTest` caught the ring handing back a lap-old
        // window as Ok. The oldest frame of a full-capacity window sits exactly
        // where the writer is about to store, and the writer publishes its
        // frame count only after storing - so a reader there cannot tell.
        val r = ring(frames = 16, maxWrite = 4)
        r.write(stereo(0, 4), 4, 2)
        r.write(stereo(4, 4), 4, 2)
        r.write(stereo(8, 4), 4, 2)
        val dst = out(12)
        assertEquals(RingReadResult.Ok(0, 12, 0), RingReader(r).read(dst))
        assertEquals(0f, dst[0].first(), 0f)
        assertEquals(11f, dst[0].last(), 0f)
    }

    @Test
    fun `one frame past the safe depth is a gap, not a short read`() {
        // The distinction PcmRingBuffer cannot make: it would return a count
        // and the caller could not tell it had lost the oldest frame.
        val r = ring(frames = 16, maxWrite = 4)
        repeat(4) { r.write(stereo(it * 4, 4), 4, 2) }
        assertEquals(RingReadResult.Gap(0, 4), RingReader(r).read(out(12)))
    }

    @Test
    fun `a gap leaves the cursor alone so the caller decides what to do`() {
        val r = ring(frames = 16, maxWrite = 4)
        val reader = RingReader(r)
        repeat(4) { r.write(stereo(it * 4, 4), 4, 2) }
        reader.read(out(12))
        assertEquals("the ring must not silently reposition a lagging reader", 0L, reader.nextSample)
        reader.skipToOldest()
        val dst = out(12)
        assertEquals(RingReadResult.Ok(4, 12, 0), reader.read(dst))
        assertEquals(4f, dst[0].first(), 0f)
    }

    @Test
    fun `a full buffer and a lost sample are different answers`() {
        // Both are "8" through copyNewSince. Here one is Ok and one is Gap.
        val full = ring(frames = 32, maxWrite = 12)
        full.write(stereo(0, 12), 12, 2)
        val okResult = RingReader(full).read(out(8))
        assertTrue("a caller-sized read is Ok with more still queued", okResult is RingReadResult.Ok)

        val lapped = ring(frames = 16, maxWrite = 12)
        lapped.write(stereo(0, 12), 12, 2)
        lapped.write(stereo(12, 12), 12, 2)
        assertTrue("a lapped reader is Gap", RingReader(lapped).read(out(8)) is RingReadResult.Gap)
    }

    @Test
    fun `the oldest trusted frame excludes the writer's runway`() {
        // The invariant the torn-read bug reduces to, stated where it cannot
        // depend on thread timing. `SampleRingConcurrencyTest` is what found
        // the hole, but it needs the reader to be preempted at exactly the
        // wrong moment and has not reproduced on demand since - so this, and
        // `one frame past the safe depth is a gap`, are what actually pin it.
        val r = ring(frames = 16, maxWrite = 4)
        assertEquals("nothing written yet", 0L, r.oldestAvailable)
        repeat(3) { r.write(stereo(it * 4, 4), 4, 2) }
        assertEquals("12 written, 4 of runway, 16 of ring", 0L, r.oldestAvailable)
        r.write(stereo(12, 4), 4, 2)
        assertEquals("the writer may already be storing into frame 16's slot", 4L, r.oldestAvailable)
        r.write(stereo(16, 4), 4, 2)
        assertEquals(8L, r.oldestAvailable)
    }

    @Test
    fun `a write longer than the runway readers reserve is refused`() {
        // Allowing it would make oldestAvailable a guess: the writer would
        // reach further into the ring than any reader believes is unsafe.
        val r = ring(frames = 16, maxWrite = 4)
        try {
            r.write(stereo(0, 5), 5, 2)
            error("expected a rejected write")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("maxWriteFrames"))
        }
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
        val r = ring(frames = 32, maxWrite = 8)
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
        val ring = SampleRing(8, 2, maxWriteFrames = 4)
        ring.write(FloatArray(8), 4, 2)
        try {
            RingReader(ring).read(arrayOf(FloatArray(4)))
            error("expected a rejected read")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("channels"))
        }
    }
}
