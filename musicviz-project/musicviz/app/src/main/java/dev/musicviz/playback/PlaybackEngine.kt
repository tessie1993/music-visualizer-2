package dev.musicviz.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.musicviz.audio.AudioFxController
import dev.musicviz.audio.PcmRingBuffer
import dev.musicviz.audio.PcmTapSink
import dev.musicviz.audio.TapRenderersFactory

/**
 * One player and everything welded to it: the PCM tap that feeds the
 * visualizer, and the effects chain attached to the player's audio session.
 *
 * [ring] lives here rather than in the ViewModel because it is not a separate
 * object at all - the tap sink writes into this exact buffer from inside the
 * audio pipeline, so a ring created alongside a new ViewModel would be one
 * nothing ever writes to: silent visuals over playing music. Everything
 * downstream (every scene, the beat tracker, the exporter, the live wallpaper)
 * reads it, so it has to be the player's own.
 */
@OptIn(UnstableApi::class)
class PlaybackSession internal constructor(
    context: Context,
) {
    /** PCM captured off the audio sink, read by the renderer and the analyzer. */
    val ring = PcmRingBuffer()

    /**
     * Decoded-output format, delivered on the playback thread every time the
     * audio pipeline reconfigures.
     *
     * Settable rather than a constructor argument because the only listener is
     * the live analyzer, which belongs to whatever screen is up and therefore
     * comes and goes; a reconfigure that lands while no screen is attached has
     * nobody to tell, and that is fine - the next screen reads the rate off the
     * next reconfigure.
     */
    @Volatile
    var onAudioFormat: ((sampleRateHz: Int, channelCount: Int, encoding: Int) -> Unit)? = null

    private val sink =
        PcmTapSink(ring) { rate, channels, encoding ->
            onAudioFormat?.invoke(rate, channels, encoding)
        }

    /**
     * The player itself. Public because a MediaSession has to be handed the
     * real Player instance - that is what makes the lock screen, the
     * notification and a Bluetooth button drive the audio that is actually
     * playing rather than a second, silent copy of it.
     */
    val player: ExoPlayer =
        ExoPlayer
            .Builder(context, TapRenderersFactory(context, sink))
            // AIFF/AIFC support: Media3 ships no AIFF extractor, so ours is
            // appended after the defaults (sniff order keeps defaults first).
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    context,
                    androidx.media3.extractor.ExtractorsFactory {
                        androidx.media3.extractor
                            .DefaultExtractorsFactory()
                            .createExtractors() +
                            dev.musicviz.audio.AiffExtractor()
                    },
                ),
            ).setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // handleAudioFocus. This one flag IS the app's audio focus
                // policy, and it has to be right now that playback outlives the
                // screen: ExoPlayer requests focus when it starts, pauses on a
                // permanent loss (another player took over), pauses on a
                // transient one (a phone call) and resumes afterwards, and
                // ducks to a low volume for a transient loss the system says
                // may be ducked (a navigation prompt) rather than stopping the
                // music dead. Hand-rolling that from a service is how apps end
                // up talking over calls.
                true,
            ).build()

    /**
     * The equalizer/bass/loudness chain.
     *
     * Here rather than in the ViewModel because it is bound to the player's
     * audio session: releasing it when a screen goes away would silently drop
     * the user's EQ out of music that is still playing.
     */
    val audioFx = AudioFxController(context)

    /**
     * True while the player intends to make sound, including while it is
     * buffering and while the system has suppressed it (a transient focus
     * loss). Deliberately weaker than [ExoPlayer.isPlaying], which is false in
     * both of those states: a player that is only waiting for a phone call to
     * end must not be mistaken for one that is finished with.
     */
    val playbackWanted: Boolean
        get() =
            player.playWhenReady &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED

    internal fun release() {
        onAudioFormat = null
        audioFx.release()
        player.release()
    }
}

/**
 * The process-wide owner of [PlaybackSession], so that playback belongs to the
 * app rather than to whatever is on screen.
 *
 * A ViewModel dies with its Activity and music must not, so the player cannot
 * be a ViewModel field. It cannot belong solely to [PlaybackService] either:
 * the service is created when playback starts and destroyed when it is over,
 * while the UI needs a player from the moment it opens. Both borrow from here
 * and both get the same instance. A second ExoPlayer is the failure this whole
 * object exists to prevent - it would mean correct-looking transport controls
 * over silence, two decoders fighting for the audio device, and two writers
 * into the one analysis ring buffer.
 *
 * Teardown is reference counted rather than lifecycle driven, because there are
 * two independent owners and either can outlive the other: the screen holds one
 * count from the ViewModel's constructor to its onCleared, the service holds
 * one from onCreate to onDestroy, and the player is freed only once both are
 * gone. That is the only moment at which freeing it cannot break someone -
 * releasing an ExoPlayer that a live ViewModel still points at makes every
 * subsequent call on it throw.
 *
 * Main thread only. That is not a restriction this class adds: an ExoPlayer may
 * only be driven from the thread it was built on, and both owners run there.
 */
object PlaybackEngine {
    private var app: Context? = null
    private var session: PlaybackSession? = null
    private var uiHolds = 0
    private var serviceHolds = 0

    /**
     * Drops everything if [context] belongs to a different Application than the
     * one cached.
     *
     * In the app this never fires - an Application is a process singleton, so
     * the check costs one reference comparison. Under Robolectric it is what
     * keeps the suite honest: every test method gets a fresh Application and a
     * fresh main Looper, and a player built in an earlier test would still be
     * bound to the dead one, so the next test would drive a player whose
     * handler goes nowhere.
     */
    private fun rebindTo(context: Context): Context {
        val current = context.applicationContext
        if (app !== current) {
            // Dropped, deliberately NOT released. ExoPlayer.release() blocks
            // until its playback thread acknowledges, and the only situation
            // that reaches this branch is a Robolectric test whose loopers are
            // paused - so the acknowledgement never arrives and the whole suite
            // hangs on the main thread. The stale player belongs to an
            // Application that is already gone; letting it become garbage is
            // both correct and the only thing that terminates.
            session = null
            uiHolds = 0
            serviceHolds = 0
            app = current
        }
        return current
    }

    @Synchronized
    private fun sessionFor(context: Context): PlaybackSession {
        val current = rebindTo(context)
        return session ?: PlaybackSession(current).also { session = it }
    }

    /**
     * The player, plus a hold that keeps it alive for as long as a screen is
     * pointing at it. Every caller must give the hold back with [releaseUi].
     */
    @Synchronized
    fun acquireForUi(context: Context): PlaybackSession {
        uiHolds++
        return sessionFor(context)
    }

    /** Gives back a hold taken by [acquireForUi]. Never stops playback itself. */
    @Synchronized
    fun releaseUi() {
        if (uiHolds > 0) uiHolds--
        releaseIfUnused()
    }

    /** The same player for [PlaybackService], with the service's own hold. */
    @Synchronized
    fun acquireForService(context: Context): PlaybackSession {
        serviceHolds++
        return sessionFor(context)
    }

    /** Gives back the hold taken by [acquireForService]. */
    @Synchronized
    fun releaseService() {
        if (serviceHolds > 0) serviceHolds--
        releaseIfUnused()
    }

    private fun releaseIfUnused() {
        if (uiHolds > 0 || serviceHolds > 0) return
        session?.release()
        session = null
        app = null
    }
}
