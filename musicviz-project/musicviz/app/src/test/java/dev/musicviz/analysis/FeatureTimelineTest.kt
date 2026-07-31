package dev.musicviz.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers timeline lookup/sectioning plus [FeatureTimeline.withBeatSensitivity].
 *
 * The latter guards a design bug: the analysis cache used to store the decided
 * beat flags, so the beat grid an export ran on was frozen at whatever
 * sensitivity the track happened to be analysed under (in practice always the
 * shipped defaults, since the offline analyzer never received the setting).
 * The timeline now carries the raw onset curve and re-decides on demand.
 */
class FeatureTimelineTest {
    private fun frame(
        timeMs: Long,
        level: Float,
        bandCount: Int = 64,
    ): TimelineFrame = TimelineFrame(timeMs, AudioFeatures(FloatArray(bandCount) { level }, FloatArray(128), rms = level))

    /** A slow, sparse track run through the real extractor, so the frames
     *  carry a real onset curve (kick every second, softer hits between). */
    private fun analyzed(
        sigma: Float,
        intervalMs: Float,
    ): FeatureTimeline {
        val extractor = FeatureExtractor(64, hopRateHz = 60f)
        extractor.beatThresholdSigma = sigma
        extractor.beatMinIntervalMs = intervalMs
        val waveform = FloatArray(128)
        val frames = ArrayList<TimelineFrame>(900)
        for (i in 0 until 900) {
            val kick = i % 60 == 0
            val minor = !kick && i % 15 == 0
            val bands =
                FloatArray(64) { b ->
                    when {
                        b >= 16 -> 0.05f
                        kick -> 0.65f
                        minor -> 0.40f
                        else -> 0.05f
                    }
                }
            frames += TimelineFrame(i * 1000L / 60L, extractor.extract(bands, waveform, 44100))
        }
        return FeatureTimeline(frames, hopMs = 16L, key = "A minor", hopRateHz = 60f)
    }

    private fun beats(t: FeatureTimeline): List<Boolean> = t.frames.map { it.features.beat }

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
    fun `re-deciding at the analysed settings is a no-op`() {
        val t = analyzed(FeatureExtractor.SLOW_SIGMA, FeatureExtractor.SLOW_INTERVAL_MS)
        val same = t.withBeatSensitivity(FeatureExtractor.SLOW_SIGMA, FeatureExtractor.SLOW_INTERVAL_MS)
        assertTrue("expected some beats to compare", beats(t).any { it })
        assertEquals(beats(t), beats(same))
    }

    @Test
    fun `re-deciding at a stricter setting drops beats without re-analysis`() {
        val t = analyzed(FeatureExtractor.SIGMA_DEFAULT, FeatureExtractor.INTERVAL_MS_DEFAULT)
        val strict = t.withBeatSensitivity(FeatureExtractor.SLOW_SIGMA, FeatureExtractor.SLOW_INTERVAL_MS)
        val before = beats(t).count { it }
        val after = beats(strict).count { it }
        assertTrue("stricter settings should flash less, got $after vs $before", after < before)
        assertTrue("but should keep the real kicks, got $after", after > 0)
        // Everything else is untouched - only the beat flags are re-decided.
        assertEquals(t.frames.size, strict.frames.size)
        assertEquals(t.hopMs, strict.hopMs)
        assertEquals(t.key, strict.key)
        assertEquals(t.bpm, strict.bpm, 1e-6f)
        for (i in t.frames.indices) {
            assertEquals(t.frames[i].timeMs, strict.frames[i].timeMs)
            assertEquals(t.frames[i].features.flux, strict.frames[i].features.flux, 0f)
            assertEquals(t.frames[i].features.rms, strict.frames[i].features.rms, 0f)
        }
        // The source timeline is immutable; a re-decide never mutates it.
        assertEquals(before, beats(t).count { it })
    }

    @Test
    fun `a timeline with no onset curve keeps its beats`() {
        // Synthesised (and pre-v2) timelines have flux = 0 everywhere;
        // re-deciding from zeros would silently erase every beat.
        val frames =
            (0 until 10).map {
                TimelineFrame(it * 16L, AudioFeatures(FloatArray(64), FloatArray(128), beat = it % 3 == 0))
            }
        val t = FeatureTimeline(frames, hopMs = 16)
        val same = t.withBeatSensitivity(FeatureExtractor.SIGMA_MAX, FeatureExtractor.INTERVAL_MS_MAX)
        assertEquals(beats(t), beats(same))
        assertEquals(4, beats(same).count { it })
    }

    @Test
    fun `suggester maps characteristics to scenes`() {
        assertEquals(SceneSuggester.SCENE_BURSTS, SceneSuggester.suggest(bpm = 140f, energy = 0.4f, centroid = 0.3f))
        assertEquals(SceneSuggester.SCENE_NEBULA, SceneSuggester.suggest(bpm = 80f, energy = 0.05f, centroid = 0.2f))
        assertEquals(SceneSuggester.SCENE_TUNNEL, SceneSuggester.suggest(bpm = 100f, energy = 0.3f, centroid = 0.6f))
        assertEquals(SceneSuggester.SCENE_JULIA, SceneSuggester.suggest(bpm = 100f, energy = 0.2f, centroid = 0.3f))
    }
}
