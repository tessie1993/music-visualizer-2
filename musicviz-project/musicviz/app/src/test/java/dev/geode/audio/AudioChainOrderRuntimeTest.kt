package dev.geode.audio

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.test.core.app.ApplicationProvider
import dev.geode.engine.audioandroid.SinkClockHooks
import dev.geode.engine.audioandroid.SkippedFrameSource
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
    fun `silence skipping sits directly after the tap`() {
        // Not style: the presentation clock reads that stage's skipped-frame
        // counter and uses it as tap-domain input frames. That conversion is
        // the identity only because the tap is pass-through and the two are
        // adjacent. A resampling stage inserted between them would corrupt
        // every skip count with no other symptom, so the adjacency is pinned
        // here rather than left as a comment.
        val processors = chainOf(RecordingSink()).toList()
        assertEquals("the tap must be first", 0, processors.indexOfFirst { it is TeeAudioProcessor })
        assertEquals(
            "a stage between the tap and silence skipping breaks the skipped-frame unit",
            1,
            processors.indexOfFirst { it is SilenceSkippingAudioProcessor },
        )
    }

    @Test
    fun `the sink hooks the factory installs reach the object that was passed in`() {
        // The clock is driven from applyPlaybackParameters and
        // applySkipSilenceEnabled on this chain. If the factory built a chain
        // without the hooks, nothing would fail: the clock would simply stay
        // empty for the life of the process.
        val speeds = mutableListOf<Float>()
        val skips = mutableListOf<Boolean>()
        var attached: SkippedFrameSource? = null
        val hooks =
            object : SinkClockHooks {
                override fun onSpeedApplied(speed: Float) {
                    speeds += speed
                }

                override fun onSkipSilenceApplied(enabled: Boolean) {
                    skips += enabled
                }

                override fun attachSkippedFrames(source: SkippedFrameSource) {
                    attached = source
                }
            }
        val chain = TapRenderersFactory(context, RecordingSink(), hooks).audioProcessorChain()
        chain.applyPlaybackParameters(androidx.media3.common.PlaybackParameters(2f, 1f))
        chain.applySkipSilenceEnabled(true)

        assertEquals(listOf(2f), speeds)
        assertEquals(listOf(true), skips)
        assertEquals("the skipped-frame source must be the chain that owns the stage", chain, attached)
        assertEquals("no audio has been through it", 0L, attached?.skippedInputFramesSinceFlush())
    }

    @Test
    fun `the sink never asks the AudioTrack to apply playback parameters`() {
        // Media3 skips the chain's applyPlaybackParameters hook entirely when
        // the sink applies speed at the AudioTrack instead of at Sonic
        // (DefaultAudioSink.applyAudioProcessorPlaybackParametersAndSkipSilence
        // returns before the chain call when useAudioOutputPlaybackParams() is
        // true). The clock would then stop appending and never say why.
        //
        // The flag reaching buildAudioSink comes from a private field of
        // DefaultRenderersFactory that no test can read, so a source scan is
        // the only available form. Stated plainly rather than dressed as a
        // runtime assertion.
        val appSources =
            java.io.File(dev.geode.ParamSurface.moduleRoot, "app/src/main")
                .walkTopDown()
                .filter { it.extension == "kt" }
                .filter { it.readText().contains("setEnableAudioTrackPlaybackParams(") }
                .map { it.name }
                .toList()
        assertEquals(
            "enabling AudioTrack playback parameters silently stops the presentation clock",
            emptyList<String>(),
            appSources,
        )
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
                dev.geode.ParamSurface.moduleRoot,
                "app/src/main/java/dev/geode/audio/TapRenderersFactory.kt",
            ).readText()
        assertTrue(
            "buildAudioSink no longer installs audioProcessorChain()",
            source.contains(".setAudioProcessorChain(audioProcessorChain())"),
        )
    }
}
