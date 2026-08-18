package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structural evidence, against constructed scenarios whose right answer
 * is known because the scenario IS the definition. These are experimental
 * channels: the tests pin the documented heuristics, and graduating them
 * past EXPERIMENTAL needs a labeled corpus of real arrangements.
 */
class StructureTrackerTest {
    private val hopRateHz = 62.5f
    private val bandCount = 16

    private fun tracker() = StructureTracker(bandCount, hopRateHz)

    private fun profile(shape: (Int) -> Float) = FloatArray(bandCount) { shape(it).coerceIn(0f, 1f) }

    private fun seconds(s: Float) = (s * hopRateHz).toInt()

    @Test
    fun `a changed band profile fires exactly one section boundary`() {
        val tracker = tracker()
        val bassy = profile { if (it < 4) 0.8f else 0.1f }
        val bright = profile { if (it >= 10) 0.8f else 0.1f }
        var sections = 0
        repeat(seconds(20f)) { tracker.step(bassy, rms = 0.5f, onset = 0.2f) }
        repeat(seconds(10f)) {
            tracker.step(bright, rms = 0.5f, onset = 0.2f)
            if (tracker.sectionBoundary) sections++
        }
        assertEquals("one profile change is one boundary", 1, sections)
    }

    @Test
    fun `steady material fires no section at all`() {
        val tracker = tracker()
        val steady = profile { 0.4f + 0.02f * (it % 3) }
        var sections = 0
        repeat(seconds(60f)) {
            tracker.step(steady, rms = 0.5f, onset = 0.2f)
            if (tracker.sectionBoundary) sections++
        }
        assertEquals(0, sections)
    }

    @Test
    fun `novelty rises on the change and settles after it`() {
        val tracker = tracker()
        val a = profile { if (it < 8) 0.7f else 0.1f }
        val b = profile { if (it >= 8) 0.7f else 0.1f }
        repeat(seconds(20f)) { tracker.step(a, 0.5f, 0.2f) }
        val before = tracker.novelty
        repeat(seconds(1f)) { tracker.step(b, 0.5f, 0.2f) }
        val during = tracker.novelty
        repeat(seconds(20f)) { tracker.step(b, 0.5f, 0.2f) }
        val after = tracker.novelty
        assertTrue("novelty did not rise: $before -> $during", during > before + 0.2f)
        assertTrue("novelty did not settle: $during -> $after", after < during - 0.2f)
    }

    @Test
    fun `a sustained ramp raises buildup and steady material does not`() {
        val ramping = tracker()
        val steady = tracker()
        val bands = profile { 0.4f }
        var peakBuildup = 0f
        val frames = seconds(8f)
        repeat(frames) { i ->
            val t = i / frames.toFloat()
            ramping.step(bands, rms = 0.15f + 0.75f * t, onset = 0.1f + 0.7f * t)
            peakBuildup = maxOf(peakBuildup, ramping.buildup)
            steady.step(bands, rms = 0.5f, onset = 0.3f)
        }
        assertTrue("ramp never read as buildup: $peakBuildup", peakBuildup > 0.5f)
        assertTrue("steady material read ${steady.buildup} buildup", steady.buildup < 0.2f)
    }

    @Test
    fun `a buildup into a dip into a slam is one drop`() {
        val tracker = tracker()
        val bands = profile { 0.4f }
        var drops = 0
        val ramp = seconds(8f)
        repeat(ramp) { i ->
            val t = i / ramp.toFloat()
            tracker.step(bands, rms = 0.15f + 0.75f * t, onset = 0.1f + 0.8f * t)
        }
        repeat(seconds(0.3f)) { tracker.step(bands, rms = 0.1f, onset = 0f) }
        repeat(seconds(2f)) {
            tracker.step(bands, rms = 0.95f, onset = 0.9f)
            if (tracker.drop) drops++
        }
        assertEquals("the slam after the dip is one drop", 1, drops)
    }

    @Test
    fun `a slam with no buildup before it is not a drop`() {
        val tracker = tracker()
        val bands = profile { 0.4f }
        var drops = 0
        repeat(seconds(10f)) { tracker.step(bands, rms = 0.3f, onset = 0.1f) }
        repeat(seconds(0.3f)) { tracker.step(bands, rms = 0.1f, onset = 0f) }
        repeat(seconds(2f)) {
            tracker.step(bands, rms = 0.95f, onset = 0.9f)
            if (tracker.drop) drops++
        }
        assertEquals("no buildup, no drop", 0, drops)
    }

    @Test
    fun `energy returning after a long quiet stretch is one arrival`() {
        val tracker = tracker()
        val bands = profile { 0.4f }
        var arrivals = 0
        repeat(seconds(10f)) { tracker.step(bands, rms = 0.6f, onset = 0.3f) }
        repeat(seconds(4f)) { tracker.step(bands, rms = 0.05f, onset = 0f) }
        repeat(seconds(2f)) {
            tracker.step(bands, rms = 0.6f, onset = 0.3f)
            if (tracker.arrival) arrivals++
        }
        assertEquals("one recovery is one arrival", 1, arrivals)
    }

    @Test
    fun `a short rest is not an arrival when the music resumes`() {
        val tracker = tracker()
        val bands = profile { 0.4f }
        var arrivals = 0
        repeat(seconds(10f)) { tracker.step(bands, rms = 0.6f, onset = 0.3f) }
        repeat(seconds(0.5f)) { tracker.step(bands, rms = 0.05f, onset = 0f) }
        repeat(seconds(2f)) {
            tracker.step(bands, rms = 0.6f, onset = 0.3f)
            if (tracker.arrival) arrivals++
        }
        assertEquals("a half-second rest is a rest", 0, arrivals)
    }

    @Test
    fun `outputs stay in range and reset forgets everything`() {
        val tracker = tracker()
        val a = profile { if (it % 2 == 0) 0.9f else 0.05f }
        repeat(seconds(30f)) { i ->
            tracker.step(a, rms = (i % 40) / 40f, onset = (i % 7) / 7f)
            assertTrue("novelty ${tracker.novelty}", tracker.novelty in 0f..1f)
            assertTrue("buildup ${tracker.buildup}", tracker.buildup in 0f..1f)
        }
        tracker.reset()
        assertEquals(0f, tracker.novelty, 0f)
        assertEquals(0f, tracker.buildup, 0f)
        assertTrue(!tracker.sectionBoundary)
        assertTrue(!tracker.drop)
        assertTrue(!tracker.arrival)
    }
}
