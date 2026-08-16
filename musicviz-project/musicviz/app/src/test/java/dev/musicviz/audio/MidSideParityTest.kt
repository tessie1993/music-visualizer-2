package dev.musicviz.audio

import androidx.media3.common.C
import dev.musicviz.engine.audio.MidSideWindow
import dev.musicviz.engine.audio.SampleRing
import dev.musicviz.engine.audioandroid.PcmTap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * The pair the analyzer reads, derived at capture by `PcmRingBuffer` and on
 * read by [MidSideWindow], compared bit for bit.
 *
 * This is the proof the reader migration rests on. `AnalysisEngine` turns these
 * two arrays into every band, beat, chroma and stereo measurement the app
 * draws, so "close enough" is not a standard that means anything here — a
 * difference of one ulp in the mono downmix is a different FFT, and a
 * different FFT is a different visual. Compared with a delta of exactly zero.
 */
class MidSideParityTest {
    private val window = 2048

    /** Feeds the same PCM through one tap into both rings, as PlaybackSession does. */
    private class Rig(
        channels: Int,
        encoding: Int,
    ) {
        val legacy = PcmRingBuffer()
        val v2 = SampleRing(capacityFrames = 1 shl 16, channelCount = 2)
        val tap =
            PcmTap({ samples, frames, sourceChannels ->
                legacy.writeInterleaved(samples, frames, sourceChannels)
                v2.write(samples, frames, sourceChannels)
            }).apply { flush(48_000, channels, encoding) }
    }

    private fun buffer(
        bytes: Int,
        fill: ByteBuffer.() -> Unit,
    ): ByteBuffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN).apply(fill).apply { flip() }

    private fun compare(
        channels: Int,
        encoding: Int = C.ENCODING_PCM_16BIT,
        pcm: () -> ByteBuffer,
    ) {
        val rig = Rig(channels, encoding)
        rig.tap.handleBuffer(pcm())

        val legacyMid = FloatArray(window)
        val legacySide = FloatArray(window)
        assertTrue(rig.legacy.snapshotLatest(legacyMid))
        assertTrue(rig.legacy.snapshotLatestSide(legacySide))

        val bridge = MidSideWindow(rig.v2, window)
        assertTrue("the V2 ring holds fewer frames than the window", bridge.refresh())

        assertArrayEquals("the mono downmix differs", legacyMid, bridge.mid, 0f)
        assertArrayEquals("the side channel differs", legacySide, bridge.side, 0f)
    }

    @Test
    fun `stereo 16-bit derives the identical pair`() {
        compare(channels = 2) {
            buffer(4096 * 2 * Short.SIZE_BYTES) {
                for (i in 0 until 4096) {
                    putShort((sin(i * 0.01) * Short.MAX_VALUE).toInt().toShort())
                    putShort((sin(i * 0.013 + 1.0) * Short.MIN_VALUE.toInt()).toInt().toShort())
                }
            }
        }
    }

    @Test
    fun `stereo float derives the identical pair`() {
        compare(channels = 2, encoding = C.ENCODING_PCM_FLOAT) {
            buffer(4096 * 2 * Float.SIZE_BYTES) {
                for (i in 0 until 4096) {
                    putFloat(sin(i * 0.02).toFloat())
                    putFloat(-sin(i * 0.017 + 0.5).toFloat())
                }
            }
        }
    }

    @Test
    fun `mono is not halved by the silent second channel`() {
        // The trap the source channel count exists for. A mono source fills
        // channel 0 and leaves channel 1 at zero, so a bridge averaging the
        // ring's two channels would return exactly half amplitude - with the
        // right shape, the right length and no error anywhere.
        compare(channels = 1) {
            buffer(4096 * Short.SIZE_BYTES) {
                for (i in 0 until 4096) putShort((sin(i * 0.01) * Short.MAX_VALUE).toInt().toShort())
            }
        }
    }

    @Test
    fun `a mono source reports no side content`() {
        val rig = Rig(1, C.ENCODING_PCM_16BIT)
        rig.tap.handleBuffer(buffer(4096 * Short.SIZE_BYTES) { repeat(4096) { putShort(12_345) } })
        val bridge = MidSideWindow(rig.v2, window)
        assertTrue(bridge.refresh())
        assertArrayEquals(FloatArray(window), bridge.side, 0f)
        assertEquals(12_345 / 32768f, bridge.mid[0], 0f)
    }

    @Test
    fun `a window wider than the ring is refused rather than wrapped`() {
        val rig = Rig(2, C.ENCODING_PCM_16BIT)
        val small = SampleRing(capacityFrames = 64, channelCount = 2, maxWriteFrames = 16)
        assertTrue("an over-wide window must not be answered", !MidSideWindow(small, 128).refresh())
        assertTrue("nothing written yet", !MidSideWindow(rig.v2, window).refresh())
    }

    @Test
    fun `five-channel audio is where the two disagree, and only there`() {
        // Documented divergence, asserted rather than discovered later. The
        // legacy downmix is the mean of all five channels; the V2 ring keeps
        // the front pair by design (section 5.1), so its mid is the mean of
        // two. Pinned here so the difference cannot widen unnoticed, and
        // recorded in adr/0003.
        val rig = Rig(5, C.ENCODING_PCM_16BIT)
        rig.tap.handleBuffer(
            buffer(4096 * 5 * Short.SIZE_BYTES) {
                repeat(4096 * 5) { putShort(((it % 4096) - 2048).toShort()) }
            },
        )
        val legacyMid = FloatArray(window)
        assertTrue(rig.legacy.snapshotLatest(legacyMid))
        val bridge = MidSideWindow(rig.v2, window)
        assertTrue(bridge.refresh())

        assertEquals("the ring kept the front pair", 2, rig.v2.channelCount)
        assertEquals("the source had five", 5, rig.v2.sourceChannelCount)
        var differs = 0
        for (i in 0 until window) if (legacyMid[i] != bridge.mid[i]) differs++
        assertTrue("the two agreed on surround audio, so this test proves nothing", differs > window / 2)
    }
}
