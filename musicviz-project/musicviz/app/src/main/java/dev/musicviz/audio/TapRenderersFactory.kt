package dev.musicviz.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor

/**
 * [DefaultRenderersFactory] whose audio sink tees PCM into [sink] before it
 * reaches the device. Permission-free alternative to android.media.audiofx.Visualizer.
 */
@OptIn(UnstableApi::class)
class TapRenderersFactory(
    context: Context,
    private val sink: TeeAudioProcessor.AudioBufferSink,
) : DefaultRenderersFactory(context) {
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
            .setAudioProcessors(arrayOf(TeeAudioProcessor(sink)))
            .build()
}
