package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping's properties, not a handful of worked examples.
 *
 * MASTER_PLAN §5.2 names three: monotonic intervals, round trips, and gaps
 * surfaced rather than interpolated across. Each has a test below that sweeps
 * the space rather than picking a point in it, because a piecewise linear map
 * is exactly the kind of thing that is right at the sample you chose and wrong
 * either side of a seam.
 */
class AudioPresentationClockTest {
    private val rate = 48_000

    private fun clockAt(
        speed: Float,
        skipped: Long = 0L,
    ): AudioPresentationClock =
        AudioPresentationClock().apply {
            append(
                ClockSegment.fromFormat(
                    epoch = 0,
                    discontinuityGeneration = 0,
                    inputSampleStart = 0,
                    presentationUsStart = 0,
                    sampleRateHz = rate,
                    speed = speed,
                    skippedInputSamples = skipped,
                ),
            )
        }

    @Test
    fun `an input frame maps forward and back to itself`() {
        for (speed in listOf(0.5f, 0.75f, 1f, 1.5f, 2f, 4f)) {
            val snapshot = clockAt(speed).current
            for (sample in listOf(0L, 1L, 47L, 1_000L, 48_000L, 1_234_567L)) {
                val us = snapshot.presentationTimeOf(sample, epoch = 0)
                assertTrue("$sample at ${speed}x did not map", us is PresentationTime.At)
                val back = snapshot.inputPositionAt((us as PresentationTime.At).us)
                assertEquals(
                    "round trip lost $sample at ${speed}x",
                    InputPosition.At(sample, 0),
                    back,
                )
            }
        }
    }

    @Test
    fun `presentation time never runs backwards as input frames advance`() {
        val snapshot = clockAt(1.25f).current
        var previous = Long.MIN_VALUE
        for (sample in 0L until 20_000L step 37L) {
            val at = snapshot.presentationTimeOf(sample, epoch = 0) as PresentationTime.At
            assertTrue("presentation time went backwards at frame $sample", at.us >= previous)
            previous = at.us
        }
    }

    @Test
    fun `doubling the speed halves the presentation time a span occupies`() {
        // The property that makes `presentationTime = sampleTime + offset`
        // wrong: below the tap, Sonic consumes twice the input per second of
        // playback, so the same span of captured audio is heard in half the time.
        val oneSecond = rate.toLong()
        val normal = clockAt(1f).current.presentationTimeOf(oneSecond, 0) as PresentationTime.At
        val fast = clockAt(2f).current.presentationTimeOf(oneSecond, 0) as PresentationTime.At
        assertEquals(1_000_000L, normal.us)
        assertEquals(500_000L, fast.us)
    }

    @Test
    fun `a frame inside a skipped span is reported skipped, not interpolated`() {
        // The other half of why the naive mapping is wrong. Silence skipping
        // removes input from the timeline entirely; a mapping that interpolated
        // over the hole would name a moment at which different audio is playing.
        val snapshot = clockAt(speed = 1f, skipped = 4_800).current
        for (sample in 0L until 4_800L step 97L) {
            assertEquals(
                "frame $sample was inside the skipped span",
                PresentationTime.Skipped(0, 4_800),
                snapshot.presentationTimeOf(sample, 0),
            )
        }
        assertEquals(PresentationTime.At(0), snapshot.presentationTimeOf(4_800, 0))
        assertEquals(PresentationTime.At(1_000_000), snapshot.presentationTimeOf(4_800L + rate, 0))
    }

    @Test
    fun `a frame from an ended numbering is refused rather than mapped`() {
        val clock = clockAt(1f)
        clock.append(
            ClockSegment.fromFormat(
                epoch = 1,
                discontinuityGeneration = 1,
                inputSampleStart = 0,
                presentationUsStart = 10_000_000,
                sampleRateHz = rate,
                speed = 1f,
            ),
        )
        // The same index exists in both numberings and means different audio in
        // each. Answering it would be plausible and wrong, which is worse than
        // refusing.
        assertEquals(PresentationTime.StaleEpoch(0, 1), clock.current.presentationTimeOf(1_000, epoch = 0))
        assertEquals(PresentationTime.At(10_020_833), clock.current.presentationTimeOf(1_000, epoch = 1))
    }

