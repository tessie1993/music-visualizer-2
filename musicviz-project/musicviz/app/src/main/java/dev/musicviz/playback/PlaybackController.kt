package dev.musicviz.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import dev.musicviz.analysis.PlaybackMath
import dev.musicviz.audio.AiffExtractor
import dev.musicviz.audio.PcmRingBuffer
import dev.musicviz.audio.PcmTapSink
import dev.musicviz.audio.TapRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer, the PCM tap that feeds the visualizer, the playback
 * queue and the sleep timer.
 *
 * Split out of PlayerViewModel so playback is a plain object with no
 * ViewModel lifetime attached to it: the ViewModel dies with its Activity,
 * and audio must not. Nothing here knows about the music library, presets,
 * analysis or the UI — the pieces that do stay in the ViewModel and reach in
 * through [mediaItemFactory] and [addListener].
 *
 * Threading: every method touches the player and so must be called from the
 * app's main thread, which is the player's application thread. [ring] is the
 * one exception — the tap writes it from the playback thread and the GL
 * thread reads it, which [PcmRingBuffer] is built for.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
) {
    /** PCM captured off the audio sink, read by the renderer and the analyzer. */
    val ring = PcmRingBuffer()

    /**
     * Decoded-output format callback, fired on the playback thread on every
     * audio-pipeline reconfigure. Assigned by the owner after construction so
     * that the callback can safely touch the owner's own fields — a sink
     * callback that fired during construction would see them uninitialized.
     */
    var onAudioFormat: ((sampleRateHz: Int, channelCount: Int, encoding: Int) -> Unit)? = null

    /**
     * Builds the queue entry for a uri. The default carries no metadata; the
     * ViewModel replaces it with one that joins the library and embedded tags
     * so the player state (and the lock screen) shows real titles.
     */
    var mediaItemFactory: (Uri) -> MediaItem = { MediaItem.fromUri(it) }

    private val sink =
        PcmTapSink(ring) { rate, channels, encoding ->
            onAudioFormat?.invoke(rate, channels, encoding)
        }

    /**
     * The player itself. Exposed because a MediaSession has to be handed the
     * real Player instance; prefer the typed transport methods below for
     * everything else, so callers do not grow their own copy of the queue and
     * seek rules.
     */
    val player: ExoPlayer =
        ExoPlayer
            .Builder(context, TapRenderersFactory(context, sink))
            // AIFF/AIFC support: Media3 ships no AIFF extractor, so ours is
            // appended after the defaults (sniff order keeps defaults first).
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    context,
                    ExtractorsFactory {
                        DefaultExtractorsFactory().createExtractors() + AiffExtractor()
                    },
                ),
            ).setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            ).build()

    /** Main-thread scope for the sleep timer; cancelled by [release]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun addListener(listener: Player.Listener) = player.addListener(listener)

    val audioSessionId: Int get() = player.audioSessionId

    val positionMs: Long get() = player.currentPosition

    /** The queue entry's own title, or its file name with the extension off. */
    private fun titleOf(item: MediaItem?): String? {
        if (item == null) return null
        return item.mediaMetadata.title?.toString() ?: fileNameTitleOf(item)
    }

    private fun fileNameTitleOf(item: MediaItem?): String? =
        item
            ?.localConfiguration
            ?.uri
            ?.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')

    fun snapshot(): PlaybackSnapshot =
        PlaybackSnapshot(
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
            artist = player.mediaMetadata.artist?.toString(),
            // The player's combined metadata first, then the file name. NOT
            // the queue entry's own title in between: the combined metadata
            // already folds that in, and consulting it separately would let a
            // stale item title outrank what is actually playing.
            title = player.mediaMetadata.title?.toString() ?: fileNameTitleOf(player.currentMediaItem),
            hasMedia = player.currentMediaItem != null,
            queueSize = player.mediaItemCount,
            queueIndex = player.currentMediaItemIndex,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )

    // ---- Queue ----

    /**
     * Replaces the queue and starts playing at [startIndex]. Returns the uri
     * that will play, or null when [uris] is empty and the queue is untouched.
     */
    fun setQueue(
        uris: List<Uri>,
        startIndex: Int = 0,
        play: Boolean = true,
    ): Uri? {
        if (uris.isEmpty()) return null
        val at = startIndex.coerceIn(0, uris.size - 1)
        player.setMediaItems(uris.map(mediaItemFactory))
        player.prepare()
        if (at != 0) player.seekTo(at, 0L)
        if (play) player.play()
        return uris[at]
    }

    /** Prepares [uri] without playing it (startup auto-resume). */
    fun prepareOnly(uri: Uri) {
        player.setMediaItems(listOf(mediaItemFactory(uri)))
        player.prepare()
    }

    /** Inserts [uri] directly after the playing item. */
    fun addNext(uri: Uri) {
        player.addMediaItem(
            QueueOps.insertNextIndex(player.currentMediaItemIndex, player.mediaItemCount),
            mediaItemFactory(uri),
        )
    }

    /** Appends [uri] to the end of the queue. */
    fun addLast(uri: Uri) {
        player.addMediaItem(mediaItemFactory(uri))
    }

    /** Human-readable labels for the queue, in play order. */
    fun queueTitles(): List<String> =
        (0 until player.mediaItemCount).map { i ->
            titleOf(player.getMediaItemAt(i)) ?: "Track ${i + 1}"
        }

    /** Jumps playback to the given queue position. */
    fun playQueueIndex(index: Int) {
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, 0L)
            player.play()
        }
    }

    fun next() = player.seekToNextMediaItem()

    fun previous() = player.seekToPreviousMediaItem()

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Seeks to a fraction of the current track; ignored while duration is unknown. */
    fun seekToFraction(fraction: Float) {
        val d = player.duration
        if (d > 0) player.seekTo((d * fraction).toLong())
    }

    /** Relative seek, clamped to the track. */
    fun seekBy(deltaMs: Long) {
        val d = player.duration
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(if (d > 0) target.coerceAtMost(d) else target)
    }

    // ---- Options ----

    var shuffle: Boolean
        get() = player.shuffleModeEnabled
        set(value) {
            player.shuffleModeEnabled = value
        }

    var repeatMode: Int
        get() = player.repeatMode
        set(value) {
            player.repeatMode = value
        }

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun cycleRepeatMode() {
        player.repeatMode =
            when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
    }

    /** Pushes speed/pitch, skip-silence and noisy-handling onto the player. */
    fun applyPlaybackPrefs(
        speed: Float,
        pitchSemitones: Float,
        skipSilence: Boolean,
        pauseOnNoisy: Boolean,
    ) {
        player.playbackParameters = PlaybackParameters(speed, PlaybackMath.semitonesToRatio(pitchSemitones))
        player.skipSilenceEnabled = skipSilence
        player.setHandleAudioBecomingNoisy(pauseOnNoisy)
    }

    // ---- Sleep timer ----

    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)

    /** Remaining sleep-timer time, or null when no timer is running. */
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs

    /**
     * Starts (or restarts) the sleep timer: counts down, fades the volume over
     * the final few seconds, pauses, then restores full volume for next play.
     */
    fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        sleepTimerJob?.cancel()
        sleepTimerJob =
            scope.launch {
                val endMs = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
                while (true) {
                    val remaining = endMs - android.os.SystemClock.elapsedRealtime()
                    if (remaining <= 0L) break
                    _sleepTimerRemainingMs.value = remaining
                    player.volume = PlaybackMath.sleepFadeVolume(remaining)
                    delay(if (remaining <= PlaybackMath.SLEEP_FADE_MS) 100 else 500)
                }
                player.pause()
                player.volume = 1f
                _sleepTimerRemainingMs.value = null
                sleepTimerJob = null
            }
    }

    /** Cancels a running sleep timer and restores full volume. */
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingMs.value = null
        player.volume = 1f
    }

    fun release() {
        scope.cancel()
        player.release()
    }
}
