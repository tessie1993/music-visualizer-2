package dev.geode.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.geode.audio.AudioFxController
import dev.geode.audio.PcmRingBuffer
import dev.geode.audio.TapRenderersFactory
import dev.geode.engine.audio.AudioPresentationClock
import dev.geode.engine.audio.PcmSink
import dev.geode.engine.audio.SampleRing
import dev.geode.engine.audioandroid.PcmTap
import dev.geode.engine.audioandroid.SinkClockDriver
import dev.geode.engine.audioandroid.TapBoundaryListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
     * The V2 ring, written in parallel with [ring] and read by nothing yet.
     *
     * Both are fed so the switch of readers is its own slice: §2.1 rule 7
     * forbids removing a legacy seam in the slice that introduces its
     * replacement, and here that is worth more than the rule — every scene,
     * the beat tracker, the exporter and the wallpaper read [ring], and moving
     * them is a change that has to be provable against captured features
     * rather than bundled into the write path.
     *
     * The capacity is not arbitrary. `SampleRing.write` **requires** each write
     * to fit inside the reader runway, and it throws on the playback thread if
     * it does not - which would stop the music. The tap delivers at most one
     * staging chunk per write, so the runway (a quarter of capacity by default)
     * has to exceed that; at 65,536 frames it is 16,384, four times the chunk.
     * `a decoder buffer far larger than the tap's staging window still fits`
     * is the test that holds this true if either constant moves.
     */
    internal val sampleRing = SampleRing(capacityFrames = 1 shl 16, channelCount = 2)

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

    /**
     * The map between captured frames and the time they are heard.
     *
     * Owned here for the same reason [ring] is: a clock fed from a
     * screen-scoped listener stops updating the moment the app is swiped away
     * while [PlaybackService] keeps playing. `internal` because nothing
     * consumes it yet — the bridge to the V2 ring is the Phase 2 gate slice —
     * and public API with no caller is dead API.
     */
    internal val presentationClock = AudioPresentationClock()

    /** `internal` so a test can drive the real chain hooks into the real clock. */
    internal val clockDriver = SinkClockDriver(presentationClock)

    /**
     * Where captured audio goes, whatever captured it.
     *
     * One definition for all three producers - the playback tap, the
     * microphone and the playback capture - because "which rings receive live
     * input" is one fact, and stating it three times is how two of them end up
     * disagreeing. The failure would be silent: visuals that work over the
     * app's own playback and sit still over a live mic.
     */
    internal val captureSink =
        PcmSink { samples, frames, channels ->
            ring.writeInterleaved(samples, frames, channels)
            sampleRing.write(samples, frames, channels)
        }

    /**
     * The capture end of the pipeline, in `:engine:audio-android` per §4.1.
     * It knows a [captureSink], not this class's buffers, which is what lets
     * the destination change without touching the capture path.
     *
     * `internal` so a test can push audio through the real tap and watch it
     * arrive. The join between the capture path and everything that draws is
     * one reference wide, and wiring it wrongly is silent - a visualizer that
     * sits still over playing music, which is the failure this class's own
     * documentation says it exists to prevent.
     */
    internal val tap =
        PcmTap(captureSink) { format ->
            val hook = onAudioFormat
            if (hook != null) {
                hook(format.sampleRateHz, format.channelCount, format.encoding)
            } else {
                // No screen attached: nobody else can retune the analyzer to
                // the sink's new rate (live-input rate ownership is a screen
                // concern), so the session does it - the wallpaper's feed
                // must stay in tune with the music the service is playing.
                analysis.sampleRateHz = format.sampleRateHz
            }
        }.apply {
            // Both subscribers, in one place, so the ring's numbering and the
            // clock's are the same number rather than two counters that agree
            // by habit: the tap's generation drives both. Ring first, so a
            // segment is never opened for a generation the ring has not begun.
            boundaryListener =
                TapBoundaryListener { ended, endedFrames, begun ->
                    sampleRing.beginEpoch()
                    clockDriver.onTapBoundary(ended, endedFrames, begun)
                }
        }

    /**
     * The player itself. Public because a MediaSession has to be handed the
     * real Player instance - that is what makes the lock screen, the
     * notification and a Bluetooth button drive the audio that is actually
     * playing rather than a second, silent copy of it.
     */
    val player: ExoPlayer =
        ExoPlayer
            .Builder(context, TapRenderersFactory(context, tap, clockDriver))
            // AIFF/AIFC support: Media3 ships no AIFF extractor, so ours is
            // appended after the defaults (sniff order keeps defaults first).
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    context,
                    androidx.media3.extractor.ExtractorsFactory {
                        androidx.media3.extractor
                            .DefaultExtractorsFactory()
                            .createExtractors() +
                            dev.geode.audio.AiffExtractor()
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
     * Work that must live exactly as long as the player does - currently the
     * sleep timer. Main-dispatcher because everything launched here talks to
     * the player; cancelled in [release], the only moment the player goes away.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The sleep timer. Here rather than in the ViewModel because a timer the
     * user set and walked away from must survive the screen being swiped
     * away, exactly like the music it is going to stop - see [SleepTimer].
     */
    val sleepTimer = SleepTimer(player, scope)

    /**
     * Live analysis of [ring], HERE rather than on the ViewModel for the same
     * reason the ring is: the wallpaper reads its output through
     * [dev.geode.audio.AudioBus] and outlives every Activity, so an
     * analyzer scoped to a screen went idle the moment the app was swiped
     * away while [PlaybackService] kept the music going. The worker is
     * demand-gated - it runs only while the bus has a consumer (the app's
     * screen, or a visible wallpaper) - so the 62 Hz loop never spins for
     * nobody.
     */
    val analysis = dev.geode.analysis.AnalysisEngine(sampleRing)

    private val interestHook: () -> Unit = { syncAnalysis() }

    init {
        dev.geode.audio.AudioBus.onInterestChanged = interestHook
        syncAnalysis()
        scope.launch {
            // The one publisher: raw frames straight off the analyzer. The
            // stale timeout on the bus turns a stopped analyzer into the
            // wallpaper's idle motion with no explicit clear needed.
            analysis.features.collect { dev.geode.audio.AudioBus.publish(it) }
        }
    }

    private fun syncAnalysis() {
        if (dev.geode.audio.AudioBus.hasConsumers) analysis.start(scope) else analysis.stop()
    }

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
        analysis.stop()
        if (dev.geode.audio.AudioBus.onInterestChanged === interestHook) {
            dev.geode.audio.AudioBus.onInterestChanged = null
        }
        scope.cancel()
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
     *
     * The hold is counted *after* the session exists, and that order is the
     * whole point: [rebindTo] clears both counters, so counting first meant the
     * very first acquire of the process went 0 → 1 → 0 and handed back a
     * session nobody was recorded as holding. The next release from anyone
     * else then freed a player that was still in use.
     */
    @Synchronized
    fun acquireForUi(context: Context): PlaybackSession = sessionFor(context).also { uiHolds++ }

    /** Gives back a hold taken by [acquireForUi]. Never stops playback itself. */
    @Synchronized
    fun releaseUi() {
        if (uiHolds > 0) uiHolds--
        releaseIfUnused()
    }

    /** The same player for [PlaybackService], with the service's own hold. */
    @Synchronized
    fun acquireForService(context: Context): PlaybackSession = sessionFor(context).also { serviceHolds++ }

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