    @Test
    fun `a presentation time inside the newest segment is answered from it`() {
        // Every segment before the newest also starts at or before this time,
        // so a reverse lookup that takes the first match rather than the last
        // answers from a span that finished playing minutes ago - with an
        // epoch and a frame index that both look entirely reasonable.
        val clock = clockAt(1f)
        clock.append(
            ClockSegment.fromFormat(
                epoch = 1,
                discontinuityGeneration = 1,
                inputSampleStart = 0,
                presentationUsStart = 1_000_000,
                sampleRateHz = rate,
                speed = 2f,
            ),
        )
        assertEquals(InputPosition.At(48_000, 1), clock.current.inputPositionAt(1_500_000))
        assertEquals(
            "a time inside the older segment still belongs to it",
            InputPosition.At(24_000, 0),
            clock.current.inputPositionAt(500_000),
        )
    }

    @Test
    fun `a time before anything recorded is unknown, not zero`() {
        assertEquals(PresentationTime.Unknown, AudioPresentationClock().current.presentationTimeOf(0, 0))
        assertEquals(InputPosition.Unknown, AudioPresentationClock().current.inputPositionAt(0))

        val late =
            AudioPresentationClock().apply {
                append(ClockSegment.fromFormat(0, 0, inputSampleStart = 500, presentationUsStart = 900, sampleRateHz = rate, speed = 1f))
            }
        assertEquals(PresentationTime.Unknown, late.current.presentationTimeOf(499, 0))
        assertEquals(InputPosition.Unknown, late.current.inputPositionAt(899))
    }

    @Test
    fun `a reader holding a snapshot is unaffected by what is appended after`() {
        // §5.2 asks the render thread to read an immutable snapshot. If append
        // mutated what a reader already holds, a frame could be drawn against
        // half of one timeline and half of another.
        val clock = clockAt(1f)
        val held = clock.current
        clock.append(ClockSegment.fromFormat(1, 1, 0, 5_000_000, rate, 2f))
        assertEquals(1, held.segments.size)
        assertEquals(2, clock.current.segments.size)
        assertNotEquals(held.epoch, clock.current.epoch)
    }

    @Test
    fun `a timeline that runs backwards is rejected at the seam`() {
        // Every one of these produces a mapping that still returns numbers, so
        // none of them would show up as a failure anywhere downstream.
        val clock = clockAt(1f).also { it.append(ClockSegment.fromFormat(0, 0, 1_000, 20_000, rate, 1f)) }

        assertThrows(IllegalArgumentException::class.java) {
            clock.append(ClockSegment.fromFormat(0, 0, 2_000, 19_999, rate, 1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            clock.append(ClockSegment.fromFormat(0, 0, 999, 30_000, rate, 1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            clock.append(ClockSegment.fromFormat(1, -1, 0, 30_000, rate, 1f))
        }
        // A new epoch may restart input numbering; that is what an epoch is for.
        clock.append(ClockSegment.fromFormat(1, 1, 0, 30_000, rate, 1f))
        assertEquals(3, clock.current.segments.size)
    }

    @Test
    fun `the oldest segments are dropped rather than growing without bound`() {
        val clock = AudioPresentationClock(maxSegments = 4)
        repeat(10) { i ->
            clock.append(ClockSegment.fromFormat(i, i, 0, i * 1_000L, rate, 1f))
        }
        assertEquals(4, clock.current.segments.size)
        assertEquals(6, clock.current.segments.first().epoch)
        assertEquals(9, clock.current.epoch)
    }

    @Test
    fun `the slope is derived from rate and speed, never supplied beside them`() {
        val segment = ClockSegment.fromFormat(0, 0, 0, 0, sampleRateHz = 44_100, speed = 1.5f)
        assertEquals(44_100 * 1.5 / 1_000_000.0, segment.inputSamplesPerPresentationUs, 1e-12)
        assertThrows(IllegalArgumentException::class.java) {
            ClockSegment.fromFormat(0, 0, 0, 0, sampleRateHz = 0, speed = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ClockSegment.fromFormat(0, 0, 0, 0, sampleRateHz = rate, speed = 0f)
        }
    }
}
