package dev.musicviz.audio

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The tap-first invariant, asserted against the array the factory builds.
 *
 * [AudioChainContractTest] states the rule and proves it by reading source
 * text, and MASTER_PLAN §12 schedules the tap's move to `:engine:audio-android`
 * — after which a text proof reads a file that no longer contains the stages it
 * is comparing. Worse, its ordering loop is `if (at >= 0)` over seven DSP stage
 * names, **none of which exist yet**, so the loop body never runs and the
 * ordering half asserts nothing today either.
 *
 * This asserts the same rule from the other end: build the chain, look at what
 * is in it, and prove the first processor is the visualizer's tap by feeding it
 * audio and watching the tap's sink receive it. Identity, not type — a second
 * `TeeAudioProcessor` wired to somewhere else would satisfy a type check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioChainOrderRuntimeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private val format = AudioProcessor.AudioFormat(44_100, 2, android.media.AudioFormat.ENCODING_PCM_16BIT)

    /** Records what the tap delivers, standing in for `PcmTapSink`. */
    private class RecordingSink : TeeAudioProcessor.AudioBufferSink {
        var bytes = 0
        var sampleRateHz = 0

        override fun flush(
            sampleRateHz: Int,
            channelCount: Int,
            encoding: Int,
        ) {
            this.sampleRateHz = sampleRateHz
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            bytes += buffer.remaining()
        }
    }

    private fun chainOf(sink: RecordingSink): Array<AudioProcessor> =
        TapRenderersFactory(context, sink).audioProcessorChain().audioProcessors

    @Test
    fun `the chain the factory builds is not empty`() {
        // The failure the text version could not see: an ordering rule over an
        // empty list is satisfied by every list. Everything below would pass
        // vacuously without this.
        val processors = chainOf(RecordingSink())
        assertTrue("a chain with no processors would satisfy any ordering claim", processors.size >= 3)
    }

    @Test
    fun `the first processor is the visualizer tap, by identity`() {
        val sink = RecordingSink()
        val first = chainOf(sink).first()
        assertTrue("first processor is ${first.javaClass.simpleName}, not the tap", first is TeeAudioProcessor)

        // Type is not enough: a TeeAudioProcessor feeding somewhere else would
        // pass. Push a buffer through and require OUR sink to be the one fed.
        first.configure(format)
        first.flush()
        val pcm = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder())
        repeat(32) { pcm.putShort(0) }
        pcm.flip()
        first.queueInput(pcm)

        assertEquals("the first processor does not feed this factory's sink", 64, sink.bytes)
        assertEquals(44_100, sink.sampleRateHz)
    }

    @Test
    fun `media3's own stages come after the tap, never before it`() {
        // Silence skipping and Sonic both alter what the audio IS - one drops
        // spans, the other resamples for speed and pitch. Analysis upstream of
        // them is what keeps live features matching the offline decode that
        // exports and the loudness bar are drawn from.
        val processors = chainOf(RecordingSink()).toList()
        val tapAt = processors.indexOfFirst { it is TeeAudioProcessor }
        val silenceAt = processors.indexOfFirst { it is SilenceSkippingAudioProcessor }
        val sonicAt = processors.indexOfFirst { it is SonicAudioProcessor }
        assertEquals("the tap must be first", 0, tapAt)
        assertTrue("silence skipping is missing from the chain", silenceAt > 0)
        assertTrue("Sonic is missing from the chain", sonicAt > 0)
        assertTrue("silence skipping is upstream of the tap", silenceAt > tapAt)
        assertTrue("Sonic is upstream of the tap", sonicAt > tapAt)
    }

    @Test
    fun `nothing else in the chain is a tap`() {
        // A second tap would make "which one feeds AudioFeatures" ambiguous,
        // and the answer would depend on construction order.
        val taps = chainOf(RecordingSink()).count { it is TeeAudioProcessor }
        assertEquals(1, taps)
    }

    @Test
    fun `the sink is built from the chain this test inspects`() {
        // The one thing a runtime look at the chain cannot see: that
        // buildAudioSink installs THIS chain rather than constructing another
        // one inline. One line of text, and it fails if the seam is bypassed.
        val source =
            java.io.File(
                dev.musicviz.ParamSurface.moduleRoot,
                "app/src/main/java/dev/musicviz/audio/TapRenderersFactory.kt",
            ).readText()
        assertTrue(
            "buildAudioSink no longer installs audioProcessorChain()",
            source.contains(".setAudioProcessorChain(audioProcessorChain())"),
        )
    }
}
