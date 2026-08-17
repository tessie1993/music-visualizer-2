package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Tempo from a bank of comb-filter resonators driven by the onset envelope.
 *
 * `docs/quality/bar-visualizer.md` §2.4 lists "tempo tracking
 * (autocorrelation/comb-filter over the onset envelope) for phase-locked
 * pulses" as the bonus bar. A resonator bank is Scheirer's formulation
 * (*Tempo and beat analysis of acoustic musical signals*, JASA 1998): each
 * resonator rings when the onset train matches its period, so the bank reports
 * both which period the music is on and how clearly — and the winning
 * resonator's own output carries the phase, which an autocorrelation peak does
 * not.
 */
class TempoTrackerTest {
    private val hopRateHz = 100f

    private fun tracker() = TempoTracker(hopRateHz)

    /** Drives [frames] frames with an impulse every [periodFrames]. */
    private fun runTrain(
        tracker: TempoTracker,
        periodFrames: Int,
        frames: Int = 3000,
        offset: Int = 0,
    ) {
        repeat(frames) { i -> tracker.step(if ((i - offset) % periodFrames == 0 && i >= offset) 1f else 0f) }
    }

    @Test
    fun `a 120 BPM pulse train reads 120 BPM`() {
        val tracker = tracker()
        runTrain(tracker, periodFrames = 50) // 100 Hz / 50 frames = 2 Hz = 120 BPM
        assertEquals(120f, tracker.bpm, 4f)
    }

    @Test
    fun `a 90 BPM pulse train reads 90 BPM`() {
        val tracker = tracker()
        runTrain(tracker, periodFrames = 67) // ~89.6 BPM
        assertEquals(90f, tracker.bpm, 4f)
    }

    @Test
    fun `a 150 BPM pulse train reads 150 BPM`() {
        val tracker = tracker()
        runTrain(tracker, periodFrames = 40)
        assertEquals(150f, tracker.bpm, 4f)
    }

    /**
     * The octave trap. A train with a hit on every eighth note resonates at the
     * eighth-note period and at the quarter-note period equally well; the
     * tempo prior is what picks the one a listener would tap.
     */
    @Test
    fun `a subdivided train locks to the beat rather than the subdivision`() {
        val tracker = tracker()
        // Quarter notes at 100 BPM (60 frames) with a weaker off-beat eighth.
        repeat(4000) { i ->
            val onset =
                when {
                    i % 60 == 0 -> 1f
                    i % 30 == 0 -> 0.5f
                    else -> 0f
                }
            tracker.step(onset)
        }
        assertTrue("read ${tracker.bpm} BPM", tracker.bpm in 90f..110f)
    }

    @Test
    fun `a metronomic train is confident and noise is not`() {
        val steady = tracker()
        runTrain(steady, periodFrames = 50)

        val noisy = tracker()
        val random = Random(20260817)
        repeat(3000) { noisy.step(if (random.nextInt(20) == 0) random.nextFloat() else 0f) }

        assertTrue(
            "steady ${steady.confidence}, noisy ${noisy.confidence}",
            steady.confidence > noisy.confidence + 0.2f,
        )
        assertTrue("steady confidence ${steady.confidence}", steady.confidence > 0.4f)
    }

    @Test
    fun `silence reports no tempo and no confidence`() {
        val tracker = tracker()
        repeat(2000) { tracker.step(0f) }
        assertEquals(0f, tracker.confidence, 1e-6f)
        assertEquals(0f, tracker.bpm, 0f)
    }

    @Test
    fun `the reported period matches the reported bpm`() {
        val tracker = tracker()
        runTrain(tracker, periodFrames = 50)
        assertEquals(60f * hopRateHz / tracker.periodFrames, tracker.bpm, 0.5f)
    }

    @Test
    fun `a tempo change is followed`() {
        val tracker = tracker()
        runTrain(tracker, periodFrames = 50, frames = 3000)
        assertEquals(120f, tracker.bpm, 4f)
        // Twelve seconds at the new tempo is well past the resonator half-life.
        repeat(1200) { i -> tracker.step(if (i % 40 == 0) 1f else 0f) }
        assertEquals(150f, tracker.bpm, 5f)
    }

    @Test
    fun `reset returns the tracker to a fresh state`() {
        val tracker = tracker()
        runTrain(tracker, periodFrames = 40)
        tracker.reset()
        assertEquals(0f, tracker.bpm, 0f)
        assertEquals(0f, tracker.confidence, 0f)
        runTrain(tracker, periodFrames = 50)
        assertEquals(120f, tracker.bpm, 4f)
    }
}
