package dev.musicviz.engine.audioandroid

import androidx.media3.common.C
import dev.musicviz.engine.audio.AudioPresentationClock
import dev.musicviz.engine.audio.InputPosition
import dev.musicviz.engine.audio.PresentationTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The driver, exercised through the exact call orders `DefaultAudioSink`
 * produces.
 *
 * Every sequence below mirrors one traced through the media3 1.10.0 bytecode:
 * a parameter change raises the two chain hooks and then flushes the pipeline,
 * a seek flushes with no hooks at all, and a reconfiguration drains to end of
 * stream first — flushing the tap once with nothing captured before the
 * hooked flush that follows. That last one is why the counter reads are
 * conditional, and it is not a case anyone would invent from the API surface.
 */
class SinkClockDriverTest {
    private val rate = 48_000

    private fun format(
        generation: Int,
        rateHz: Int = rate,
        channels: Int = 2,
        encoding: Int = C.ENCODING_PCM_16BIT,
    ) = PcmTapFormat(rateHz, channels, encoding, generation)

    private class Skips(
        var frames: Long = 0L,
    ) : SkippedFrameSource {
        var reads = 0

        override fun skippedInputFramesSinceFlush(): Long {
            reads++
            return frames
        }
    }

    private class Rig {
        val clock = AudioPresentationClock()
        val driver = SinkClockDriver(clock)
        val skips = Skips()

        init {
            driver.attachSkippedFrames(skips)
        }

        /** A parameter application: both hooks, then the pipeline flush. */
        fun parameterChange(
            speed: Float,
            ended: PcmTapFormat?,
            endedFrames: Long,
            begun: PcmTapFormat,
            skipSilence: Boolean = false,
        ) {
            driver.onSpeedApplied(speed)
            driver.onSkipSilenceApplied(skipSilence)
            driver.onTapBoundary(ended, endedFrames, begun)
        }

        /** A seek or route rebuild: `DefaultAudioSink.flush`, no hooks. */
        fun unhookedFlush(
            ended: PcmTapFormat?,
            endedFrames: Long,
            begun: PcmTapFormat,
        ) {
            driver.onTapBoundary(ended, endedFrames, begun)
        }
    }

    /** A rig already past the first parameter application, so speed is authoritative. */
    private fun started(speed: Float = 1f): Rig = Rig().apply { parameterChange(speed, null, 0, format(1)) }

    @Test
    fun `the first parameter application opens the timeline at zero`() {
        val rig = started()
        assertEquals(1, rig.clock.current.segments.size)
        assertEquals(PresentationTime.At(0), rig.clock.current.presentationTimeOf(0, epoch = 1))
        assertEquals(PresentationTime.At(1_000_000), rig.clock.current.presentationTimeOf(rate.toLong(), epoch = 1))
    }

    @Test
    fun `the next generation starts where the last one finished playing`() {
        val rig = started()
        // Two seconds captured at 1x, then a seek.
        rig.unhookedFlush(format(1), endedFrames = 2L * rate, begun = format(2))
        assertEquals(PresentationTime.At(2_000_000), rig.clock.current.presentationTimeOf(0, epoch = 2))
    }

    @Test
    fun `a speed change scales only what follows it`() {
        val rig = started(speed = 1f)
        rig.parameterChange(2f, ended = format(1), endedFrames = rate.toLong(), begun = format(2))
        // One second was heard at 1x, so the new generation starts at 1 s...
        assertEquals(PresentationTime.At(1_000_000), rig.clock.current.presentationTimeOf(0, epoch = 2))
        // ...and its own second of audio is heard in half a second.
        assertEquals(PresentationTime.At(1_500_000), rig.clock.current.presentationTimeOf(rate.toLong(), epoch = 2))
    }

    @Test
    fun `skipped silence shortens the anchor rather than being placed in a segment`() {
        // The driver knows HOW MANY frames were removed, never WHERE. Placing
        // them at the head of the next segment would claim a span was skipped
        // that was not; folding the total into the anchor is exact.
        val rig = started()
        rig.skips.frames = rate.toLong() / 2
        rig.unhookedFlush(format(1), endedFrames = 2L * rate, begun = format(2))
        assertEquals("2 s captured, 0.5 s of it silence", PresentationTime.At(1_500_000), rig.clock.current.presentationTimeOf(0, 2))
        assertEquals(0L, rig.clock.current.segments.last().skippedInputSamples)
    }

