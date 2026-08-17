package dev.musicviz.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

class OnsetDetectorTest {
    private val hopRateHz = 86f

    private fun detector(
        minimum: Float = 0.5f,
        warmupSeconds: Float = OnsetDetector.WARMUP_SECONDS,
    ) = OnsetDetector(hopRateHz, minimum = minimum, warmupSeconds = warmupSeconds)

    /** Frames on which [detector] fired, over a whole stream of sounding frames. */
    private fun firings(
        node: OnsetDetector,
        stream: FloatArray,
    ): List<Int> {
        val hits = mutableListOf<Int>()
        for ((i, x) in stream.withIndex()) if (node.next(x, FrameActivity.Sounding)) hits += i
        return hits
    }

    @Test
    fun `a held excursion is one onset, not one per frame`() {
        // The excursion rule, which is the whole reason to prefer this to a
        // level test: a crash cymbal sits above the mean for a second, and a
        // level test would report it a hundred times.
        val node = detector()
        val strength = OnsetStrength(hopRateHz)
        val stream = FloatArray(1_000) { if (it in 400..500) strength.next(20f) else strength.next(0f) }
        val hits = firings(node, stream)
        assertEquals("the held excursion did not fire exactly once: $hits", 1, hits.size)
        // And it is timed where the rise ended, not where it began or released.
        val peak = hits.single() - OnsetDetector.PEAK_FRAMES_BACK
        assertTrue("peaked at $peak, outside the rise", peak in 400..(400 + 2 * strength.delayFrames))
    }

    @Test
    fun `two excursions separated by a return below the threshold are two onsets`() {
        val node = detector()
        val strength = OnsetStrength(hopRateHz)
        val stream =
            FloatArray(1_000) {
                strength.next(if (it in 400..430 || it in 600..630) 20f else 0f)
            }
        val hits = firings(node, stream)
        assertEquals("expected two onsets, got $hits", 2, hits.size)
        assertTrue("the first is not at the first burst", hits[0] - OnsetDetector.PEAK_FRAMES_BACK in 400..410)
        assertTrue("the second is not at the second burst", hits[1] - OnsetDetector.PEAK_FRAMES_BACK in 600..610)
    }

    @Test
    fun `nothing fires before the warmup is over`() {
        val node = detector(warmupSeconds = 0.5f)
        val warmupFrames = 43
        val stream = FloatArray(200) { 20f }
        val hits = firings(node, stream)
        assertTrue("fired during warmup: $hits", hits.all { it >= warmupFrames })
        // And warmup is reported rather than silently passed off as valid.
        val fresh = detector(warmupSeconds = 0.5f)
        fresh.next(20f, FrameActivity.Sounding)
        assertSame(FeatureValidity.Warmup, fresh.validity)
        repeat(200) { fresh.next(20f, FrameActivity.Sounding) }
        assertSame(FeatureValidity.Valid, fresh.validity)
    }

    @Test
    fun `the minimum keeps a flat quiet passage from firing on its own noise`() {
        // A sustained pad above the silence floor: the deviation collapses, so
        // a tenth of it is nothing, and without the raw minimum every ripple
        // would cross the flattened mean.
        val random = Random(11)
        val node = detector(minimum = 0.5f)
        val stream = FloatArray(20_000) { 3f + 1e-3f * random.nextFloat() }
        val hits = firings(node, stream)
        assertTrue("a flat passage produced ${hits.size} onsets", hits.size <= 1)

        // With no minimum worth the name, the same material fires constantly —
        // which is what the parameter is buying.
        val unguarded = detector(minimum = 1e-9f)
        val loose = firings(unguarded, FloatArray(20_000) { 3f + 1e-3f * Random(11).let { r -> r.nextFloat() } })
        assertTrue("the guard made no difference", loose.size >= hits.size)
    }

    @Test
    fun `the threshold follows the material rather than sitting still`() {
        // Adaptive, measured: the same absolute excursion is an onset against a
        // quiet phrase and not against a loud one.
        val random = Random(12)
        val quiet = detector(minimum = 0.01f)
        val loud = detector(minimum = 0.01f)
        repeat(2_000) {
            quiet.next(0.1f * random.nextFloat(), FrameActivity.Sounding)
            loud.next(10f * random.nextFloat(), FrameActivity.Sounding)
        }
        assertTrue(
            "the thresholds did not separate: ${quiet.threshold} vs ${loud.threshold}",
            loud.threshold > 10 * quiet.threshold,
        )
    }

    @Test
    fun `a silent frame neither fires nor trains`() {
        val random = Random(13)
        val node = detector()
        repeat(2_000) { node.next(5f * random.nextFloat(), FrameActivity.Sounding) }
        val mean = node.runningMean
        repeat(4_000) {
            assertFalse("a silent frame fired", node.next(50f, FrameActivity.Silent))
            assertSame(FeatureValidity.Silent, node.validity)
        }
        assertEquals("silence moved the mean", mean, node.runningMean, 0f)
    }

