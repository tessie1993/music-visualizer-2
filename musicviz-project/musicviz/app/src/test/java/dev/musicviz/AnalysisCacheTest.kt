package dev.musicviz

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.analysis.AnalysisCache
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.analysis.FeatureTimeline
import dev.musicviz.analysis.TimelineFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalysisCacheTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun timeline(): FeatureTimeline {
        val frames =
            (0 until 5).map { i ->
                TimelineFrame(
                    timeMs = i * 17L,
                    features =
                        AudioFeatures(
                            bands = FloatArray(8) { (it + i) / 10f },
                            waveform = FloatArray(16) { kotlin.math.sin(it * 0.3f + i) * 0.8f },
                            rms = 0.4f + i * 0.01f,
                            bass = 0.5f,
                            mid = 0.3f,
                            treble = 0.1f,
                            onset = 0.2f,
                            beat = i % 2 == 0,
                            bpm = 124f,
                            centroid = 1800f,
                        ),
                )
            }
        return FeatureTimeline(frames, hopMs = 17L, key = "A minor")
    }

    @Test
    fun roundtripPreservesFramesKeyAndBpm() {
        val uri = Uri.parse("content://media/audio/1234")
        AnalysisCache.clear(ctx)
        assertNull(AnalysisCache.load(ctx, uri))
        val t = timeline()
        AnalysisCache.save(ctx, uri, t)
        val back = AnalysisCache.load(ctx, uri)!!
        assertEquals(t.frames.size, back.frames.size)
        assertEquals(t.hopMs, back.hopMs)
        assertEquals("A minor", back.key)
        assertEquals(124f, back.bpm, 0.01f)
        for (i in t.frames.indices) {
            val a = t.frames[i].features
            val b = back.frames[i].features
            assertEquals(t.frames[i].timeMs, back.frames[i].timeMs)
            assertEquals(a.beat, b.beat)
            assertEquals(a.rms, b.rms, 1e-4f)
            for (j in a.bands.indices) assertEquals(a.bands[j], b.bands[j], 1f / 4096f)
            for (j in a.waveform.indices) assertEquals(a.waveform[j], b.waveform[j], 1f / 8192f)
        }
    }

    @Test
    fun evictionCapsEntryCount() {
        AnalysisCache.clear(ctx)
        val t = timeline()
        for (i in 0 until 20) {
            AnalysisCache.save(ctx, Uri.parse("content://media/audio/$i"), t)
        }
        assertTrue("count=${AnalysisCache.entryCount(ctx)}", AnalysisCache.entryCount(ctx) <= 15)
    }
}
