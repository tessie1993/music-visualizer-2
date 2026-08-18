package dev.geode.analysis

import dev.geode.engine.audio.SampleRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.FloatBuffer
import kotlin.math.abs

/**
 * §5.7's parity gate, headless: the live path (the ring, `AnalysisEngine.Pass`)
 * and the offline path (`OfflineAnalyzer.StreamingPipeline`) over the same
 * corpus bytes. The two run the SAME `ReactiveAnalyzer`, so this is not a
 * numeric-identity test — their hop cadences are documented as 62.5 and
 * 60 Hz — it is the drift alarm: scalar curves within tolerance at matched
 * times, beats agreeing in count, and the channels export used to LOSE
 * (chroma, stereo) now present and agreeing.
 */
class LiveOfflineParityTest {
    private class LiveRun(
        val timesMs: MutableList<Long> = mutableListOf(),
        val frames: MutableList<AudioFeatures> = mutableListOf(),
    )

    /** Drives the live path at its own cadence over one fixture. */
    private fun live(fixture: Corpus.Fixture): LiveRun {
        val ring = SampleRing(capacityFrames = 1 shl 16, channelCount = 2)
        val engine = AnalysisEngine(ring)
        engine.sampleRateHz = fixture.sampleRateHz
        val pass = engine.Pass()
        val run = LiveRun()
        val hop = (fixture.sampleRateHz / AnalysisEngine.HOP_RATE_HZ).toInt()
        val interleaved = fixture.interleaved
        val channels = fixture.channels
        val chunk = FloatArray(hop * channels)
        var frame = 0
        while (frame + hop <= fixture.frames) {
            System.arraycopy(interleaved, frame * channels, chunk, 0, hop * channels)
            ring.write(chunk, hop, channels)
            frame += hop
            if (pass.tick()) {
                run.timesMs += frame * 1000L / fixture.sampleRateHz
                run.frames += engine.features.value
            }
        }
        return run
    }

    private fun offline(fixture: Corpus.Fixture): FeatureTimeline {
        val pipeline =
            OfflineAnalyzer.StreamingPipeline(
                BeatTuning.SENSITIVITY_DEFAULT,
                BeatTuning.INTERVAL_MS_DEFAULT,
            )
        pipeline.feedFloat(FloatBuffer.wrap(fixture.interleaved), fixture.channels, fixture.sampleRateHz)
        return pipeline.finish()
    }

    private fun nearest(
        run: LiveRun,
        timeMs: Long,
    ): AudioFeatures {
        var best = 0
        var bestDistance = Long.MAX_VALUE
        for (i in run.timesMs.indices) {
            val d = abs(run.timesMs[i] - timeMs)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return run.frames[best]
    }

    @Test
    fun `the scalar curves agree at matched times`() {
        // Smooth fixtures only, and deliberately: on impulse material the two
        // cadences put the SAME click into DIFFERENT windows, so a pointwise
        // comparison measures the 8 ms skew, not the graph. The click track's
        // parity claim is the beat-count test below.
        for (name in listOf("am_4hz", "sweep")) {
            val fixture = Corpus.named(name)
            val liveRun = live(fixture)
            val timeline = offline(fixture)
            assertTrue("$name produced no live frames", liveRun.frames.size > 30)
            var compared = 0
            for (frame in timeline.frames) {
                // Both graphs learn their adaptive range in the first moments;
                // parity is a claim about the settled state.
                if (frame.timeMs < 700) continue
                val want = frame.features
                val got = nearest(liveRun, frame.timeMs)
                assertEquals("$name rms @${frame.timeMs}", want.rms, got.rms, 0.25f)
                assertEquals("$name bass @${frame.timeMs}", want.bass, got.bass, 0.35f)
                assertEquals("$name mid @${frame.timeMs}", want.mid, got.mid, 0.35f)
                assertEquals("$name treble @${frame.timeMs}", want.treble, got.treble, 0.35f)
                compared++
            }
            assertTrue("$name compared only $compared frames", compared > 10)
        }
    }

    @Test
    fun `both paths hear the same beats on the click track`() {
        val fixture = Corpus.named("clicks_120bpm")
        val liveBeats = live(fixture).frames.count { it.beat }
        val offlineBeats = offline(fixture).frames.count { it.features.beat }
        assertTrue("live heard only $liveBeats beats", liveBeats >= 3)
        assertTrue("offline heard only $offlineBeats beats", offlineBeats >= 3)
        assertTrue(
            "beat counts drifted: live $liveBeats, offline $offlineBeats",
            abs(liveBeats - offlineBeats) <= 3,
        )
    }

    @Test
    fun `chroma survives export instead of arriving empty`() {
        // The defect this slice fixes: the offline pipeline never ran the
        // chromagram, so every exported video lost harmony reactivity.
        val timeline = offline(Corpus.named("am_4hz"))
        val settled = timeline.frames.last().features
        assertTrue("offline chroma still empty", settled.hasChroma)
        assertTrue("no harmonic confidence on a carrier tone: ${settled.chromaConfidence}", settled.chromaConfidence > 0.3f)
        val bins = settled.chroma
        val dominant = bins.indices.maxBy { bins[it] }
        assertEquals("a 440 Hz carrier is pitch class A", 9, dominant)
    }

    @Test
    fun `stereo width survives export and agrees with live`() {
        val fixture = Corpus.named("stereo_wide")
        val offlineLast = offline(fixture).frames.last().features
        val liveRun = live(fixture)
        val liveLast = liveRun.frames.last()
        assertTrue("offline width still zero: ${offlineLast.stereoWidth}", offlineLast.stereoWidth > 0.05f)
        assertEquals("width drifted", liveLast.stereoWidth, offlineLast.stereoWidth, 0.1f)
        assertEquals("correlation drifted", liveLast.stereoCorrelation, offlineLast.stereoCorrelation, 0.15f)
    }
}
