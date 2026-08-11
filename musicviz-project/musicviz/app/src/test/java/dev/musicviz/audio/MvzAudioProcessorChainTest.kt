package dev.musicviz.audio

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import dev.musicviz.audio.dsp.MvzAudioProcessorChain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The chain's contract, headless: pure ordering and delegation, no sink, no GL,
 * no device.
 *
 * The reason this is worth a test at all is that the two things it must get
 * right both fail silently. Wire a DSP stage above the tap and the visuals
 * quietly stop matching exports. Forget to forward
 * [MvzAudioProcessorChain.applyPlaybackParameters] and speed/pitch stop working
 * with no error anywhere - the chain interface has no "you missed one" signal.
 */
class MvzAudioProcessorChainTest {
    /** A stand-in stage; the chain only ever holds and orders these. */
    private class FakeStage : AudioProcessor {
        override fun configure(inputAudioFormat: AudioProcessor.AudioFormat) = inputAudioFormat

        override fun isActive() = false

        override fun queueInput(inputBuffer: ByteBuffer) = Unit

        override fun queueEndOfStream() = Unit

        override fun getOutput(): ByteBuffer = AudioProcessor.EMPTY_BUFFER

        override fun isEnded() = false

        override fun flush() = Unit

        override fun reset() = Unit
    }

    private val tap = FakeStage()

    @Test
    fun `with no dsp stages the chain is exactly the tap plus media3's own two`() {
        // The equivalence claim that makes adopting this class a no-op change:
        // same stages, same order as setAudioProcessors(arrayOf(tap)) produced.
        val processors = MvzAudioProcessorChain(tap).audioProcessors
        assertEquals(3, processors.size)
        assertSame("the tap must be first", tap, processors[0])
        assertTrue("expected silence skipping second", processors[1] is SilenceSkippingAudioProcessor)
        assertTrue("expected Sonic third", processors[2] is SonicAudioProcessor)
    }

    @Test
    fun `dsp stages land after media3's 16-bit-only stages`() {
        // The whole point of owning the chain. media3's DefaultAudioProcessorChain
        // appends Sonic and silence skipping AFTER the caller's array; both are
        // ENCODING_PCM_16BIT-only, so a float stage upstream of them fails at
        // configure() and playback dies at track start.
        val eq = FakeStage()
        val gain = FakeStage()
        val processors = MvzAudioProcessorChain(tap, listOf(eq, gain)).audioProcessors
        assertEquals(5, processors.size)
        assertSame(tap, processors[0])
        assertTrue(processors[1] is SilenceSkippingAudioProcessor)
        assertTrue(processors[2] is SonicAudioProcessor)
        assertSame("dsp stages keep the caller's order", eq, processors[3])
        assertSame(gain, processors[4])
    }

    @Test
    fun `the tap stays first however many dsp stages are added`() {
        // Analysis must never see processed audio: live features have to keep
        // matching the cached and exported ones for the same file.
        val stages = List(6) { FakeStage() }
        val processors = MvzAudioProcessorChain(tap, stages).audioProcessors
        assertSame(tap, processors.first())
        assertEquals(0, processors.indexOfFirst { it === tap })
    }

    @Test
    fun `playback parameters reach the sonic stage`() {
        // Not decoration: this is the only route by which speed and pitch reach
        // the processor that implements them.
        val chain = MvzAudioProcessorChain(tap)
        val params = PlaybackParameters(1.5f, 0.75f)
        assertEquals(params, chain.applyPlaybackParameters(params))
        val sonic = chain.audioProcessors.filterIsInstance<SonicAudioProcessor>().single()
        assertNotNull(sonic)
        // Sonic reports the media duration it would need for a given playout
        // duration; at 1.5x speed that is longer than the playout.
        assertTrue(
            "Sonic did not receive the speed - getMediaDuration is unscaled",
            chain.getMediaDuration(1_000_000L) != 1_000_000L,
        )
    }

    @Test
    fun `skip silence reaches the silence-skipping stage`() {
        val chain = MvzAudioProcessorChain(tap)
        assertTrue(chain.applySkipSilenceEnabled(true))
        assertEquals(0L, chain.skippedOutputFrameCount)
    }

    @Test
    fun `the chain reports the same stage array every call`() {
        // DefaultAudioSink reads getAudioProcessors() during configure and
        // rebuilds its pipeline from it; a chain that allocated a fresh array
        // with fresh stages each call would drop filter state on every format
        // change.
        val chain = MvzAudioProcessorChain(tap, listOf(FakeStage()))
        val first = chain.audioProcessors
        val second = chain.audioProcessors
        assertEquals(first.size, second.size)
        for (i in first.indices) assertSame("stage $i is not stable across calls", first[i], second[i])
    }
}
