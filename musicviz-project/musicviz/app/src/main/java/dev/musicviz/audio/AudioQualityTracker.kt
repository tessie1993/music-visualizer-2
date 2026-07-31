package dev.musicviz.audio

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import dev.musicviz.analysis.AudioQualityInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks the Now Playing audio-quality readout: combines the selected track's
 * source [Format] (from `Player.Listener.onTracksChanged`, main thread) with
 * the decoded output format the read-only tap reports ([PcmTapSink]'s flush
 * callback, playback thread), and publishes the classified result.
 *
 * Translating Media3's `Format`/`C` constants into the plain ints
 * [AudioQualityInfo.classify] takes is this class's job — the info type itself
 * stays free of android/androidx/media3 imports so the headless gate can test
 * it.
 *
 * Both inputs arrive from different threads, hence the `@Volatile` fields;
 * each arrival recomputes from whatever the other side last published.
 */
@OptIn(UnstableApi::class)
class AudioQualityTracker(
    /**
     * The uri now playing, read at event time for the container guess. A
     * supplier rather than a value: the tracker is constructed with the audio
     * pipeline, before the first track is ever selected.
     */
    private val currentUri: () -> Uri?,
) {
    /** Decoded output format from the tap's flush callback. */
    private data class TapFormat(
        val sampleRateHz: Int,
        val channelCount: Int,
        val encoding: Int,
    )

    @Volatile
    private var tapFormat: TapFormat? = null

    @Volatile
    private var sourceFormat: Format? = null

    private val _info = MutableStateFlow<AudioQualityInfo?>(null)

    /** Source vs decoded-output quality of the current track; null when idle. */
    val info: StateFlow<AudioQualityInfo?> = _info

    /** Called from the playback thread on every audio-pipeline reconfigure. */
    fun onTapFormat(
        sampleRateHz: Int,
        channelCount: Int,
        encoding: Int,
    ) {
        tapFormat = TapFormat(sampleRateHz, channelCount, encoding)
        recompute()
    }

    /** Called when track selection changes; null clears the readout. */
    fun onSourceFormat(format: Format?) {
        sourceFormat = format
        recompute()
    }

    private fun recompute() {
        val src = sourceFormat
        if (src == null) {
            _info.value = null
            return
        }
        val tap = tapFormat
        _info.value =
            AudioQualityInfo.classify(
                mime = src.sampleMimeType,
                container = containerGuess(),
                sourceSampleRateHz = src.sampleRate.takeIf { it != Format.NO_VALUE } ?: 0,
                sourceChannels = src.channelCount.takeIf { it != Format.NO_VALUE } ?: 0,
                bitDepth = bitDepthOf(src.pcmEncoding),
                bitrateBps = src.bitrate.takeIf { it != Format.NO_VALUE } ?: 0,
                outputSampleRateHz = tap?.sampleRateHz ?: 0,
                outputChannels = tap?.channelCount ?: 0,
                outputFloat = tap?.encoding == C.ENCODING_PCM_FLOAT,
            )
    }

    /** Container guess from the uri's file extension ("" for opaque uris). */
    private fun containerGuess(): String {
        val name = currentUri()?.lastPathSegment?.substringAfterLast('/') ?: return ""
        val ext = name.substringAfterLast('.', "")
        return if (ext.length in 1..4) ext.lowercase() else ""
    }

    /** Bits per sample for a Media3 PCM encoding constant; 0 = unknown. */
    private fun bitDepthOf(pcmEncoding: Int): Int =
        when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN -> 32
            C.ENCODING_PCM_FLOAT -> 32
            else -> 0
        }
}
