package dev.musicviz.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import dev.musicviz.audio.dsp.MvzAudioProcessorChain

/**
 * [DefaultRenderersFactory] whose audio sink tees PCM into [sink] before it
 * reaches the device. Permission-free alternative to android.media.audiofx.Visualizer.
 */
@OptIn(UnstableApi::class)
class TapRenderersFactory(
    context: Context,
    private val sink: TeeAudioProcessor.AudioBufferSink,
) : DefaultRenderersFactory(context) {
    /**
     * The chain [buildAudioSink] installs.
     *
     * Extracted so the tap-first invariant is asserted against the array this
     * actually builds, rather than by reading the order out of this file.
     * A text proof stops proving anything the moment the tap moves module -
     * which MASTER_PLAN §12 schedules - and it stops loudly enough that
     * nobody notices.
     */
    internal fun audioProcessorChain(): MvzAudioProcessorChain = MvzAudioProcessorChain(TeeAudioProcessor(sink))

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink
            .Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            // Media3 renamed this along with the AudioTrack -> AudioOutput
            // abstraction; the old spelling is a one-line delegate to it, so
            // the rename is exactly behaviour-preserving. The parameter keeps
            // the factory's name because the only `buildAudioSink` override
            // Media3 offers still spells it that way.
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            // Not setAudioProcessors: that wraps the array in media3's own chain,
            // which appends its two 16-bit-only stages AFTER ours. Owning the
            // chain puts them before ours instead, which is what lets a future
            // float DSP stage exist at all. Order and rationale live in
            // MvzAudioProcessorChain; with no DSP stages it is exactly the
            // single-tap array this replaced.
            .setAudioProcessorChain(audioProcessorChain())
            .build()
}
