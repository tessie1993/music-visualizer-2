package dev.musicviz.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureTimelineTest {
    private fun frame(
        timeMs: Long,
        level: Float,
        bandCount: Int = 64,
    ): TimelineFrame = TimelineFrame(timeMs, AudioFeatures(FloatArray(bandCount) { level }, FloatArray(128), rms = level))

    @Test
    fun `featuresAt returns nearest frame`() {
        val frames = (0 until 100).map { frame(it * 16L, it / 100f) }
        val timeline = FeatureTimeline(frames, hopMs = 16)
        assertEquals(frames[50].features.rms, timeline.featuresAt(50 * 16L).rms, 1e-6f)
        assertEquals(frames[99].features.rms, timeline.featuresAt(999_999L).rms, 1e-6f)
    }

    @Test
    fun `detects a section boundary at a spectral change`() {
        // 40s of quiet then 40s of loud at 60 fps.
        val frames = ArrayList<TimelineFrame>()
        for (i in 0 until 4800) {
            val level = if (i < 2400) 0.1f else 0.9f
            frames += frame(i * 16L, level)
        }
        val timeline = FeatureTimeline(frames, hopMs = 16)
        val sections = timeline.detectSections()
        assertTrue("expected at least one boundary", sections.isNotEmpty())
        val boundary = sections.first()
        val expected = 2400 * 16L
        assertTrue(
            "boundary $boundary should be near $expected",
            kotlin.math.abs(boundary - expected) < 3000,
        )
    }

    @Test
    fun `suggester maps characteristics to scenes`() {
        assertEquals(SceneSuggester.SCENE_BURSTS, SceneSuggester.suggest(bpm = 140f, energy = 0.4f, centroid = 0.3f))
        assertEquals(SceneSuggester.SCENE_NEBULA, SceneSuggester.suggest(bpm = 80f, energy = 0.05f, centroid = 0.2f))
        assertEquals(SceneSuggester.SCENE_TUNNEL, SceneSuggester.suggest(bpm = 100f, energy = 0.3f, centroid = 0.6f))
        assertEquals(SceneSuggester.SCENE_JULIA, SceneSuggester.suggest(bpm = 100f, energy = 0.2f, centroid = 0.3f))
    }
}
