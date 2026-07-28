package dev.musicviz

import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.fluid.FluidEmitters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** F2 emitter scheduler tests per FLUID_SIM v2 section 15 (headless). */
class FluidEmittersTest {
    private fun features(
        beat: Boolean = false,
        bass: Float = 0.4f,
        mid: Float = 0.3f,
        treble: Float = 0.05f,
        bands: FloatArray = FloatArray(16) { it / 16f },
    ) = AudioFeatures(
        bands = bands,
        waveform = FloatArray(64),
        rms = 0.4f,
        bass = bass,
        mid = mid,
        treble = treble,
        beat = beat,
    )

    @Test
    fun beatsProduceExactlyBeatSplatsExtraRequests() {
        val e =
            FluidEmitters(Random(7)).apply {
                stirrers = 2
                beatSplats = 3
                sparkle = false
            }
        val dt = 1f / 60f
        e.tick(features(), dt, 1.6f, 0.1f, 0.5f) // frame 1: stirrers have no prev yet
        val calm = e.tick(features(), dt, 1.6f, 0.1f, 0.5f).size
        val onBeat = e.tick(features(beat = true), dt, 1.6f, 0.1f, 0.5f).size
        assertEquals(2, calm) // the two stirrers
        assertEquals(calm + 3, onBeat)
        // Beat firing is EDGE-triggered: a sustained beat=true snapshot (the
        // analysis hop is slower than a high-refresh display) fires once,
        // not once per frame - three separated beats fire three times.
        val sustained = e.tick(features(beat = true), dt, 1.6f, 0.1f, 0.5f).size
        assertEquals(calm, sustained)
        var extra = 0
        repeat(3) {
            e.tick(features(), dt, 1.6f, 0.1f, 0.5f)
            extra += e.tick(features(beat = true), dt, 1.6f, 0.1f, 0.5f).size - calm
        }
        assertEquals(9, extra)
    }

    @Test
    fun stirrerPositionsAreContinuousFrameToFrame() {
        val e =
            FluidEmitters(Random(7)).apply {
                stirrers = 2
                sparkle = false
            }
        val dt = 1f / 60f
        e.tick(features(), dt, 1f, 0f, 0.5f)
        var prev = e.tick(features(), dt, 1f, 0f, 0.5f)
        repeat(30) {
            val cur = e.tick(features(), dt, 1f, 0f, 0.5f)
            for (i in cur.indices) {
                // Each frame's capsule starts where the previous one ended.
                assertEquals(prev[i].curX, cur[i].prevX, 1e-5f)
                assertEquals(prev[i].curY, cur[i].prevY, 1e-5f)
                val step = abs(cur[i].curX - cur[i].prevX) + abs(cur[i].curY - cur[i].prevY)
                assertTrue("stirrer jumped: $step", step < 0.12f)
            }
            prev = cur
        }
    }

    @Test
    fun spectrumArcOrdersSplatsLeftToRightByBandIndex() {
        val e =
            FluidEmitters(Random(7)).apply {
                stirrers = 0
                sparkle = false
                beatPattern = FluidEmitters.PATTERN_SPECTRUM_ARC
                beatSplats = 6
            }
        val dt = 1f / 60f
        val splats = e.tick(features(beat = true), dt, 1.6f, 0f, 0.5f)
        assertEquals(6, splats.size)
        for (i in 1 until splats.size) {
            assertTrue("arc not left->right", splats[i].curX > splats[i - 1].curX)
        }
        // All fire upward from the lower-band arc (the baseline may drift
        // with the journey anchors but stays below -0.3).
        splats.forEach {
            assertTrue(it.curY > it.prevY || it.velY > 0f)
            assertTrue(it.prevY < -0.3f)
        }
    }

    @Test
    fun beatEnvelopeAttacksOnBeatAndReleases() {
        val e =
            FluidEmitters(Random(7)).apply {
                stirrers = 0
                sparkle = false
                beatSplats = 0
            }
        val dt = 1f / 60f
        e.tick(features(beat = true), dt, 1f, 0f, 0.5f)
        assertEquals(1f, e.beatEnv, 1e-4f)
        repeat(30) { e.tick(features(), dt, 1f, 0f, 0.5f) }
        assertTrue("did not release: ${e.beatEnv}", e.beatEnv < 0.35f)
    }
}
