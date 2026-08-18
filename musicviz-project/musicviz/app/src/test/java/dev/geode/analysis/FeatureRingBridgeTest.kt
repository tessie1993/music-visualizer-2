package dev.geode.analysis

import dev.geode.engine.audio.FeatureFrame
import dev.geode.engine.audio.FeatureRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The proof §5.6 demands before any consumer switches: the ring's span
 * semantics, fed through the bridge, are IDENTICAL to
 * [FeatureTimeline.featuresAt]'s peak-hold/OR — event for event, over the
 * same frames, at random times and spans. Plus the reason the ring exists
 * at all: a latest-wins reader loses beats; a span reader does not.
 */
class FeatureRingBridgeTest {
    private val sampleRate = 48_000
    private val hopMs = 17L

    private fun randomFeatures(random: Random): AudioFeatures =
        AudioFeatures(
            bands = FloatArray(0),
            waveform = FloatArray(0),
            rms = random.nextFloat(),
            bass = random.nextFloat(),
            mid = random.nextFloat(),
            treble = random.nextFloat(),
            centroid = random.nextFloat(),
            bpm = 60f + random.nextFloat() * 120f,
            beat = random.nextInt(8) == 0,
            onset = if (random.nextInt(4) == 0) random.nextFloat() else 0f,
            flux = random.nextFloat() * 0.3f,
            beatStrength = if (random.nextInt(8) == 0) random.nextFloat() else 0f,
            transient = if (random.nextInt(5) == 0) random.nextFloat() else 0f,
        )

    private fun build(count: Int): Triple<FeatureTimeline, FeatureRing, FeatureRingBridge> {
        val random = Random(42)
        val frames = (0 until count).map { TimelineFrame(it * hopMs, randomFeatures(random)) }
        val timeline = FeatureTimeline(frames, hopMs, hopRateHz = 1000f / hopMs)
        val ring = FeatureRingBridge.newRing(1024)
        val bridge = FeatureRingBridge()
        for (frame in frames) {
            bridge.publish(ring, frame.timeMs * sampleRate / 1000L, frame.features)
        }
        return Triple(timeline, ring, bridge)
    }

    @Test
    fun `the ring's span combine is the timeline's, event for event`() {
        val (timeline, ring, bridge) = build(400)
        val out = FeatureFrame(FeatureRingBridge.CONTINUOUS_SLOTS, FeatureRingBridge.EVENT_SLOTS)
        val random = Random(7)
        var compared = 0
        repeat(2_000) {
            val timeMs = random.nextLong(0L, 398 * hopMs)
            val spanMs = random.nextLong(0L, 6 * hopMs)
            val want = timeline.featuresAt(timeMs, spanMs)
            val result = ring.acquireAt(timeMs * sampleRate / 1000L, spanMs * sampleRate / 1000L, out)
            if (result != FeatureRing.Acquire.OK) return@repeat
            val got = bridge.snapshot(out)
            assertEquals("beat at t=$timeMs span=$spanMs", want.beat, got.beat)
            assertEquals("onset at t=$timeMs span=$spanMs", want.onset, got.onset, 0f)
            assertEquals("flux at t=$timeMs span=$spanMs", want.flux, got.flux, 0f)
            assertEquals("beatStrength at t=$timeMs span=$spanMs", want.beatStrength, got.beatStrength, 0f)
            assertEquals("transient at t=$timeMs span=$spanMs", want.transient, got.transient, 0f)
            compared++
        }
        assertTrue("only $compared comparisons ran", compared > 1_500)
    }

    @Test
    fun `a latest-wins reader loses the beat and a span reader does not`() {
        val ring = FeatureRingBridge.newRing()
        val bridge = FeatureRingBridge()
        val quiet = AudioFeatures.empty()
        val hopSamples = 768L
        // A single one-hop beat at hop 7, in a renderer polling every 3 hops.
        for (hop in 0 until 30) {
            val f = if (hop == 7) quiet.copy(beat = true, beatStrength = 0.9f) else quiet
            bridge.publish(ring, hop * hopSamples, f)
        }
        val out = FeatureFrame(FeatureRingBridge.CONTINUOUS_SLOTS, FeatureRingBridge.EVENT_SLOTS)
        var latestWinsSaw = false
        var spanSaw = 0
        var poll = 0L
        while (poll <= 27) {
            // Latest-wins: the value AT the poll instant only.
            ring.acquireAt(poll * hopSamples, 0L, out)
            if (out.events[FeatureRingBridge.EVENT_BEAT] > 0f) latestWinsSaw = true
            // Span: everything since the previous poll.
            ring.acquireAt(poll * hopSamples, 3 * hopSamples, out)
            if (out.events[FeatureRingBridge.EVENT_BEAT] > 0f) spanSaw++
            poll += 3
        }
        assertTrue("the latest-wins read somehow saw the beat; the demonstration is vacuous", !latestWinsSaw)
        assertEquals("the span reader must see the beat exactly once", 1, spanSaw)
    }

    @Test
    fun `the scalar view round-trips every slot`() {
        val random = Random(3)
        val features = randomFeatures(random).copy(stereoWidth = 0.4f, stereoCorrelation = -0.2f, stereoPan = 0.6f)
        val ring = FeatureRingBridge.newRing()
        val bridge = FeatureRingBridge()
        bridge.publish(ring, 1000L, features)
        val out = FeatureFrame(FeatureRingBridge.CONTINUOUS_SLOTS, FeatureRingBridge.EVENT_SLOTS)
        assertEquals(FeatureRing.Acquire.OK, ring.acquireAt(1000L, 0L, out))
        val got = bridge.snapshot(out)
        assertEquals(features.rms, got.rms, 0f)
        assertEquals(features.bpm, got.bpm, 0f)
        assertEquals(features.beat, got.beat)
        assertEquals(features.stereoWidth, got.stereoWidth, 0f)
        assertEquals(features.stereoCorrelation, got.stereoCorrelation, 0f)
        assertEquals(features.stereoPan, got.stereoPan, 0f)
        assertTrue("arrays are deliberately empty", !got.hasChroma && got.bands.isEmpty())
    }
}
