package dev.musicviz.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import dev.musicviz.engine.audioandroid.SinkClockHooks
import dev.musicviz.engine.audioandroid.SkippedFrameSource

/**
 * The audio sink's processor chain, with the order stated rather than inherited.
 *
 * `DefaultAudioSink.Builder.setAudioProcessors(...)` looks like it gives you an
 * ordered chain. It does not: it wraps the array in media3's own
 * `DefaultAudioProcessorChain`, which allocates `length + 2` and **appends**
 * [SilenceSkippingAudioProcessor] and [SonicAudioProcessor] after everything you
 * passed. Both of those accept `ENCODING_PCM_16BIT` only, so the first stage of
 * ours that emits float would make the pipeline's `configure` throw and playback
 * would fail at track start - with the stack trace pointing at media3.
 *
 * Owning the chain fixes the ordering *and* the format problem at once: media3's
 * two stages go before ours, so anything we add is downstream of everything
 * 16-bit-only and a float stage has nothing left to offend.
 *
 * ## The order, and why it is this order
 *
 * 1. **[tap] first.** Everything after this point is audio the analysis must not
 *    see. `FeatureExtractor` and `AnalysisEngine` both hold live features to
 *    matching the offline and exported ones for the same file, and the offline
 *    path decodes the file with no user EQ in it. A tap below user-tunable DSP
 *    would make live visuals diverge from every exported video, differently per
 *    user and per preset, with nothing able to test it. Output metering, if it is
 *    ever wanted, gets its own second tap that feeds meters and never
 *    `AudioFeatures`.
 * 2. **Silence skipping, then Sonic** - media3's own stages, in media3's own
 *    relative order, so speed/pitch and skip-silence keep behaving exactly as
 *    they do today.
 * 3. **[dsp] last**, in the caller's order. Empty today, which makes this class
 *    byte-for-byte equivalent to the `setAudioProcessors(arrayOf(tap))` it
 *    replaced.
 *
 * [applyPlaybackParameters] and [applySkipSilenceEnabled] are not optional
 * plumbing - they are how `PlaybackParameters` and the skip-silence toggle reach
 * the two stages that implement them. A chain that forgets them silently breaks
 * both features, which is the trap in hand-rolling this interface.
 */
@OptIn(UnstableApi::class)
class MvzAudioProcessorChain(
    tap: AudioProcessor,
    dsp: List<AudioProcessor> = emptyList(),
    private val hooks: SinkClockHooks = SinkClockHooks.None,
) : AudioProcessorChain,
    SkippedFrameSource {
    private val silenceSkipping = SilenceSkippingAudioProcessor()
    private val sonic = SonicAudioProcessor()

    private val processors: Array<AudioProcessor> =
        (listOf(tap, silenceSkipping, sonic) + dsp).toTypedArray()

    override fun getAudioProcessors(): Array<AudioProcessor> = processors

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        sonic.setSpeed(playbackParameters.speed)
        sonic.setPitch(playbackParameters.pitch)
        hooks.onSpeedApplied(playbackParameters.speed)
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkipping.setEnabled(skipSilenceEnabled)
        hooks.onSkipSilenceApplied(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long = sonic.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long = silenceSkipping.skippedFrames

    /**
     * Media3's own name for this number says "output"; for this chain it is
     * input frames, in the tap's own domain.
     *
     * [SilenceSkippingAudioProcessor] counts `(consumed - output) / (channels
     * * 2)` against its own input format, and it sits directly after a
     * pass-through tap that neither resamples nor rechannels - so the
     * conversion is the identity. `AudioChainOrderRuntimeTest` pins the
     * adjacency, because a resampling stage inserted between them would
     * corrupt every skip count with no other symptom.
     */
    override fun skippedInputFramesSinceFlush(): Long = silenceSkipping.skippedFrames
}