    @Test
    fun `and a rest re-arms the excursion, so the phrase after it gets its own onset`() {
        // Without this the excursion is still "open" across the rest and the
        // first hit of the next phrase is swallowed.
        val node = detector()
        repeat(500) { node.next(0f, FrameActivity.Sounding) }
        // Rise, then level off: the peak is the frame that failed to rise.
        node.next(10f, FrameActivity.Sounding)
        assertTrue("no onset at the top of the rise", node.next(10f, FrameActivity.Sounding))
        assertFalse("still inside the same excursion", node.next(10f, FrameActivity.Sounding))
        repeat(10) { node.next(20f, FrameActivity.Silent) }
        node.next(10f, FrameActivity.Sounding)
        assertTrue("the rest did not re-arm the excursion", node.next(10f, FrameActivity.Sounding))
    }

    @Test
    fun `the running statistics match a recomputation of the same window`() {
        // The incremental sliding update is the part most likely to be subtly
        // wrong, and it is not observable from the firing pattern alone.
        val random = Random(14)
        val window = (OnsetDetector.HISTORY_SECONDS * hopRateHz).toInt()
        val node = detector()
        val seen = ArrayDeque<Double>()
        repeat(20_000) {
            val x = if (random.nextInt(9) == 0) 400.0 * random.nextDouble() else random.nextDouble()
            node.next(x.toFloat(), FrameActivity.Sounding)
            seen.addLast(x)
            if (seen.size > window) seen.removeFirst()
        }
        val mean = seen.average()
        val deviation = sqrt(seen.sumOf { (it - mean) * (it - mean) } / seen.size)
        assertEquals("mean drifted", mean, node.runningMean.toDouble(), abs(mean) * 1e-4)
        assertEquals(
            "threshold drifted",
            maxOf(OnsetDetector.DEVIATIONS * deviation, 0.5),
            node.threshold.toDouble(),
            deviation * 1e-3,
        )
    }

    @Test
    fun `an onset lands on the sample the transient did, once the strength delay is taken off`() {
        // "Sample-aligned onset evidence", demonstrated rather than claimed:
        // the two stages compose to place an event on the audio sample that
        // caused it, with no empirical latency constant anywhere.
        val branch = AnalysisBranch.GENERAL
        val sampleRateHz = 48_000
        val grid = FrameGrid(branch)
        val frameRate = sampleRateHz.toFloat() / branch.hopFrames
        val strength = OnsetStrength(frameRate)
        val node = OnsetDetector(frameRate, minimum = 0.05f, warmupSeconds = 0.25f)

        val transientFrame = 400
        var firedAt = -1
        for (frame in 0 until 1_000) {
            val flux = if (frame == transientFrame) 30f else 0f
            if (node.next(strength.next(flux), FrameActivity.Sounding) && firedAt < 0) firedAt = frame
        }
        assertTrue("nothing fired", firedAt >= 0)

        val peakFrame = (firedAt - OnsetDetector.PEAK_FRAMES_BACK).toLong()
        val reportedSample = grid.centerSample(peakFrame) - strength.delayFrames.toLong() * branch.hopFrames
        assertEquals(
            "the onset was placed ${reportedSample - grid.centerSample(transientFrame.toLong())} samples off",
            grid.centerSample(transientFrame.toLong()),
            reportedSample,
        )
    }

    @Test
    fun `reset returns it to a fresh stream`() {
        val random = Random(15)
        val node = detector()
        repeat(5_000) { node.next(50f * random.nextFloat(), FrameActivity.Sounding) }
        node.reset()
        assertSame(FeatureValidity.Warmup, node.validity)
        assertEquals(0f, node.runningMean, 0f)
        assertFalse("the previous stream's excursion survived", node.next(1f, FrameActivity.Sounding))
    }

    @Test
    fun `one frame allocates nothing`() {
        val node = detector()
        var i = 0
        val perRun =
            JvmAllocationMeter.perRun(20_000) {
                node.next((i++ % 31).toFloat(), if (i % 11 == 0) FrameActivity.Silent else FrameActivity.Sounding)
            }
        assertEquals("next allocated $perRun bytes per frame", 0.0, perRun, 1.0)
    }

    @Test
    fun `a malformed detector is refused at construction`() {
        val bad =
            listOf(
                { OnsetDetector(0f, 1f) },
                { OnsetDetector(hopRateHz, minimum = 0f) },
                { OnsetDetector(hopRateHz, minimum = Float.NaN) },
                { OnsetDetector(hopRateHz, 1f, historySeconds = 0f) },
                { OnsetDetector(hopRateHz, 1f, deviations = 0f) },
                { OnsetDetector(hopRateHz, 1f, warmupSeconds = 0f) },
            )
        for (make in bad) {
            try {
                make()
                throw AssertionError("a malformed detector was accepted")
            } catch (expected: IllegalArgumentException) {
                assertTrue("the message says nothing useful", expected.message!!.isNotEmpty())
            }
        }
    }
}