    @Test
    fun `a drain followed by a hooked flush reads the skip counter once`() {
        // The trap in the real call order: a reconfiguration drains to end of
        // stream (flushing the tap with everything captured so far) and THEN
        // applies parameters and flushes again with nothing captured. Reading
        // the counter at both would subtract the same silence twice; the
        // second read would also return a counter the silence-skipping stage
        // has already zeroed.
        val rig = started()
        rig.skips.frames = rate.toLong() / 2
        rig.unhookedFlush(format(1), endedFrames = 2L * rate, begun = format(2))
        assertEquals(1, rig.skips.reads)
        rig.parameterChange(2f, ended = format(2), endedFrames = 0, begun = format(3))
        assertEquals("the zero-frame boundary must not consume the counter again", 1, rig.skips.reads)
        assertEquals(PresentationTime.At(1_500_000), rig.clock.current.presentationTimeOf(0, 3))
    }

    @Test
    fun `a zero-frame boundary costs no time but still opens the generation`() {
        val rig = started()
        rig.parameterChange(1f, ended = format(1), endedFrames = 0, begun = format(2))
        assertEquals("the clock must have a segment for the generation being captured", 2, rig.clock.current.epoch)
        assertEquals(PresentationTime.At(0), rig.clock.current.presentationTimeOf(0, 2))
    }

    @Test
    fun `a boundary that proves speed is applied at the AudioTrack is refused`() {
        // media3 skips the chain's speed hook when the sink applies playback
        // parameters at the AudioTrack instead of at Sonic. The skip hook is
        // not skipped, so its arrival alone is the evidence: a slope built
        // from this driver's `speed` would be describing a stage that is not
        // doing the work.
        val rig = started()
        rig.driver.onSkipSilenceApplied(false)
        rig.driver.onTapBoundary(format(1), rate.toLong(), format(2))
        assertEquals("no segment may be invented for a speed the chain does not own", 1, rig.clock.current.segments.size)
        assertEquals(1L, rig.driver.diagnostics.refusedSpeedNotAuthoritative)
        assertEquals(PresentationTime.StaleEpoch(2, 1), rig.clock.current.presentationTimeOf(0, 2))
    }

    @Test
    fun `the speed verdict recovers when the chain regains authority`() {
        // The refusal is a verdict on the CURRENT configuration, not a
        // permanent one. Offload and tunneling end; a sink that stopped
        // telling the chain about speed can start again, and a driver that
        // latched "not authoritative" for good would leave the clock frozen
        // for the rest of the process with every counter looking healthy.
        val rig = started()
        rig.driver.onSkipSilenceApplied(false)
        rig.driver.onTapBoundary(format(1), rate.toLong(), format(2))
        assertEquals(1L, rig.driver.diagnostics.refusedSpeedNotAuthoritative)

        rig.parameterChange(1f, ended = format(2), endedFrames = rate.toLong(), begun = format(3))
        assertEquals("the driver never recovered", 2, rig.clock.current.segments.size)
        assertEquals(3, rig.clock.current.epoch)
    }

    @Test
    fun `a seek does not disturb the speed verdict`() {
        // A seek raises neither hook, which is indistinguishable from the
        // AudioTrack case if the verdict were recomputed from absence. It must
        // only be recomputed when the skip hook actually arrives.
        val rig = started(speed = 2f)
        repeat(3) { rig.unhookedFlush(format(it + 1), endedFrames = rate.toLong(), begun = format(it + 2)) }
        assertEquals(4, rig.clock.current.segments.size)
        assertEquals(0L, rig.driver.diagnostics.refusedSpeedNotAuthoritative)
        // A seek is an unhooked boundary. If that stopped being true the
        // detector above would be reading a different signal than it thinks.
        assertEquals("only the first, hooked, boundary carried media3's parameter hooks", 1L, rig.driver.diagnostics.hookedBoundaries)
        assertEquals(4L, rig.driver.diagnostics.boundaries)
    }

    @Test
    fun `nothing is modelled before the sink has ever applied parameters`() {
        val rig = Rig()
        rig.unhookedFlush(null, endedFrames = 0, begun = format(1))
        assertEquals(0, rig.clock.current.segments.size)
        assertEquals(PresentationTime.Unknown, rig.clock.current.presentationTimeOf(0, 1))
    }

