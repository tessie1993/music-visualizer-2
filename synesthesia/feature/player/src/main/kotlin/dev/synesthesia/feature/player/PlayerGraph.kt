package dev.synesthesia.feature.player

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import dev.synesthesia.core.audio.SampleRing
import dev.synesthesia.core.audio.TapAudioProcessor

/**
 * Composition root for the playback half of the data flow (M7-foundation).
 * ExoPlayer -> DefaultAudioSink -> [TapAudioProcessor] -> SampleRing.
 * All @UnstableApi usage confined HERE per blueprint decision #3.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlayerGraph {

    /** ~4 seconds of mono analysis headroom at 48 kHz (192,000 frames). */
    const val RING_CAPACITY_FRAMES: Int = 48_000 * 4

    fun buildRing(): SampleRing = SampleRing(RING_CAPACITY_FRAMES)

    fun buildTap(ring: SampleRing): TapAudioProcessor =
        TapAudioProcessor(
            sink = { pcm, frames, _ -> ring.write(pcm, frames) },
            onSampleRateChanged = { ring.beginEpoch() },
        )

    fun buildExoPlayer(context: Context, tap: TapAudioProcessor): ExoPlayer {
        val audioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(tap))
            .build()
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = audioSink
        }
        return ExoPlayer.Builder(context, renderersFactory).build()
    }
}
