package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which beat is beat ONE.
 *
 * The beat grid says where beats are; a scene choreographing at the bar
 * scale (section swells, camera turns, palette walks) needs to know which
 * of them starts the bar. The tracker accumulates accent evidence per
 * position of an assumed 4/4 bar and follows the position that keeps
 * winning — the published downbeat intuition (the accented position
 * repeats), implemented over the grid this engine already trusts.
 */
class BarTrackerTest {
    private val periodFrames = 32

    /**
     * Drives [bars] bars of a 4/4 grid the way [BeatGrid] presents one: a
     * phase ramp wrapping once per beat, with the beat flag on the wrap
     * frame. [accentOf] grades each beat by its TRUE bar position 0..3;
     * [beatsAudible] false models a breakdown — the grid keeps turning but
     * no beat is supported.
     */
    private fun drive(
        tracker: BarTracker,
        bars: Int,
        locked: Boolean = true,
        beatsAudible: Boolean = true,
        accentOf: (Int) -> Float = { if (it == 0) 1f else 0.4f },
        observe: (frame: Int, beat: Boolean) -> Unit = { _, _ -> },
    ) {
        var phase = 0f
        repeat(bars * periodFrames * 4) { frame ->
            phase += 1f / periodFrames
            var beat = false
            if (phase >= 1f) {
                phase -= 1f
                beat = beatsAudible
            }
            val truePosition = (frame / periodFrames) % 4
            tracker.step(
                phase = phase,
                beat = beat,
                locked = locked,
                accent = if (beat) accentOf(truePosition) else 0f,
            )
            observe(frame, beat)
        }
    }

    @Test
    fun `the downbeat lands on the accented beat`() {
        val tracker = BarTracker()
        var downbeats = 0
        var offAccentDownbeats = 0
        drive(tracker, bars = 12) { frame, _ ->
            if (tracker.downbeat) {
                downbeats++
                if ((frame / periodFrames) % 4 != 0) offAccentDownbeats++
            }
        }
        assertTrue("only $downbeats downbeats fired", downbeats >= 8)
        assertEquals("downbeats fired off the accented position", 0, offAccentDownbeats)
    }

    @Test
    fun `an accent on beat three moves the downbeat there`() {
        val tracker = BarTracker()
        var downbeats = 0
        var offAccentDownbeats = 0
        drive(tracker, bars = 12, accentOf = { if (it == 2) 1f else 0.4f }) { frame, _ ->
            if (tracker.downbeat) {
                downbeats++
                if ((frame / periodFrames) % 4 != 2) offAccentDownbeats++
            }
        }
        assertTrue("only $downbeats downbeats fired", downbeats >= 8)
        assertEquals("downbeats fired off the accented position", 0, offAccentDownbeats)
    }

    @Test
    fun `bar phase ramps continuously across the bar`() {
        val tracker = BarTracker()
        drive(tracker, bars = 8)
        var last = -1f
        var wraps = 0
        drive(tracker, bars = 2) { _, _ ->
            val phase = tracker.barPhase
            assertTrue("bar phase out of range: $phase", phase in 0f..1f)
            if (last >= 0f && phase < last - 0.5f) {
                wraps++
            } else if (last >= 0f) {
                assertTrue("bar phase went backwards: $last -> $phase", phase >= last - 0.06f)
            }
            last = phase
        }
        assertEquals("a bar should wrap once per bar", 2, wraps)
    }

    @Test
    fun `uniform accents leave the confidence low and a clear accent raises it`() {
        val flat = BarTracker()
        drive(flat, bars = 12, accentOf = { 0.7f })
        val accented = BarTracker()
        drive(accented, bars = 12)
        assertTrue("flat bars read ${flat.confidence}", flat.confidence < 0.25f)
        assertTrue(
            "accented ${accented.confidence} should clear flat ${flat.confidence}",
            accented.confidence > flat.confidence + 0.25f,
        )
    }

    @Test
    fun `one loud syncopation does not steal an established downbeat`() {
        val tracker = BarTracker()
        drive(tracker, bars = 12)
        var stolen = false
        drive(tracker, bars = 1, accentOf = { if (it == 1) 1.5f else 0.2f })
        drive(tracker, bars = 2) { frame, _ ->
            if (tracker.downbeat && (frame / periodFrames) % 4 != 0) stolen = true
        }
        assertTrue("one bar of syncopation moved the downbeat", !stolen)
    }

    @Test
    fun `an unlocked grid reports no downbeat confidence`() {
        val tracker = BarTracker()
        drive(tracker, bars = 12, locked = false)
        assertEquals(0f, tracker.confidence, 1e-6f)
    }

    @Test
    fun `the bar keeps flowing when beats fall silent`() {
        val tracker = BarTracker()
        drive(tracker, bars = 8)
        var advanced = 0
        var lastBeatInBar = tracker.beatInBar
        drive(tracker, bars = 2, beatsAudible = false) { _, _ ->
            if (tracker.beatInBar != lastBeatInBar) advanced++
            lastBeatInBar = tracker.beatInBar
        }
        assertEquals("two silent bars should advance eight beat slots", 8, advanced)
    }

    @Test
    fun `reset forgets the learned bar`() {
        val tracker = BarTracker()
        drive(tracker, bars = 12)
        tracker.reset()
        assertEquals(0f, tracker.confidence, 0f)
        assertEquals(0f, tracker.barPhase, 0f)
        assertTrue(!tracker.downbeat)
    }
}