    @Test
    fun `an unreadable format is refused rather than mapped at a guessed rate`() {
        val rig = started()
        rig.parameterChange(1f, ended = format(1), endedFrames = 0, begun = format(2, encoding = C.ENCODING_PCM_8BIT))
        rig.parameterChange(1f, ended = format(2), endedFrames = 0, begun = format(3, rateHz = 0))
        rig.parameterChange(1f, ended = format(3), endedFrames = 0, begun = format(4, channels = 0))
        assertEquals(3L, rig.driver.diagnostics.refusedUnreadableFormat)
        assertEquals(1, rig.clock.current.segments.size)
    }

    @Test
    fun `a skip count larger than the frames captured is discarded, not believed`() {
        // Arithmetically impossible, so it is a bad read rather than a large
        // skip. Clamping it to the frames captured would declare the whole
        // generation silent and stall the anchor; discarding it keeps the part
        // still trustworthy - that those frames were captured.
        val rig = started()
        rig.skips.frames = 10L * rate
        rig.unhookedFlush(format(1), endedFrames = rate.toLong(), begun = format(2))
        assertEquals(1L, rig.driver.diagnostics.clampedSkipExceedingFrames)
        assertEquals(PresentationTime.At(1_000_000), rig.clock.current.presentationTimeOf(0, 2))
        assertEquals(0L, rig.driver.diagnostics.refusedByClockInvariant)
    }

    @Test
    fun `the driver never builds a segment the clock rejects`() {
        // The one invariant with teeth: ClockSegment and append both throw,
        // and this runs inside AudioProcessor.flush where a throw stops the
        // music. The catch exists as a net; this asserts the net is dry.
        val rig = started()
        var generation = 1
        repeat(200) { i ->
            val ended = format(generation)
            generation++
            rig.skips.frames = (i % 7) * 1_000L
            if (i % 3 == 0) {
                rig.parameterChange(0.5f + (i % 8) * 0.5f, ended = ended, endedFrames = (i % 5) * 1_000L, begun = format(generation))
            } else {
                rig.unhookedFlush(ended, endedFrames = (i % 11) * 900L, begun = format(generation))
            }
        }
        val d = rig.driver.diagnostics
        assertEquals("the clock rejected a segment the driver built", 0L, d.refusedByClockInvariant)
        assertEquals(201L, d.boundaries)
        assertTrue("nothing was appended at all", d.segmentsAppended > 100)
    }

    @Test
    fun `the timeline round trips through both directions after a speed change`() {
        val rig = started(speed = 1f)
        rig.parameterChange(2f, ended = format(1), endedFrames = rate.toLong(), begun = format(2))
        val snapshot = rig.clock.current
        val at = snapshot.presentationTimeOf(24_000, epoch = 2) as PresentationTime.At
        assertEquals(InputPosition.At(24_000, 2), snapshot.inputPositionAt(at.us))
    }

    @Test
    fun `a boundary allocates nothing beyond the segment and the snapshot`() {
        // Diagnostics are plain counters materialised on read for exactly this
        // reason. The boundary runs on the playback thread; the segment and the
        // clock's new snapshot are unavoidable and bounded, a data class per
        // counter update would not be.
        val rig = started()
        // Hoisted: two PcmTapFormat objects per iteration would be the test's
        // own allocation, not the driver's. Reusing one generation is legal -
        // every segment restarts at input frame 0 within it.
        val ended = format(1)
        val begun = format(2)
        val budget = AllocationMeter.perRun(RUNS) { rig.driver.onTapBoundary(ended, 1_000L, begun) }
        assertTrue("a boundary allocated $budget bytes", budget < BOUNDARY_BUDGET_BYTES)
        val control = AllocationMeter.perRun(RUNS) { rig.skips.frames = FloatArray(8).size.toLong() }
        assertTrue("the meter reads $control bytes for a loop that allocates; it sees nothing", control > 8.0)
    }

    private companion object {
        const val RUNS = 20_000

        /**
         * One `ClockSegment`, one segment list and one `PresentationSnapshot`
         * per boundary: measured at **368 bytes** with the segment list at its
         * 64-entry cap. It was 944 until `append` stopped building the kept
         * list twice, which this test is what found.
         *
         * The budget leaves headroom but stays well under what a
         * `SinkClockDiagnostics.copy()` per counter would add - which is the
         * thing it exists to forbid.
         */
        const val BOUNDARY_BUDGET_BYTES = 600.0
    }
}
