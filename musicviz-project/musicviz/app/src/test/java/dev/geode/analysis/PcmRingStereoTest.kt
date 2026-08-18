package dev.geode.analysis

import dev.geode.audio.PcmRingBuffer
import dev.geode.engine.audio.StereoField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The ring-buffer plumbing that produces the mid/side pair [StereoField]
 * measures. Split from `StereoFieldTest` when the measurements moved to
 * `audio-core`: these pin the RING's derivation, which lives here.
 */
class PcmRingStereoTest {
    private fun tone(
        hz: Float,
        phase: Float = 0f,
        amp: Float = 0.8f,
    ): (Int) -> Float = { i -> (amp * sin(2.0 * PI * hz * i / 48_000.0 + phase)).toFloat() }

    /**
     * The mono channel must be BYTE-IDENTICAL to what it was before the side
     * channel existed, because every downstream stage - FFT, bands, flux,
     * tempo - reads it and none of them were meant to change.
     */
    @Test
    fun `the mid channel is still the plain mono downmix`() {
        val ring = PcmRingBuffer(1 shl 12)
        val frames = 512
        val interleaved = FloatArray(frames * 2)
        for (i in 0 until frames) {
            interleaved[i * 2] = tone(440f)(i)
            interleaved[i * 2 + 1] = tone(660f)(i)
        }
        ring.writeInterleaved(interleaved, frames, 2)
        val mid = FloatArray(frames)
        assertTrue(ring.snapshotLatest(mid))
        for (i in 0 until frames) {
            assertEquals("frame $i", (interleaved[i * 2] + interleaved[i * 2 + 1]) / 2f, mid[i], 1e-6f)
        }
    }

    @Test
    fun `the ring buffer recovers left and right exactly`() {
        val ring = PcmRingBuffer(1 shl 12)
        val frames = 512
        val interleaved = FloatArray(frames * 2)
        for (i in 0 until frames) {
            interleaved[i * 2] = tone(440f)(i)
            interleaved[i * 2 + 1] = tone(660f, phase = 0.7f)(i)
        }
        ring.writeInterleaved(interleaved, frames, 2)
        val mid = FloatArray(frames)
        val side = FloatArray(frames)
        assertTrue(ring.snapshotLatest(mid))
        assertTrue(ring.snapshotLatestSide(side))
        for (i in 0 until frames) {
            assertEquals("L @$i", interleaved[i * 2], mid[i] + side[i], 1e-6f)
            assertEquals("R @$i", interleaved[i * 2 + 1], mid[i] - side[i], 1e-6f)
        }
    }

    @Test
    fun `a mono source has an all-zero side channel`() {
        val ring = PcmRingBuffer(1 shl 12)
        val frames = 256
        val interleaved = FloatArray(frames) { tone(440f)(it) }
        ring.writeInterleaved(interleaved, frames, 1)
        val side = FloatArray(frames)
        assertTrue(ring.snapshotLatestSide(side))
        for (i in 0 until frames) assertEquals("frame $i", 0f, side[i], 0f)
        val mid = FloatArray(frames)
        assertTrue(ring.snapshotLatest(mid))
        assertEquals(StereoField.MONO, StereoField.of(mid, side))
    }

    /**
     * Side comes from the front pair on a surround source, not from a fold of
     * every channel - the surrounds are not part of the image two speakers
     * will reproduce.
     */
    @Test
    fun `side uses the front pair of a surround source`() {
        val ring = PcmRingBuffer(1 shl 12)
        val channels = 6
        val frames = 128
        val interleaved = FloatArray(frames * channels)
        for (i in 0 until frames) {
            val base = i * channels
            interleaved[base] = 0.5f // L
            interleaved[base + 1] = -0.3f // R
            for (c in 2 until channels) interleaved[base + c] = 0.9f // surrounds
        }
        ring.writeInterleaved(interleaved, frames, channels)
        val side = FloatArray(frames)
        assertTrue(ring.snapshotLatestSide(side))
        for (i in 0 until frames) assertEquals("frame $i", (0.5f - -0.3f) / 2f, side[i], 1e-6f)
    }
}
