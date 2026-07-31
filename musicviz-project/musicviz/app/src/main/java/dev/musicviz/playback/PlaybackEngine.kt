package dev.musicviz.playback

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import dev.musicviz.analysis.AnalysisEngine
import dev.musicviz.audio.AiffExtractor
import dev.musicviz.audio.AudioFxController
import dev.musicviz.audio.PcmRingBuffer
import dev.musicviz.audio.PcmTapSink
import dev.musicviz.audio.TapRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Process-wide owner of the ExoPlayer, its PCM tap and the audio-effects
 * chain.
 *
 * These used to live in [dev.musicviz.ui.PlayerViewModel], which tied their
 * lifetime to the Activity: once MainActivity was destroyed the player was
 * released mid-track, and with no foreground service the process was a
 * candidate for eviction as soon as the app left the screen. Hoisting them
 * here lets [PlaybackService] hold a MediaSession over the same player, so
 * playback survives a locked screen and a backgrounded app.
 *
 * The ViewModel still drives the player directly rather than through a
 * MediaController: it needs the concrete ExoPlayer for the audio session id
 * (equalizer), volume ramps (sleep timer) and the tap renderers factory that
 * feeds the visualizer — none of which a MediaController exposes.
 *
 * Construct and touch [player] on the main thread only.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine private constructor(
    private val app: Application,
) {
    /** Rolling PCM window written by the tap, read by the analysis loop. */
    val ring = PcmRingBuffer()

    /** Publishes AudioFeatures for the visualizer; runs for the process's life. */
    val analysis = AnalysisEngine(ring)

    /** Equalizer / bass boost / loudness, bound to the player's audio session. */
    val audioFx = AudioFxController(app)

    /**
     * Set by the ViewModel to receive decoded-output format changes for the
     * audio-quality readout. Invoked on the playback thread.
     */
    @Volatile
    var onTapFormat: ((sampleRateHz: Int, channelCount: Int, encoding: Int) -> Unit)? = null

    private val sink =
        PcmTapSink(ring) { rate, channels, encoding ->
            analysis.sampleRateHz = rate
            onTapFormat?.invoke(rate, channels, encoding)
        }

    val player: ExoPlayer =
        ExoPlayer
            .Builder(app, TapRenderersFactory(app, sink))
            // AIFF/AIFC support: Media3 ships no AIFF extractor, so ours is
            // appended after the defaults (sniff order keeps defaults first).
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    app,
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
            .apply {
                // Without a wake lock the CPU can sleep with the screen off and
                // playback stutters or stalls; Media3 acquires/releases it in
                // step with playWhenReady, so it costs nothing while paused.
                setWakeMode(C.WAKE_MODE_LOCAL)
            }

    /**
     * Application-lifetime scope for the analysis loop. Deliberately not the
     * viewModelScope: the loop must keep feeding the ring buffer's consumers
     * while the UI is gone, so a returning Activity sees live features
     * immediately instead of a frame of silence.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        analysis.start(scope)
    }

    /**
     * Quiesces a stale engine. Only reachable when the Application instance
     * changes, which on a device never happens — a process has exactly one
     * Application — so this exists purely to keep Robolectric's per-test
     * Application from being served an engine built against the previous one.
     *
     * Deliberately does NOT call player.release(): ExoPlayer.release() blocks
     * the calling thread until its internal playback thread acknowledges, and
     * under Robolectric's paused looper mode that background looper only
     * advances when the test drives it — which it cannot, because the test
     * thread is the one blocked. The result is a hung test run rather than a
     * failing one. The stale player is left to GC; it holds no foreground
     * service and, in the only situation that reaches this code, the test JVM
     * is about to move on anyway.
     */
    private fun releaseQuietly() {
        runCatching { analysis.stop() }
        runCatching { scope.cancel() }
        runCatching { audioFx.release() }
    }

    companion object {
        @Volatile
        private var instance: PlaybackEngine? = null

        /**
         * The process-wide engine, created on first use. Main thread only.
         *
         * Keyed on the Application instance rather than being a plain
         * singleton: Robolectric builds a fresh Application per test method
         * while static state survives in the sandbox classloader, so a plain
         * singleton would hand later tests a player bound to a dead Looper.
         * A real process only ever has one Application, so this costs nothing
         * on device.
         */
        @MainThread
        fun get(app: Application): PlaybackEngine {
            instance?.let { if (it.app === app) return it }
            return synchronized(this) {
                val current = instance
                if (current != null && current.app === app) {
                    current
                } else {
                    current?.releaseQuietly()
                    PlaybackEngine(app).also { instance = it }
                }
            }
        }
    }
}
