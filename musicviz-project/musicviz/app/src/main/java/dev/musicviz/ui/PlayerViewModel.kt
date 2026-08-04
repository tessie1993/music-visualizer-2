package dev.musicviz.ui

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.musicviz.analysis.AnalysisEngine
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.analysis.FeatureTimeline
import dev.musicviz.analysis.IntelligenceMode
import dev.musicviz.analysis.OfflineAnalyzer
import dev.musicviz.analysis.PlaybackMath
import dev.musicviz.analysis.SceneSuggester
import dev.musicviz.audio.AudioFxState
import dev.musicviz.audio.MicCapture
import dev.musicviz.export.ExportAspect
import dev.musicviz.export.VideoExporter
import dev.musicviz.playback.PlaybackEngine
import dev.musicviz.playback.PlaybackService
import dev.musicviz.playback.QueueOps
import dev.musicviz.render.TransitionStyle
import dev.musicviz.render.scene.CustomizeTab
import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.PcmChunk
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val title: String? = null,
    val artist: String? = null,
    val hasMedia: Boolean = false,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

data class VizUiState(
    val sceneId: String = SceneIds.NEBULA,
    val intelligenceMode: IntelligenceMode = IntelligenceMode.MANUAL,
    val suggestedSceneId: String? = null,
    val attack: Float = 0.6f,
    val decay: Float = 0.12f,
    val analyzing: Boolean = false,
    val analysisProgress: Float = 0f,
    val bpm: Float = 0f,
    val sections: List<Long> = emptyList(),
    val shaderError: String? = null,
    val presets: List<Preset> = emptyList(),
    val params: SceneParams = SceneParams.DEFAULT,
    val vizPlaylist: List<VizPlaylistEntry> = emptyList(),
    val vizPlaylistEnabled: Boolean = false,
    val vizPlaylistIntervalSec: Int = 30,
    val vizPlaylistIntelligent: Boolean = false,
    val transitionStyle: TransitionStyle = TransitionStyle.FADE,
    /**
     * Selected transition as a [dev.musicviz.render.TransitionCatalog] id: one
     * of the five built-in style names, or a gl-transitions corpus name. The
     * id is what the renderer takes; [transitionStyle] survives as the enum
     * the base shader's built-ins are indexed by.
     */
    val transitionId: String = TransitionStyle.FADE.name.lowercase(),
    val transitionDurationSec: Float = 1.2f,
    // Random mode: hops to a random style/preset on an interval (or on strong
    // musical moments). Mutually exclusive with the visual playlist.
    val randomEnabled: Boolean = false,
    val randomIntervalSec: Int = 20,
    val randomOnBeat: Boolean = true,
    val randomIncludeStyles: Boolean = true,
    val randomIncludePresets: Boolean = true,
    val randomIncludeMilk: Boolean = false,
    val randomizeColors: Boolean = false,
    /**
     * Section staging: the track's own structure drives the look.
     *
     * Distinct from the interval and "switch on a strong moment" modes, which
     * both rotate on a clock the music does not keep. Here each detected
     * section gets a look and KEEPS it, and the same section index always gets
     * the same one - so a chorus looks like the chorus every time it comes
     * round, and the video reads the shape of the song rather than a timer.
     */
    val sectionStaging: Boolean = false,
)

/** One step of the visual preset playlist. */
data class VizPlaylistEntry(
    val sceneId: String,
    val presetName: String? = null,
    val milkPath: String? = null,
    val label: String,
)

/** Side effects the GL renderer must apply for a playlist/preset step. */
data class VizApply(
    val milkPath: String? = null,
    val customShader: String? = null,
    /** Scene the [customShader] belongs to; avoids racing the scene switch. */
    val sceneId: String? = null,
)

/** A .milk file available to the milkdrop scene. */
data class MilkFile(
    val name: String,
    val path: String,
)

/**
 * A track a screen can hand straight to the player.
 *
 * Carries the title/artist the list ALREADY knows so building a queue never
 * has to reach for the ContentResolver: [PlayerViewModel.mediaItemFor] falls
 * back to a per-uri `DISPLAY_NAME` query when it has no metadata, which is
 * fine for one track and a main-thread stall for a thousand of them. Every
 * list in the app has these two strings in hand, so the queue-building path
 * takes them instead.
 */
data class QueueTrack(
    val uri: String,
    val title: String = "",
    val artist: String = "",
)

/** One row of the device music index (MediaStore). */
data class DeviceTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val durationMs: Long,
    /** MediaStore DATE_ADDED, in SECONDS since the epoch; 0 when unknown. */
    val addedSec: Long = 0L,
)

/** Music library + playlists + batch-analysis progress. */
data class LibraryState(
    val tracks: List<LibraryTrack> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val analyzing: Boolean = false,
    val analyzeProgress: Float = 0f,
)

private val AUDIO_EXTS = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "wma", "aiff")

/**
 * Performance-take state: what is being recorded, what is being replayed, and
 * the saved takes list.
 */
data class TakeUiState(
    val takes: List<TakeInfo> = emptyList(),
    val recording: Boolean = false,
    val recordedEvents: Int = 0,
    val recordedMs: Long = 0L,
    /** Name of the take currently replaying, or null. */
    val replaying: String? = null,
    val replayMs: Long = 0L,
    val replayEndMs: Long = 0L,
    /**
     * Take the next video export replays, or null for the live settings.
     *
     * PARAMETER AUTOMATION ONLY. The exporter builds one scene up front and
     * renders every frame through it, so a style SWITCH inside a take cannot
     * be reproduced offline without teaching the exporter to create, swap and
     * release scenes mid-render. Everything else a take holds - every slider,
     * colour, FX and fluid setting, moving exactly as it was performed - does
     * reach the file. The export dialog says so where the take is chosen.
     */
    val exportTake: String? = null,
)

/** Live-input state for the Settings switch: running, plus why it is not. */
data class MicState(
    val active: Boolean = false,
    val failure: MicCapture.Failure? = null,
)

/**
 * "Visualize other apps": whether the capture is running, what it can hear,
 * and - when it can hear nothing - enough context to say why.
 */
data class ExternalAudioState(
    /** False on Android 9 and older, where the API does not exist. */
    val supported: Boolean = dev.musicviz.audio.playbackCaptureSupported,
    /** True while the capture is open and feeding the analyzer. */
    val active: Boolean = false,
    /** True while waiting for the user to answer the system consent dialog. */
    val awaitingConsent: Boolean = false,
    val failure: dev.musicviz.audio.CaptureFailure? = null,
    /** What another app's media session says is playing, when readable. */
    val nowPlaying: dev.musicviz.audio.NowPlayingBridge.External? = null,
    /** True when the notification-listener switch is on, so [nowPlaying] works. */
    val hasSessionAccess: Boolean = false,
    /**
     * The capture is open, something is playing, and every sample has been an
     * exact zero for seconds: the playing app forbids capture. Spotify is the
     * one people hit.
     */
    val refusedByApp: Boolean = false,
) {
    /** The app to name in a "…won't let us listen" message, if we know it. */
    val refusingApp: String? get() = if (refusedByApp) nowPlaying?.appLabel else null
}

/** Clip list and export progress behind the Studio tab. */
data class StudioUiState(
    val clips: List<dev.musicviz.export.StudioClip> = emptyList(),
    val loading: Boolean = false,
    val running: Boolean = false,
    val progress: Float = 0f,
    /** Where the finished file landed, for the Share and Open actions. */
    val resultUri: Uri? = null,
    val error: String? = null,
)

/** The player's queue as the Now Playing queue tab reads it. */
data class QueueUiState(
    val tracks: List<QueueTrack> = emptyList(),
    val index: Int = 0,
)

data class ExportUiState(
    val running: Boolean = false,
    /** True when the user picked the output location via the file picker. */
    val customDestination: Boolean = false,
    val progress: Float = 0f,
    val resultUri: Uri? = null,
    val error: String? = null,
)

/**
 * The dialog state an export outcome produces.
 *
 * Lifted out of [PlayerViewModel.startExport] because this mapping is the only
 * part of the failure path a unit test can reach - the export itself needs a
 * hardware encoder and an EGL context - and it is the part that was wrong: a
 * refusal to write used to arrive as a plain null and was published as
 * running=false, progress=1, no uri, no error, which the dialog reads as
 * neither a success nor a failure and drops back to the options form. The
 * three outcomes must stay tellable apart from each other here.
 */
internal fun exportUiStateFor(
    result: VideoExporter.Result,
    customDestination: Boolean,
): ExportUiState =
    when (result) {
        is VideoExporter.Result.Saved ->
            ExportUiState(
                running = false,
                progress = 1f,
                resultUri = result.uri,
                customDestination = customDestination,
            )
        is VideoExporter.Result.Failed -> ExportUiState(running = false, error = result.message)
        // A cancel is the user's own decision: it says nothing and goes back to
        // the options, which is what an empty state renders as.
        VideoExporter.Result.Cancelled -> ExportUiState(running = false)
    }

/**
 * Graded beat impulse a "switch on a musical moment" decision (intelligent
 * visual playlist, Random mode's switch-on-beat) treats as strong enough to
 * act on. Track-relative by construction - [AudioFeatures.beatImpulse] folds
 * in the macro-energy envelope - so this is "one of this song's bigger hits",
 * not an absolute loudness that quiet masters never reach.
 */
private const val STRONG_MOMENT_IMPULSE = 0.6f

/**
 * Replay tick rate for performance takes. Keyframes land no closer than
 * [PerformanceTake.MIN_KEYFRAME_GAP_MS] apart, so this is comfortably finer
 * than the recording it reads - a slower clock would turn a swept slider back
 * into a staircase.
 */
private const val TAKE_REPLAY_HZ = 30L

/**
 * Longest edge the artwork is decoded to before its hues are counted. A hue
 * histogram is stable far below this; decoding a full-size sleeve to build one
 * would cost tens of megabytes for no extra precision.
 */
private const val ART_SAMPLE_SIZE = 128

/**
 * Owns playback (queue + audio focus + PCM tap), live analysis, offline
 * analysis/intelligence, presets and export orchestration.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    /**
     * The process's one player, borrowed rather than built.
     *
     * A ViewModel dies with its Activity, and music must not stop because the
     * user swiped away from the app, so the ExoPlayer - and with it the PCM tap
     * teed off its audio pipeline and the effects chain on its audio session -
     * belongs to [PlaybackEngine]. Everything below reads exactly as it did
     * when this class built the player itself; what changed is who outlives
     * whom. The hold taken here is given back in [onCleared].
     */
    private val playback = PlaybackEngine.acquireForUi(application)

    private val ring = playback.ring
    private val engine = AnalysisEngine(ring)

    /**
     * "Live input": the microphone as a second producer for the SAME ring
     * buffer the playback tap writes into, so every consumer downstream is
     * unchanged. Nothing is stored or transmitted - see [MicCapture].
     */
    private val micCapture = MicCapture(application, ring)

    private val _micState = MutableStateFlow(MicState())

    /** Microphone-driven visuals: on/off plus the last failure to report. */
    val micState: StateFlow<MicState> = _micState

    /**
     * Turns live input on or off.
     *
     * Turning it ON pauses playback: the ring buffer has ONE analysis window,
     * so a track and the room would be summed into a single spectrum and
     * neither would drive the visuals recognisably. That is also what the
     * feature asks for - visuals reacting to the room, with no music playing.
     *
     * Returns the failure that stopped it, or null on success. A refused
     * permission is reported rather than swallowed so the caller can send the
     * user to the system prompt instead of leaving a switch that silently
     * springs back.
     */
    fun setMicEnabled(enabled: Boolean): MicCapture.Failure? {
        if (!enabled) {
            micCapture.stop()
            engine.reset()
            engine.sampleRateHz = tapSampleRateHz.takeIf { it > 0 } ?: 44100
            _micState.value = MicState(active = false)
            setGuiPrefs(_guiPrefs.value.copy(micReactive = false))
            return null
        }
        if (micCapture.active) return null
        player.pause()
        val failure = micCapture.start { rate -> engine.sampleRateHz = rate }
        if (failure != null) {
            _micState.value = MicState(active = false, failure = failure)
            return failure
        }
        // The beat grid and energy envelope model one continuous piece of
        // audio; the room is a different one, exactly like a track change.
        engine.reset()
        _micState.value = MicState(active = true)
        setGuiPrefs(_guiPrefs.value.copy(micReactive = true))
        return null
    }

    /** True when the RECORD_AUDIO permission is already granted. */
    fun hasMicPermission(): Boolean = micCapture.hasPermission()

    /**
     * True while a source other than our own playback is feeding the ring
     * buffer, and therefore owns the analyzer's sample rate.
     */
    private fun externalAudioOwnsAnalyzer(): Boolean = micCapture.active || playbackCapture.active

    // ---- Visualize other apps (Spotify, YouTube, anything playing) ----

    /**
     * Third producer for the one ring buffer, after the playback tap and the
     * microphone. Held on every API level; only starting it needs Android 10,
     * and that gate lives in [startPlaybackCapture] where the reason for it
     * can be turned into something the user reads.
     */
    private val playbackCapture = dev.musicviz.audio.PlaybackCapture(ring)

    private val nowPlayingBridge = dev.musicviz.audio.NowPlayingBridge(application)

    private val _externalAudio = MutableStateFlow(ExternalAudioState())

    /** State behind the "Visualize other apps" card. */
    val externalAudio: StateFlow<ExternalAudioState> = _externalAudio

    /**
     * Records that the consent dialog is up, so the switch can show that it is
     * waiting rather than springing back while the system UI is in front.
     */
    fun noteExternalAudioConsentPending() {
        _externalAudio.update { it.copy(awaitingConsent = true, failure = null) }
    }

    /** The user dismissed the system capture dialog. */
    fun noteExternalAudioConsentDenied() {
        _externalAudio.update {
            it.copy(awaitingConsent = false, failure = dev.musicviz.audio.CaptureFailure.CONSENT)
        }
    }

    /**
     * Starts reading another app's audio with a projection the service has
     * just published. Called from the [MediaProjectionHolder] collector, not
     * by the UI: consent, the foreground service and the recorder are three
     * separate steps and only the last one belongs here.
     */
    private fun startPlaybackCapture(projection: android.media.projection.MediaProjection) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            _externalAudio.update {
                it.copy(
                    awaitingConsent = false,
                    failure = dev.musicviz.audio.CaptureFailure.UNSUPPORTED,
                )
            }
            return
        }
        if (!micCapture.hasPermission()) {
            _externalAudio.update {
                it.copy(
                    awaitingConsent = false,
                    failure = dev.musicviz.audio.CaptureFailure.PERMISSION,
                )
            }
            return
        }
        // One ring buffer, one source. Our own playback and the microphone
        // both step aside, exactly as they do for each other.
        player.pause()
        if (micCapture.active) setMicEnabled(false)
        val failure = playbackCapture.start(projection) { rate -> engine.sampleRateHz = rate }
        // The beat grid and energy envelope model one continuous piece of
        // audio; another app's stream is a different one, like a track change.
        engine.reset()
        if (failure != null) {
            // The service is what the consent flow started, and it is running
            // by the time we get here. A recorder that never opened leaves it -
            // and its "this app can hear you" notification - standing over a
            // capture that does not exist, with the only way back a switch the
            // user just watched fail. Hand the analyzer back too, since nothing
            // is going to feed it.
            engine.sampleRateHz = tapSampleRateHz.takeIf { it > 0 } ?: 44100
            dev.musicviz.audio.PlaybackCaptureService
                .stop(getApplication())
        }
        _externalAudio.update {
            it.copy(
                active = failure == null,
                awaitingConsent = false,
                failure = failure,
                refusedByApp = false,
            )
        }
    }

    /** Stops the capture and takes the foreground service down with it. */
    fun stopExternalAudio() {
        playbackCapture.stop()
        engine.reset()
        engine.sampleRateHz = tapSampleRateHz.takeIf { it > 0 } ?: 44100
        dev.musicviz.audio.PlaybackCaptureService
            .stop(getApplication())
        _externalAudio.update {
            it.copy(active = false, awaitingConsent = false, refusedByApp = false)
        }
    }

    /** Where to send the user to switch the notification listener on. */
    fun notificationAccessIntent(): android.content.Intent = nowPlayingBridge.settingsIntent()

    /**
     * Refreshes what another app is playing, and re-decides whether a silent
     * capture is being refused.
     *
     * The refusal verdict needs BOTH halves: the capture reporting nothing but
     * exact zeroes, and a session reporting that something is in fact playing.
     * Either alone is ordinary - a paused phone is silent, and a session can
     * be playing while the capture is simply not running.
     */
    private fun refreshExternalAudio() {
        val state = _externalAudio.value
        val access = nowPlayingBridge.hasAccess()
        val now = if (access) nowPlayingBridge.current() else null
        val refused = playbackCapture.active && playbackCapture.blockedLikely && (now?.playing ?: false)
        val next =
            state.copy(
                active = playbackCapture.active,
                nowPlaying = now,
                hasSessionAccess = access,
                refusedByApp = refused,
            )
        if (next != state) _externalAudio.value = next
    }

    /**
     * Notices a microphone that died under us and puts the switch back.
     *
     * [MicCapture.active] goes false on its own when the recorder stops mid-
     * capture - a call takes the microphone, another app grabs it, the device
     * refuses a read - but nothing else re-reads it: [_micState] is otherwise
     * only ever written by [setMicEnabled]. So the switch stayed on, the
     * "listening" affordance stayed up, and the visuals sat on a spectrum that
     * had stopped arriving. Hands the analyzer back to playback the same way
     * an explicit switch-off does.
     */
    private fun refreshMicState() {
        if (!_micState.value.active || micCapture.active) return
        engine.reset()
        engine.sampleRateHz = tapSampleRateHz.takeIf { it > 0 } ?: 44100
        _micState.value = MicState(active = false, failure = MicCapture.Failure.UNAVAILABLE)
        setGuiPrefs(_guiPrefs.value.copy(micReactive = false))
    }

    /**
     * Retunes the analysis chain for what the microphone is pointed at.
     *
     * Writes across three normally-separate places - beat sensitivity
     * (GuiPrefs), the reactivity envelope (vizState) and the band balance
     * (SceneParams) - because they are one decision: a guitar needs a lower
     * beat threshold AND a faster attack AND more mid, and setting one of the
     * three without the others just makes the visuals wrong differently.
     *
     * Everything it writes stays an ordinary slider afterwards. Nothing
     * remembers which profile was used, so there is no mode to fall out of
     * sync with the controls it moved.
     */
    fun applyLiveInputProfile(profile: dev.musicviz.analysis.LiveInputProfile) {
        setGuiPrefs(
            _guiPrefs.value.copy(
                beatThresholdSigma = profile.beatSigma,
                beatMinIntervalMs = profile.beatIntervalMs,
            ),
        )
        setReactivity(profile.attack, profile.decay)
        setSceneParams(profile.apply(_vizState.value.params))
    }

    /**
     * Sample rate the decoded audio pipeline last reconfigured to, remembered
     * so live input can hand the analyzer back the playback rate when it
     * stops. Written from the playback thread.
     */
    @Volatile
    private var tapSampleRateHz: Int = 0

    /**
     * Held as a field rather than passed as a lambda so [onCleared] can check
     * whether the hook still installed on the player is this ViewModel's own
     * before clearing it - see there for the race that makes the check matter.
     */
    private val audioFormatHook: (sampleRateHz: Int, channelCount: Int, encoding: Int) -> Unit =
        { rate, _, _ ->
            // Live input owns the analyzer's rate while it is running: the
            // player can still reconfigure its pipeline (a queued track being
            // prepared) and would otherwise retune the FFT to a rate no
            // samples are arriving at.
            tapSampleRateHz = rate
            if (!externalAudioOwnsAnalyzer()) engine.sampleRateHz = rate
        }

    /**
     * Its own init block, run here rather than from the main one at the bottom
     * of the class, because the player it hooks into may already be playing:
     * the engine hands back a live player when a previous screen left one
     * running, and a reconfigure landing between construction and the main init
     * would leave the analyzer tuned to a rate no samples arrive at.
     */
    init {
        playback.onAudioFormat = audioFormatHook
    }

    private val offlineAnalyzer = OfflineAnalyzer(application)
    private val presetStore = PresetStore(application)
    private val trackLibrary = TrackLibrary(application)
    private val themeStore = ThemeStore(application)
    private val playerPrefsStore = PlayerPrefsStore(application)
    private val textureStore = TextureStore(application)
    private val lfoStore = LfoStore(application)
    private val musicPlaylists = MusicPlaylistStore(application)
    private val exporter = VideoExporter(application)
    private val audioFxController = playback.audioFx

    val player: ExoPlayer = playback.player

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    private val _vizState = MutableStateFlow(restoreVizState())
    val vizState: StateFlow<VizUiState> = _vizState

    /** Prefs file for the LIVE viz state (scene + Customize params). */
    private fun vizPrefs(): android.content.SharedPreferences =
        getApplication<Application>().getSharedPreferences("musicviz-viz", android.content.Context.MODE_PRIVATE)

    /**
     * Restores the live customization on startup. Without this every app
     * restart silently reset the selected style and ALL Customize sliders to
     * defaults - only explicit presets survived. Reuses the preset JSON
     * serializer so every SceneParams field roundtrips (same coverage the
     * PresetRoundtripTest gate proves).
     *
     * Deliberately synchronous: this IS the first frame. One prefs string and
     * one JSON parse, and deferring it would draw the default style with every
     * slider at its default and then snap to the user's - the flash the live
     * state exists to prevent. The saved-preset list, which costs a directory
     * walk plus a parse per file, is what [refreshPresets] takes off this path.
     */
    private fun restoreVizState(): VizUiState {
        val base = VizUiState(presets = BuiltInPresets.ALL)
        val json = vizPrefs().getString("live_state", null) ?: return base
        return runCatching {
            val p = PresetStore.fromJson(json)
            base.copy(sceneId = p.sceneId, attack = p.attack, decay = p.decay, params = p.params)
        }.getOrDefault(base)
    }

    /**
     * Re-reads the saved presets off the main thread.
     *
     * [PresetStore.list] walks the preset directory and parses every file in
     * it, so a user with a couple of hundred presets was blocking their own
     * first frame on a couple of hundred reads. The built-ins are in
     * [restoreVizState]'s initial value, so the browser is populated from the
     * start and the user's own presets join the list a moment later rather than
     * replacing something wrong.
     */
    private fun refreshPresets() {
        viewModelScope.launch(Dispatchers.IO) {
            val listed = presetStore.list()
            withContext(Dispatchers.Main) {
                // Fills the initial value only. Saving, deleting or moving a
                // preset re-lists synchronously on the main thread, and a
                // listing that began before one of those must not land on top
                // of it - the untouched built-ins are still the same list
                // instance restoreVizState started from, which is exactly the
                // question "has anything published a list yet".
                _vizState.update {
                    if (it.presets !== BuiltInPresets.ALL) it else it.copy(presets = BuiltInPresets.ALL + listed)
                }
            }
        }
    }

    /**
     * Persists the live viz state; called from every mutation funnel.
     *
     * Coalesced onto a background thread rather than written where it is
     * called. [setSceneParams] is the funnel for every Customize slider, for
     * [nudgeTransform] (once per pinch/twist touch-move EVENT) and for take
     * replay at [TAKE_REPLAY_HZ], and one write here is a 171-field
     * serialization plus a rewrite of the whole prefs file - so a gesture used
     * to produce tens of both per second on the main thread, with apply()'s
     * queue then drained synchronously in Activity.onPause, turning the
     * backlog into a stall on the way out.
     *
     * A trailing window, not a restarting debounce: a continuous stream (a
     * slider held down, a take replaying) would keep resetting a restarting
     * timer and never write at all. The pending write reads [_vizState] AFTER
     * its delay instead of closing over the value that scheduled it, so what
     * lands is always the latest state, and clearing the scheduled flag before
     * the write means a change arriving mid-write schedules another rather
     * than being folded into one that already read past it.
     */
    private fun persistVizState() {
        vizStateDirty = true
        if (!vizPersistScheduled.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            delay(VIZ_PERSIST_WINDOW_MS)
            vizPersistScheduled.set(false)
            writeVizState()
        }
    }

    /** Serializes the live viz state into prefs. Off the main thread, or at teardown. */
    private fun writeVizState() {
        // Cleared before the state is read, so a change that lands during the
        // write is still seen as pending by the teardown path.
        vizStateDirty = false
        val s = _vizState.value
        val json = PresetStore.toJson(Preset("__live__", s.sceneId, s.attack, s.decay, null, s.params))
        // commit(), not apply(): this already runs off the main thread, and
        // apply()'s queued write is what onPause drains synchronously.
        vizPrefs().edit().putString("live_state", json).commit()
    }

    /** True when a viz-state change has not reached disk yet; read at teardown. */
    @Volatile
    private var vizStateDirty = false

    private val vizPersistScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = _exportState

    /**
     * Starts empty and is filled by [refreshLibrary]: the library file is one
     * JSON document covering every imported track and the playlists are a file
     * each, which is not work to make the first frame wait for. Every screen
     * that reads this already renders an empty list as "nothing here yet" for
     * the seconds before a device scan returns.
     */
    private val _library = MutableStateFlow(LibraryState())
    val library: StateFlow<LibraryState> = _library

    /**
     * Reads the imported-track library and the playlists off the main thread,
     * once, to fill the initial value. Skips if anything has published a list
     * meanwhile - an import or a playlist edit re-lists synchronously, and this
     * listing may have begun before it.
     */
    private fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val tracks = trackLibrary.list()
            val playlists = musicPlaylists.list()
            withContext(Dispatchers.Main) {
                _library.update {
                    if (it.tracks.isNotEmpty() || it.playlists.isNotEmpty()) it else it.copy(tracks = tracks, playlists = playlists)
                }
            }
        }
    }

    /**
     * App-side metadata overrides keyed by uri, derived from [library].
     * Screens (and search) join device/MediaStore rows against this map;
     * every [saveTrackInfo]/import/analysis pass bumps it.
     */
    val trackOverrides: StateFlow<Map<String, LibraryTrack>> =
        _library
            .map { st -> st.tracks.associateBy { it.uri } }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                _library.value.tracks.associateBy { it.uri },
            )

    private val _theme = MutableStateFlow(themeStore.load())
    val theme: StateFlow<AppTheme> = _theme

    private val _guiPrefs = MutableStateFlow(themeStore.loadGui())

    init {
        engine.beatThresholdSigma = _guiPrefs.value.beatThresholdSigma
        engine.beatMinIntervalMs = _guiPrefs.value.effectiveBeatMinIntervalMs
        // Apply the restored reactivity to the engine (setReactivity normally
        // does this, but the restored values arrive outside that path).
        engine.smoother.attack = _vizState.value.attack
        engine.smoother.decay = _vizState.value.decay
    }

    val guiPrefs: StateFlow<GuiPrefs> = _guiPrefs

    fun setGuiPrefs(prefs: GuiPrefs) {
        val previous = _guiPrefs.value
        themeStore.saveGui(prefs)
        _guiPrefs.value = prefs
        engine.beatThresholdSigma = prefs.beatThresholdSigma
        // Safe visuals floors the gap between beats, because `flash` fires
        // once per beat and no visual slider governs how often that is.
        engine.beatMinIntervalMs = prefs.effectiveBeatMinIntervalMs
        // Compare the EFFECTIVE interval, not the raw slider and not the whole
        // SafetyConfig: the effective value already folds in the Safe-visuals
        // floor, while `safety != safety` would also fire on flash depth,
        // inversion and reduced motion - none of which touch the beat grid, so
        // each tick of those sliders would re-decide tens of thousands of
        // frames to produce a byte-identical timeline.
        val sensitivityChanged =
            previous.beatThresholdSigma != prefs.beatThresholdSigma ||
                previous.effectiveBeatMinIntervalMs != prefs.effectiveBeatMinIntervalMs
        if (sensitivityChanged) redecideCachedBeats(prefs)
    }

    /**
     * Re-decides the offline timeline's beats from its stored onset curve, so
     * a sensitivity change reaches an already-analysed track without a second
     * analysis pass. Off the main thread and debounced: a slider drag calls
     * this on every tick and a full track is tens of thousands of frames.
     * (Export re-applies the current settings itself, so a drag racing the
     * export button cannot produce a stale beat grid in the file.)
     */
    private fun redecideCachedBeats(prefs: GuiPrefs) {
        val base = timeline ?: return
        val uri = currentUri
        beatRedecideJob?.cancel()
        beatRedecideJob =
            viewModelScope.launch(Dispatchers.Default) {
                delay(120)
                val updated = base.withBeatSensitivity(prefs.beatThresholdSigma, prefs.effectiveBeatMinIntervalMs)
                val now = _guiPrefs.value
                val stillCurrent =
                    now.beatThresholdSigma == prefs.beatThresholdSigma &&
                        now.effectiveBeatMinIntervalMs == prefs.effectiveBeatMinIntervalMs
                if (stillCurrent && currentUri == uri) timeline = updated
            }
    }

    fun setTheme(theme: AppTheme) {
        themeStore.save(theme)
        _theme.value = theme
    }

    // ---- Playback preferences ----

    private val _playerPrefs = MutableStateFlow(playerPrefsStore.load())

    /** Core playback preferences (speed, pitch, skip silence, sleep timer, ...). */
    val playerPrefs: StateFlow<PlayerPrefs> = _playerPrefs

    /** Applies changed playback prefs to the live player and persists them. */
    fun setPlayerPrefs(prefs: PlayerPrefs) {
        val p =
            prefs.copy(
                speed = prefs.speed.coerceIn(0.5f, 2f),
                pitchSemitones = prefs.pitchSemitones.coerceIn(-6f, 6f),
                sleepTimerMinutes = prefs.sleepTimerMinutes.coerceAtLeast(0),
            )
        _playerPrefs.value = p
        playerPrefsStore.save(p)
        applyPlaybackPrefs(p)
    }

    /** Pushes speed/pitch, skip-silence and noisy-handling onto the ExoPlayer. */
    private fun applyPlaybackPrefs(p: PlayerPrefs) {
        player.playbackParameters = PlaybackParameters(p.speed, PlaybackMath.semitonesToRatio(p.pitchSemitones))
        player.skipSilenceEnabled = p.skipSilence
        player.setHandleAudioBecomingNoisy(p.pauseOnNoisy)
    }

    /** Mirrors the player's shuffle/repeat state into the persisted prefs. */
    private fun persistPlayerOptions() {
        val p = _playerPrefs.value.copy(shuffle = player.shuffleModeEnabled, repeatMode = player.repeatMode)
        _playerPrefs.value = p
        playerPrefsStore.save(p)
    }

    // ---- Equalizer & audio effects ----

    private val _audioFx = MutableStateFlow(audioFxController.snapshot())

    /** Equalizer/bass/loudness chain state for the Settings UI. */
    val audioFx: StateFlow<AudioFxState> = _audioFx

    private fun refreshAudioFx() {
        _audioFx.value = audioFxController.snapshot()
    }

    fun setAudioFxEnabled(enabled: Boolean) {
        audioFxController.setEnabled(enabled)
        refreshAudioFx()
    }

    fun setAudioFxBand(
        band: Int,
        levelMb: Int,
    ) {
        audioFxController.setBandLevel(band, levelMb)
        refreshAudioFx()
    }

    fun useAudioFxPreset(index: Int) {
        audioFxController.usePreset(index)
        refreshAudioFx()
    }

    fun setAudioFxBassBoost(strength: Int) {
        audioFxController.setBassBoost(strength)
        refreshAudioFx()
    }

    fun setAudioFxLoudness(gainMb: Int) {
        audioFxController.setLoudness(gainMb)
        refreshAudioFx()
    }

    /** Filled by [refreshTextures]; only the milkdrop texture picker reads it. */
    private val _textures = MutableStateFlow<List<MilkTexture>>(emptyList())
    val textures: StateFlow<List<MilkTexture>> = _textures

    private fun refreshTextures() {
        viewModelScope.launch(Dispatchers.IO) {
            val listed = textureStore.list()
            // Same one-shot rule as the library: an import or a removal
            // publishes its own list and this one may predate it.
            withContext(Dispatchers.Main) { if (_textures.value.isEmpty()) _textures.value = listed }
        }
    }

    private val _lfos = MutableStateFlow(lfoStore.load())
    private val _adsrs = MutableStateFlow(lfoStore.loadAdsrs())
    val lfos: StateFlow<List<dev.musicviz.render.LfoConfig>> = _lfos
    val adsrs: StateFlow<List<dev.musicviz.render.AdsrConfig>> = _adsrs

    private fun adsrPrefs(): android.content.SharedPreferences =
        getApplication<Application>().getSharedPreferences("musicviz-mod", android.content.Context.MODE_PRIVATE)

    // ---- Randomizer with per-parameter locks (keys = slider labels) ----
    private val _lockedParams =
        MutableStateFlow<Set<String>>(
            adsrPrefs().getStringSet("locked_params", emptySet()) ?: emptySet(),
        )
    val lockedParams: StateFlow<Set<String>> = _lockedParams

    fun toggleParamLock(label: String) {
        _lockedParams.update { if (label in it) it - label else it + label }
        adsrPrefs().edit().putStringSet("locked_params", _lockedParams.value).apply()
    }

    /**
     * Randomizes the unlocked Customize parameters of [tab] within their
     * slider ranges; `null` rolls every tab.
     *
     * The roll itself lives in [ParamRandomizer] so it stays pure and unit
     * testable; locks are keyed by the slider label the lock chip persists.
     * The panel always passes the tab the button was pressed on - a roll that
     * also moved the other tabs' sliders discarded work the user could not get
     * back.
     */
    fun randomizeParams(tab: CustomizeTab? = null) {
        setSceneParams(ParamRandomizer.randomize(_vizState.value.params, _lockedParams.value, tab = tab))
    }

    fun setAdsr(
        index: Int,
        config: dev.musicviz.render.AdsrConfig,
    ) {
        val list = _adsrs.value.toMutableList()
        while (list.size < dev.musicviz.render.AdsrEngine.COUNT) list.add(dev.musicviz.render.AdsrConfig())
        if (index in list.indices) {
            list[index] = config
            _adsrs.value = list
            lfoStore.saveAdsrs(list)
        }
    }

    fun setLfo(
        index: Int,
        config: dev.musicviz.render.LfoConfig,
    ) {
        val list = _lfos.value.toMutableList()
        while (list.size < 3) list.add(dev.musicviz.render.LfoConfig())
        if (index in 0..2) {
            list[index] = config
            _lfos.value = list
            lfoStore.save(list)
        }
    }

    /**
     * Imports images into the shared milkdrop texture folder. [onImported] is
     * invoked so the caller can reload the current preset and have projectM
     * pick the new textures up.
     */
    fun importTextures(
        uris: List<Uri>,
        onImported: () -> Unit,
    ) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = textureStore.import(uris)
            withContext(Dispatchers.Main) {
                _textures.value = updated
                onImported()
            }
        }
    }

    fun removeTexture(name: String) {
        _textures.value = textureStore.remove(name)
    }

    /** Generates a display preset for [name] and hands its path to the caller. */
    fun useTexture(
        name: String,
        onReady: (String) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = runCatching { textureStore.generateDisplayPreset(name) }.getOrNull()
            withContext(Dispatchers.Main) { path?.let(onReady) }
        }
    }

    val features: StateFlow<AudioFeatures> = engine.features

    /**
     * Adds track-position context to live features for progression-driven
     * scenes (fluid spawn/catch choreography): playback progress from the
     * cached player position (refreshed by the 500 ms loop - a slow signal
     * is fine, the choreography rate-limits its motion) and section context
     * from the offline analysis when available. Without a duration (radio
     * stream, idle) features pass through with the zero defaults.
     */
    fun enrichFeatures(f: AudioFeatures): AudioFeatures {
        val ui = _uiState.value
        if (ui.durationMs <= 0L) return f
        val pos = ui.positionMs.coerceIn(0L, ui.durationMs)
        val sections = _vizState.value.sections
        var idx = 0
        for (s in sections) {
            if (s <= pos) idx++ else break
        }
        return f.copy(
            progress = pos.toFloat() / ui.durationMs,
            sectionIndex = idx,
            sectionCount = sections.size + 1,
        )
    }

    private val pcmScratch = FloatArray(4096)
    private var pcmCursor = 0L

    /** Fresh mono PCM since the last call, for the milkdrop scene (GL thread). */
    fun latestPcm(): PcmChunk? {
        val n = ring.copyNewSince(pcmCursor, pcmScratch)
        pcmCursor = ring.lastCopyEndIndex
        return if (n > 0) PcmChunk(pcmScratch, n) else null
    }

    private var builtInIndex = -1

    /** Async: copies bundled presets on first use, returns next path on main. */
    fun nextMilkPresetAsync(onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = nextBuiltInMilkPresetBlocking()
            withContext(Dispatchers.Main) { onDone(path) }
        }
    }

    private fun nextBuiltInMilkPresetBlocking(): String? =
        try {
            val files =
                importDir()
                    .listFiles { f -> f.extension == "milk" }
                    .orEmpty()
                    .sortedBy { it.name }
            if (files.isEmpty()) {
                null
            } else {
                builtInIndex = (builtInIndex + 1) % files.size
                files[builtInIndex].absolutePath
            }
        } catch (t: Throwable) {
            null
        }

    private fun builtInDir(): java.io.File = java.io.File(getApplication<Application>().filesDir, "milk-builtin")

    private fun importDir(): java.io.File = java.io.File(getApplication<Application>().filesDir, "milk")

    /** Async listing of all .milk files (bundled + imported) for the browser. */
    fun milkPresetFilesAsync(onDone: (List<MilkFile>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val files =
                try {
                    // Built-in .milk presets were removed (they were low
                    // quality); clean up any copies from older versions so
                    // they stop appearing, and list only the user's files.
                    builtInDir().deleteRecursively()
                    java.io.File(importDir(), "textures").mkdirs()
                    importDir()
                        .listFiles { f -> f.extension == "milk" }
                        .orEmpty()
                        .map { MilkFile(it.nameWithoutExtension, it.absolutePath) }
                        .sortedBy { it.name }
                } catch (t: Throwable) {
                    emptyList()
                }
            withContext(Dispatchers.Main) { onDone(files) }
        }
    }

    /** Async import of a user-picked .milk preset; path arrives on main. */
    fun importMilkPresetAsync(
        uri: Uri,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = importMilkPresetBlocking(uri)
            withContext(Dispatchers.Main) { onDone(path) }
        }
    }

    private fun importMilkPresetBlocking(uri: Uri): String? =
        try {
            val dir = java.io.File(getApplication<Application>().filesDir, "milk").apply { mkdirs() }
            val name = (uri.lastPathSegment ?: "preset").substringAfterLast('/').ifBlank { "preset" }
            val file = java.io.File(dir, if (name.endsWith(".milk")) name else "$name.milk")
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            file.absolutePath
        } catch (t: Throwable) {
            null
        }

    private var timelineBacking: FeatureTimeline? = null

    /**
     * Offline analysis for the current track. A property rather than a field
     * so the waveform is republished from every assignment site - there are
     * five, on three different paths (cache hit, fresh analysis, take replay),
     * and a seek bar that only redrew on one of them would be blank half the
     * time.
     */
    private var timeline: FeatureTimeline?
        get() = timelineBacking
        set(value) {
            timelineBacking = value
            _waveform.value = value?.let(::waveformOf)
        }
    private var currentUri: Uri? = null
    private var exportJob: Job? = null
    private var beatRedecideJob: Job? = null

    @Volatile
    private var exportCancelled = false

    // Fields used by the construction-time main loop (launched in the init
    // block below on Main.immediate, which executes synchronously until its
    // first delay). They MUST be declared before that init block: on-device
    // this crashed at launch with an NPE when applyIntelligence() read
    // _presetLocked before its initializer had run. Robolectric's deferred
    // looper hid the crash, which is why the smoke test passed.
    private val historyStore = HistoryStore(application)
    private val _historyTick = MutableStateFlow(0)
    val historyTick: StateFlow<Int> = _historyTick

    // Declared here for the same reason as the fields above, not down in the
    // "Performance takes" section they belong to: the init block below lists
    // the saved takes, and a property declared after it has not been
    // initialized when it runs. Kotlin does not catch that - the field is
    // simply null - so it surfaces as an NPE inside the ViewModel constructor,
    // i.e. as the app failing to start.
    private val takeStore = TakeStore(application)
    private val _takeState = MutableStateFlow(TakeUiState())

    /** Recording/replay state for the Takes tab. */
    val takeState: StateFlow<TakeUiState> = _takeState

    /** Keep the current preset: auto/random switching skips while locked. */
    private val _presetLocked = MutableStateFlow(false)
    val presetLocked: StateFlow<Boolean> = _presetLocked

    // Player state, declared up here for the same construction-order reason as
    // the fields above rather than in the "Player" section it belongs to: the
    // init block below starts the 500 ms poll, which touches the A-B loop and
    // the queue on its FIRST iteration. A property declared after the init
    // block is still null when that runs, and Kotlin does not catch it - it
    // surfaces as an NPE inside the ViewModel constructor, i.e. as the app
    // failing to start.
    private val favouritesStore = FavouritesStore(application)

    private val _favourites = MutableStateFlow(favouritesStore.all().toSet())

    /** Every marked uri, so a heart anywhere can be drawn from one truth. */
    val favourites: StateFlow<Set<String>> = _favourites

    private val _waveform = MutableStateFlow<FloatArray?>(null)

    /**
     * Loudness envelope of the current track, [WAVEFORM_BUCKETS] wide and
     * normalized to its own peak; null until the track has been analysed.
     *
     * Free, in the sense that matters: the offline analyzer already produces a
     * per-frame RMS curve for the visuals, so a waveform seek bar is a
     * reduction of numbers the app computed anyway rather than a second pass
     * over the file.
     */
    val waveform: StateFlow<FloatArray?> = _waveform

    private val _lyrics = MutableStateFlow<Lyrics?>(null)

    /** Words for the current track, timed when an .lrc was found. */
    val lyrics: StateFlow<Lyrics?> = _lyrics

    private val _abLoop = MutableStateFlow<AbLoop?>(null)

    /** The section being looped, or null. */
    val abLoop: StateFlow<AbLoop?> = _abLoop

    private val _queue = MutableStateFlow(QueueUiState())

    /** The queue as the player holds it, for the Now Playing queue tab. */
    val queue: StateFlow<QueueUiState> = _queue

    // Volume has two independent owners - the sleep timer's fade-out and the
    // play/pause/skip fade - and multiplying them is what keeps the two from
    // overwriting each other's ramp.
    @Volatile
    private var sleepVolume: Float = 1f

    @Volatile
    private var fadeVolume: Float = 1f

    private var fadeJob: Job? = null

    /** 0 = off, 1 = random, 2 = intelligent. */
    private val _autoMode = MutableStateFlow(0)
    val autoMode: StateFlow<Int> = _autoMode

    /**
     * Kept so [onCleared] can unregister it.
     *
     * It never needed unregistering while this class released the player it was
     * attached to - the listener died with it. The player outlives the screen
     * now, so a listener left on it would keep a dead ViewModel alive for as
     * long as the music plays, and would go on writing that ViewModel's history
     * and analysis state alongside the live one's.
     */
    private var playerListener: Player.Listener? = null

    init {
        engine.start(viewModelScope)
        refreshNumericTitles()
        refreshTakes()
        // Everything startup reads off disk that is not needed to draw the
        // first frame. See each function for what it costs and why waiting for
        // it shows nothing wrong in the meantime.
        refreshPresets()
        refreshLibrary()
        refreshTextures()
        // Restore persisted playback options onto the player. Auto-resume runs
        // BEFORE the listener registers so the startup preparation never
        // records a phantom play into history (ExoPlayer only delivers events
        // to listeners registered when they occurred).
        val pp = _playerPrefs.value
        player.shuffleModeEnabled = pp.shuffle
        player.repeatMode = pp.repeatMode
        applyPlaybackPrefs(pp)
        // The player is no longer necessarily new: it survives the screen, so a
        // second screen can open onto music that is already playing. Loading
        // the last-played track over that would throw away what the user is
        // listening to, so this branch adopts the queue that is there instead -
        // and seeds the fields the transition event would otherwise have set,
        // since that event happened before this ViewModel existed.
        val alreadyLoaded = player.currentMediaItem != null
        if (alreadyLoaded) {
            currentUri = player.currentMediaItem?.localConfiguration?.uri
        } else if (pp.autoResume) {
            prepareLastPlayed()
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    refresh()
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                        currentUri = player.currentMediaItem?.localConfiguration?.uri
                        currentUri?.let { u ->
                            val title =
                                player.mediaMetadata.title?.toString()
                                    ?: player.currentMediaItem
                                        ?.localConfiguration
                                        ?.uri
                                        ?.lastPathSegment
                                        .orEmpty()
                            // The old track's accumulated time belongs to the
                            // old track: bank it before the uri moves on.
                            flushListenTime()
                            historyStore.recordPlay(
                                u.toString(),
                                title,
                                player.mediaMetadata.artist
                                    ?.toString()
                                    .orEmpty(),
                            )
                            _historyTick.update { it + 1 }
                        }
                        onTrackChanged()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    // A seek breaks the audio stream's continuity just as a
                    // track change does: the tracker's predicted beat frames
                    // now point at music that will not arrive, so it would
                    // suppress the real beats at the new position as off-grid
                    // until it re-locked. Covers every seek path (transport
                    // bar, gestures, any future notification controls), which
                    // is why this hangs off the listener and not seekTo().
                    // Auto-advance discontinuities are left to
                    // EVENT_MEDIA_ITEM_TRANSITION, which resets anyway.
                    if (reason == Player.DISCONTINUITY_REASON_SEEK ||
                        reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
                    ) {
                        engine.reset()
                    }
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    // The audiofx chain must follow the sink's session; attach
                    // rebuilds the effects and restores persisted settings.
                    audioFxController.attach(audioSessionId)
                    refreshAudioFx()
                }

                /**
                 * Everything that has to be true whenever playback starts, no
                 * matter who started it.
                 *
                 * This used to be safe to do inside [togglePlayPause], because
                 * that button was the only way to start. It is not any more:
                 * the notification, the lock screen and a headset button all
                 * drive the player straight through the MediaSession without
                 * passing through this class at all. Hanging the rules off the
                 * player is the same reasoning as the seek reset above - the
                 * player is where every path meets.
                 */
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isPlaying) return
                    // One ring buffer, one source. A track and the room (or a
                    // track and Spotify) summed into a single spectrum drive
                    // the visuals as neither.
                    if (micCapture.active) setMicEnabled(false)
                    if (_externalAudio.value.active) stopExternalAudio()
                    // A faded pause leaves the output at zero and waits for the
                    // matching fade in. A transport that is not ours knows
                    // nothing about that, so its play would have been silent.
                    if (fadeVolume < 1f && fadeJob?.isActive != true) fadeThen(fadeVolume, 1f) {}
                    // From here on the music must survive this screen.
                    PlaybackService.ensureRunning(getApplication())
                }
            }
        playerListener = listener
        player.addListener(listener)
        // The sink may already have a session id (attach ignores UNSET = 0).
        audioFxController.attach(player.audioSessionId)
        refreshAudioFx()
        // A screen opening onto music that is already playing has missed the
        // track change that started it, and with it the lyrics, the cached
        // analysis and the section grid for what it is now showing.
        if (alreadyLoaded) onTrackChanged()
        // Consent -> foreground service -> projection -> recorder. This is the
        // last hop: the service publishes what the user granted, and the
        // recorder opens against it here, where the ring buffer lives.
        viewModelScope.launch {
            dev.musicviz.audio.MediaProjectionHolder.projection
                .collect { projection ->
                    if (projection != null) {
                        startPlaybackCapture(projection)
                    } else if (_externalAudio.value.active) {
                        // Revoked from the system UI, or the service died.
                        playbackCapture.stop()
                        engine.reset()
                        engine.sampleRateHz = tapSampleRateHz.takeIf { it > 0 } ?: 44100
                        _externalAudio.update { it.copy(active = false, refusedByApp = false) }
                    }
                }
        }
        viewModelScope.launch {
            while (true) {
                refresh()
                accrueListenTime()
                enforceAbLoop()
                refreshQueue()
                refreshExternalAudio()
                refreshMicState()
                applyIntelligence()
                advanceVizPlaylist()
                advanceRandomMode()
                advanceSectionStaging()
                delay(500)
            }
        }
    }

    private fun refresh() {
        _uiState.value =
            PlayerUiState(
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = player.duration.coerceAtLeast(0),
                artist = player.mediaMetadata.artist?.toString(),
                title =
                    player.mediaMetadata.title?.toString()
                        ?: player.currentMediaItem
                            ?.localConfiguration
                            ?.uri
                            ?.lastPathSegment
                            ?.substringAfterLast('/')
                            ?.substringBeforeLast('.'),
                hasMedia = player.currentMediaItem != null,
                queueSize = player.mediaItemCount,
                queueIndex = player.currentMediaItemIndex,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
            )
    }

    // ---- Player options ----

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        persistPlayerOptions()
        refresh()
    }

    fun cycleRepeatMode() {
        player.repeatMode =
            when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        persistPlayerOptions()
        refresh()
    }

    /**
     * Auto-resume: prepares (without playing) the most recent history entry
     * so the mini-player and the Home resume card can continue it with one
     * tap. Prepare-only by design - the existing Resume card stays the UI.
     */
    private fun prepareLastPlayed() {
        val last = historyStore.recentlyPlayed(1).firstOrNull() ?: return
        runCatching {
            val uri = Uri.parse(last.uri)
            player.setMediaItems(listOf(mediaItemFor(uri)))
            player.prepare()
            currentUri = uri
        }
    }

    // ---- Sleep timer ----

    private var sleepTimerJob: Job? = null
    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)

    /** Remaining sleep-timer time, or null when no timer is running. */
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs

    /**
     * Starts (or restarts) the sleep timer: counts down, fades the volume
     * over the final 3 s, pauses, then restores full volume for next play.
     * Persists [minutes] as the last-chosen duration (never a running state).
     */
    fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        setPlayerPrefs(_playerPrefs.value.copy(sleepTimerMinutes = minutes))
        sleepTimerJob?.cancel()
        sleepTimerJob =
            viewModelScope.launch {
                val endMs = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
                while (true) {
                    val remaining = endMs - android.os.SystemClock.elapsedRealtime()
                    if (remaining <= 0L) break
                    _sleepTimerRemainingMs.value = remaining
                    sleepVolume = PlaybackMath.sleepFadeVolume(remaining)
                    applyVolume()
                    delay(if (remaining <= PlaybackMath.SLEEP_FADE_MS) 100 else 500)
                }
                // "Finish this track" waits out whatever is playing when the
                // clock runs down, so a timer set mid-song does not cut it off
                // thirty seconds from the end.
                if (_playerPrefs.value.sleepFinishTrack) {
                    sleepVolume = 1f
                    applyVolume()
                    while (player.isPlaying) delay(500)
                }
                player.pause()
                sleepVolume = 1f
                applyVolume()
                _sleepTimerRemainingMs.value = null
                sleepTimerJob = null
            }
    }

    /** Cancels a running sleep timer and restores full volume. */
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerRemainingMs.value = null
        sleepVolume = 1f
        applyVolume()
    }

    // ---- Visual playlist ----

    private val _vizApply = MutableSharedFlow<VizApply>(extraBufferCapacity = 8)

    /** Renderer side effects (milk preset loads, custom shaders) to apply. */
    val vizApply: SharedFlow<VizApply> = _vizApply

    private val _morphFade = MutableSharedFlow<Float>(extraBufferCapacity = 4)

    /** One-shot preset-morph fade (seconds) for the renderer; never persisted. */
    val morphFade: SharedFlow<Float> = _morphFade

    private var lastVizSwitchMs = 0L
    private var vizPlaylistIndex = 0

    fun addToVizPlaylist(entry: VizPlaylistEntry) {
        val s = _vizState.value
        _vizState.value = s.copy(vizPlaylist = s.vizPlaylist + entry)
    }

    fun removeVizPlaylistAt(index: Int) {
        val s = _vizState.value
        if (index in s.vizPlaylist.indices) {
            _vizState.value = s.copy(vizPlaylist = s.vizPlaylist.filterIndexed { i, _ -> i != index })
        }
    }

    fun setVizPlaylistEnabled(enabled: Boolean) {
        _vizState.value =
            _vizState.value.copy(
                vizPlaylistEnabled = enabled,
                randomEnabled = if (enabled) false else _vizState.value.randomEnabled,
            )
        lastVizSwitchMs = android.os.SystemClock.elapsedRealtime()
    }

    fun setVizPlaylistIntelligent(enabled: Boolean) {
        _vizState.update { it.copy(vizPlaylistIntelligent = enabled) }
    }

    fun setVizPlaylistInterval(seconds: Int) {
        _vizState.update { it.copy(vizPlaylistIntervalSec = seconds.coerceIn(5, 300)) }
    }

    /**
     * Applies user GLSL to the current shader scene: stored in state (so
     * presets capture it) and emitted through vizApply so the shell-level
     * engine bindings push it to the renderer from ANY screen - the GLSL
     * editor no longer depends on the deleted expanded-screen plumbing.
     */
    fun applyCustomShader(source: String) {
        val sceneId = _vizState.value.sceneId
        _vizState.update { it.copy(shaderError = null) }
        _vizApply.tryEmit(VizApply(customShader = source, sceneId = sceneId))
    }

    fun setTransitionStyle(style: TransitionStyle) {
        _vizState.update { it.copy(transitionStyle = style, transitionId = style.name.lowercase()) }
    }

    /**
     * Picks a transition by id - a built-in style name or a gl-transitions
     * corpus name. Keeps [VizState.transitionStyle] in step for the built-ins
     * so the two never disagree about which one is selected.
     */
    fun setTransitionId(id: String) {
        _vizState.update {
            it.copy(
                transitionId = id,
                transitionStyle = dev.musicviz.render.TransitionCatalog.builtIn(id) ?: it.transitionStyle,
            )
        }
    }

    fun setTransitionDuration(seconds: Float) {
        _vizState.update { it.copy(transitionDurationSec = seconds.coerceIn(0.3f, 5f)) }
    }

    private fun advanceVizPlaylist() {
        val s = _vizState.value
        if (!s.vizPlaylistEnabled || s.vizPlaylist.size < 2 || !_uiState.value.isPlaying) return
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = now - lastVizSwitchMs
        val intervalMs = s.vizPlaylistIntervalSec * 1000L
        val due =
            if (s.vizPlaylistIntelligent) {
                // Intelligent: after a minimum dwell, switch on a strong
                // musical moment; force a switch at 2x interval so quiet
                // passages still rotate. "Strong" is the tracker's graded beat
                // impulse, which is TRACK-RELATIVE (it folds in the macro-
                // energy envelope) - the absolute rms gate this replaced never
                // opened on a quietly mastered track, so intelligent mode
                // silently degraded into the plain 2x-interval timer there.
                val f = engine.features.value
                val minDwell = maxOf(8_000L, intervalMs / 2)
                (elapsed >= minDwell && f.beatImpulse >= STRONG_MOMENT_IMPULSE) || elapsed >= intervalMs * 2
            } else {
                elapsed >= intervalMs
            }
        if (!due) return
        lastVizSwitchMs = now
        vizPlaylistIndex = (vizPlaylistIndex + 1) % s.vizPlaylist.size
        applyVizEntry(s.vizPlaylist[vizPlaylistIndex])
    }

    // ---- Random mode ----

    private var lastRandomSwitchMs = 0L
    private val randomRng = kotlin.random.Random(android.os.SystemClock.elapsedRealtime())

    /** Cached .milk files so random picks don't touch disk on the tick loop. */
    private var cachedMilkFiles: List<MilkFile> = emptyList()

    fun setRandomEnabled(enabled: Boolean) {
        _vizState.value =
            _vizState.value.copy(
                randomEnabled = enabled,
                vizPlaylistEnabled = if (enabled) false else _vizState.value.vizPlaylistEnabled,
            )
        lastRandomSwitchMs = android.os.SystemClock.elapsedRealtime()
        if (enabled && _vizState.value.randomIncludeMilk) refreshMilkCache()
        if (enabled) randomStepNow()
    }

    fun setRandomInterval(seconds: Int) {
        _vizState.update { it.copy(randomIntervalSec = seconds.coerceIn(5, 300)) }
    }

    fun setRandomOnBeat(enabled: Boolean) {
        _vizState.update { it.copy(randomOnBeat = enabled) }
    }

    fun setRandomIncludeStyles(enabled: Boolean) {
        _vizState.update { it.copy(randomIncludeStyles = enabled) }
    }

    fun setRandomIncludePresets(enabled: Boolean) {
        _vizState.update { it.copy(randomIncludePresets = enabled) }
    }

    fun setRandomIncludeMilk(enabled: Boolean) {
        _vizState.update { it.copy(randomIncludeMilk = enabled) }
        if (enabled) refreshMilkCache()
    }

    fun setRandomizeColors(enabled: Boolean) {
        _vizState.update { it.copy(randomizeColors = enabled) }
    }

    private fun refreshMilkCache() {
        milkPresetFilesAsync { cachedMilkFiles = it }
    }

    /** Section the playhead is inside, from the offline analysis boundaries. */
    private fun currentSectionIndex(): Int {
        val sections = _vizState.value.sections
        if (sections.isEmpty()) return 0
        val pos = _uiState.value.positionMs
        var idx = 0
        for (boundary in sections) {
            if (boundary <= pos) idx++ else break
        }
        return idx
    }

    /** Section last staged, so a look is applied once per section, not per tick. */
    private var lastStagedSection = -1

    /**
     * Applies a look when the playhead crosses into a new section.
     *
     * Deterministic by section INDEX rather than "next in the list": the point
     * is that a chorus looks like the chorus every time, so the third section
     * of a track must get the same look on every play - and on the export.
     * Falls back to the current style's presets when no visual playlist has
     * been built, so the mode works without any setup at all.
     */
    private fun advanceSectionStaging() {
        val s = _vizState.value
        if (!s.sectionStaging || !_uiState.value.isPlaying) return
        val index = currentSectionIndex()
        if (index == lastStagedSection) return
        lastStagedSection = index
        if (s.vizPlaylist.isNotEmpty()) {
            applyVizEntry(s.vizPlaylist[index % s.vizPlaylist.size])
            return
        }
        val pool = s.presets.filter { it.sceneId == s.sceneId }
        if (pool.isNotEmpty()) applyPreset(pool[index % pool.size])
    }

    /**
     * Turns section staging on or off.
     *
     * Switching it on kicks off the offline analysis when it has not run:
     * sections come from that pass, and a mode whose input is missing would
     * otherwise just sit there doing nothing with no way to tell why.
     */
    fun setSectionStaging(enabled: Boolean) {
        _vizState.update { it.copy(sectionStaging = enabled) }
        lastStagedSection = -1
        if (enabled && _vizState.value.sections.isEmpty()) analyzeCurrentTrack()
    }

    private fun advanceRandomMode() {
        val s = _vizState.value
        if (!s.randomEnabled || !_uiState.value.isPlaying) return
        val now = android.os.SystemClock.elapsedRealtime()
        val elapsed = now - lastRandomSwitchMs
        val intervalMs = s.randomIntervalSec * 1000L
        val due =
            if (s.randomOnBeat) {
                // Switch on a strong musical moment after a minimum dwell;
                // force a switch at 2x interval so quiet passages still move.
                // Graded and track-relative, as in advanceVizPlaylist().
                val f = engine.features.value
                val minDwell = maxOf(6_000L, intervalMs / 2)
                (elapsed >= minDwell && f.beatImpulse >= STRONG_MOMENT_IMPULSE) || elapsed >= intervalMs * 2
            } else {
                elapsed >= intervalMs
            }
        if (!due) return
        randomStepNow()
    }

    /** Jumps to a random style/preset immediately (also used on enable). */
    fun randomStepNow() {
        if (_presetLocked.value) return
        val s = _vizState.value
        lastRandomSwitchMs = android.os.SystemClock.elapsedRealtime()
        val choices = mutableListOf<VizPlaylistEntry>()
        val sceneIds =
            dev.musicviz.render.VisualizerRenderer.PARTICLE_SCENES +
                dev.musicviz.render.VisualizerRenderer.SHADER_SCENES.keys
        if (s.randomIncludeStyles) sceneIds.forEach { choices += VizPlaylistEntry(sceneId = it, label = it) }
        if (s.randomIncludePresets) {
            s.presets.forEach { choices += VizPlaylistEntry(sceneId = it.sceneId, presetName = it.name, label = it.name) }
        }
        if (s.randomIncludeMilk && dev.musicviz.render.scene.PMBridge.available) {
            cachedMilkFiles.forEach {
                choices += VizPlaylistEntry(sceneId = SceneIds.MILKDROP, milkPath = it.path, label = it.name)
            }
        }
        if (choices.isEmpty()) return
        var pick = choices[randomRng.nextInt(choices.size)]
        // One retry to avoid landing on the scene already showing.
        if (choices.size > 1 && pick.sceneId == s.sceneId && pick.presetName == null && pick.milkPath == null) {
            pick = choices[randomRng.nextInt(choices.size)]
        }
        applyVizEntry(pick)
        if (s.randomizeColors) {
            // The roll is drawn out here, once: update re-runs its block on a
            // losing compare-and-set, and drawing inside it would give the
            // retry different colours from the ones this step decided on.
            val palette = randomRng.nextInt(SceneParams.PALETTES.size)
            val palette2 = randomRng.nextInt(SceneParams.PALETTES.size)
            val paletteMix = if (randomRng.nextBoolean()) randomRng.nextFloat() * 0.6f else 0f
            val colorShift = randomRng.nextFloat()
            _vizState.update { cur ->
                val rolled =
                    cur.params.copy(
                        palette = palette,
                        palette2 = palette2,
                        paletteMix = paletteMix,
                        colorShift = colorShift,
                    )
                // A custom-palette override outranks the PALETTES lookup, so the
                // new indices stay invisible unless both slots are cleared too.
                cur.copy(params = PaletteStore.clear(PaletteStore.clear(rolled), second = true))
            }
        }
    }

    /** Applies a playlist entry: scene, saved preset params and side effects.
     *  The preset's custom shader (if any) is emitted by [applyPreset]. */
    fun applyVizEntry(entry: VizPlaylistEntry) {
        selectScene(entry.sceneId)
        if (entry.presetName != null) {
            _vizState.value.presets
                .firstOrNull { it.name == entry.presetName && it.sceneId == entry.sceneId }
                ?.let { applyPreset(it) }
        }
        if (entry.milkPath != null) {
            _vizApply.tryEmit(VizApply(milkPath = entry.milkPath, sceneId = entry.sceneId))
        }
    }

    // ---- Music library & playlists ----

    /**
     * Embedded-tag metadata read from a file; fields blank/zero when absent.
     * [fileName]/[sizeBytes] are not tags but the provider's view of the file
     * itself, carried here because they are what identifies it in the library.
     */
    private data class FileMeta(
        val title: String,
        val artist: String = "",
        val album: String = "",
        val genre: String = "",
        val year: Int = 0,
        val trackNo: Int = 0,
        val fileName: String = "",
        val sizeBytes: Long = 0L,
    )

    private val _deviceTracks = MutableStateFlow<List<DeviceTrack>>(emptyList())

    /** Device music index (MediaStore); refreshed on demand from the UI. */
    val deviceTracks: StateFlow<List<DeviceTrack>> = _deviceTracks

    /**
     * Re-queries the MediaStore device index on IO. Safe to call from any
     * screen: without the audio permission it just publishes an empty list.
     * (The query used to run synchronously inside LibraryScreen composition,
     * janking the first frame of the Library tab on large collections.)
     */
    fun refreshDeviceTracks() {
        viewModelScope.launch(Dispatchers.IO) {
            _deviceTracks.value = queryDeviceTracksBlocking()
        }
    }

    /** Full MediaStore music query; call on Dispatchers.IO. */
    private fun queryDeviceTracksBlocking(): List<DeviceTrack> {
        val app = getApplication<Application>()
        val permission =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.READ_MEDIA_AUDIO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
        val granted =
            androidx.core.content.ContextCompat
                .checkSelfPermission(app, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyList()
        val out = mutableListOf<DeviceTrack>()
        val proj =
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.DATE_ADDED,
            )
        runCatching {
            app.contentResolver
                .query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    proj,
                    "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                    null,
                    "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
                )?.use { c ->
                    val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val ti = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val ar = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val al = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val du = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val da = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                    val ad = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(id))
                        val path = c.getString(da).orEmpty()
                        out +=
                            DeviceTrack(
                                uri = uri.toString(),
                                title = c.getString(ti) ?: "Unknown",
                                artist = c.getString(ar) ?: "Unknown artist",
                                album = c.getString(al) ?: "Unknown album",
                                folder = path.substringBeforeLast('/', ""),
                                durationMs = c.getLong(du),
                                addedSec = c.getLong(ad),
                            )
                    }
                }
        }
        return out
    }

    /**
     * Resolves tag metadata the way real media players do: embedded tags
     * first (MediaMetadataRetriever), then the provider's display name, and
     * only then the URI path - so content URIs never surface as bare
     * document numbers. Call on Dispatchers.IO; the retriever hits disk.
     */
    private fun metadataFor(uri: Uri): FileMeta {
        val app = getApplication<Application>()
        var title: String? = null
        var artist: String? = null
        var album = ""
        var genre = ""
        var year = 0
        var trackNo = 0
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(app, uri)

                fun tag(key: Int): String? = r.extractMetadata(key)?.trim()?.ifBlank { null }
                title = tag(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = tag(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                album = tag(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                genre = tag(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
                // Year tags arrive as "1997" or full dates; track numbers as "3" or "3/12".
                year =
                    tag(android.media.MediaMetadataRetriever.METADATA_KEY_YEAR)
                        ?.filter { it.isDigit() }
                        ?.take(4)
                        ?.toIntOrNull() ?: 0
                trackNo =
                    tag(android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                        ?.substringBefore('/')
                        ?.trim()
                        ?.toIntOrNull() ?: 0
            } finally {
                runCatching { r.release() }
            }
        }
        val openable = openableInfoFor(uri)
        if (title == null) title = openable.first.ifBlank { null }?.substringBeforeLast('.')
        return FileMeta(
            title = title ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Track",
            artist = artist ?: "",
            album = album,
            genre = genre,
            year = year,
            trackNo = trackNo,
            fileName = openable.first,
            sizeBytes = openable.second,
        )
    }

    /**
     * The provider's display name and byte size for [uri], blank/zero when it
     * reports neither. This is the library's dedup identity (see
     * [LibraryTrack.fileName]), so it is queried for every file rather than
     * only as a title fallback: SAF and MediaStore hand out different uris
     * for the same file but the same DISPLAY_NAME/SIZE. One cursor next to
     * the retriever above, whose disk I/O dwarfs it.
     */
    private fun openableInfoFor(uri: Uri): Pair<String, Long> =
        runCatching {
            val cols = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
            getApplication<Application>()
                .contentResolver
                .query(uri, cols, null, null, null)
                ?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    val name = if (ni >= 0 && !c.isNull(ni)) c.getString(ni).orEmpty() else ""
                    val size = if (si >= 0 && !c.isNull(si)) c.getLong(si) else 0L
                    name to size
                }
        }.getOrNull() ?: ("" to 0L)

    private fun libraryTrackFor(
        uriStr: String,
        m: FileMeta,
    ): LibraryTrack =
        LibraryTrack(
            uri = uriStr,
            title = m.title,
            artist = m.artist,
            album = m.album,
            genre = m.genre,
            year = m.year,
            trackNo = m.trackNo,
            fileName = m.fileName,
            sizeBytes = m.sizeBytes,
        )

    private fun titleFor(uri: Uri): String = metadataFor(uri).title

    /** Imports picked audio files into the library (persist read permission first). */
    fun importTracks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // titleFor() runs a content-resolver metadata query per file; a large
        // multi-select would jank/ANR the main thread, so do it on IO.
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val tracks =
                uris.map { uri ->
                    runCatching {
                        app.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    libraryTrackFor(uri.toString(), metadataFor(uri))
                }
            // A null result means the store was unreadable and nothing was
            // written, so leave the on-screen list exactly as it is rather
            // than publishing a list that does not reflect the disk.
            trackLibrary.addAll(tracks)?.let { merged -> _library.update { it.copy(tracks = merged) } }
        }
    }

    /** The stored library/override entry for [uri], if any (imported or user-edited). */
    fun trackOverride(uri: String): LibraryTrack? = _library.value.tracks.firstOrNull { it.uri == uri }

    /**
     * Track-info-editor prefill: the stored override when one exists, else
     * the file's embedded tags (retriever runs on IO).
     */
    suspend fun trackInfoFor(uriStr: String): LibraryTrack =
        trackOverride(uriStr) ?: withContext(Dispatchers.IO) {
            libraryTrackFor(uriStr, metadataFor(Uri.parse(uriStr)))
        }

    /**
     * Saves user-edited track info into the app-side store. Upserts, so it
     * works for MediaStore tracks that were never imported; the audio file
     * itself is never modified. Publishing through [_library] (and thus
     * [trackOverrides]) is what refreshes every observing screen.
     */
    fun saveTrackInfo(
        uri: String,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int,
        trackNo: Int,
        comment: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val merged = trackLibrary.updateMetadata(uri, title, artist, album, genre, year, trackNo, comment)
            merged?.let { withContext(Dispatchers.Main) { _library.update { s -> s.copy(tracks = it) } } }
        }
    }

    /**
     * Analysis with the persistent cache: a hit skips the whole offline
     * pass (the dominant cost of export). Call on Dispatchers.IO.
     *
     * Both paths get the user's beat sensitivity: the analyzer runs its gate
     * with it, and a cache hit re-decides the beats from the stored onset
     * curve. So the cached beat grid always matches what the live engine is
     * flashing on, for exports as well as the intelligence modes.
     */
    private suspend fun analyzeCached(
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): dev.musicviz.analysis.FeatureTimeline {
        val app = getApplication<Application>()
        val gui = _guiPrefs.value
        dev.musicviz.analysis.AnalysisCache
            .load(app, uri, gui.beatThresholdSigma, gui.effectiveBeatMinIntervalMs)
            ?.let {
                onProgress(1f)
                return it
            }
        return offlineAnalyzer
            .analyze(uri, gui.beatThresholdSigma, gui.effectiveBeatMinIntervalMs, onProgress)
            .also {
                dev.musicviz.analysis.AnalysisCache
                    .save(app, uri, it)
            }
    }

    private fun libraryPrefs(): android.content.SharedPreferences =
        getApplication<Application>().getSharedPreferences("musicviz-library", android.content.Context.MODE_PRIVATE)

    private val _mediaRoots =
        MutableStateFlow<Set<String>>(libraryPrefs().getStringSet("roots", emptySet()) ?: emptySet())

    /** Persistent library folders (SAF tree URIs); rescanned on demand. */
    val mediaRoots: StateFlow<Set<String>> = _mediaRoots

    private val _libraryScanning = MutableStateFlow(false)
    val libraryScanning: StateFlow<Boolean> = _libraryScanning

    fun importFolder(treeUri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        _mediaRoots.update { it + treeUri.toString() }
        libraryPrefs().edit().putStringSet("roots", _mediaRoots.value).apply()
        viewModelScope.launch(Dispatchers.IO) {
            _libraryScanning.value = true
            try {
                scanTreeBlocking(treeUri)
            } finally {
                _libraryScanning.value = false
            }
        }
    }

    fun removeMediaRoot(uriStr: String) {
        _mediaRoots.update { it - uriStr }
        libraryPrefs().edit().putStringSet("roots", _mediaRoots.value).apply()
    }

    /** Re-walks every registered folder; existing entries keep their analysis. */
    fun rescanMediaRoots() {
        if (_libraryScanning.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _libraryScanning.value = true
            try {
                for (root in _mediaRoots.value) {
                    scanTreeBlocking(Uri.parse(root))
                }
            } finally {
                _libraryScanning.value = false
            }
        }
    }

    /** Recursive SAF walk (VLC-mirror: full tree, hidden dirs skipped). */
    private suspend fun scanTreeBlocking(treeUri: Uri) {
        val app = getApplication<Application>()
        val found = mutableListOf<LibraryTrack>()
        runCatching {
            val root =
                androidx.documentfile.provider.DocumentFile
                    .fromTreeUri(app, treeUri) ?: return@runCatching

            fun walk(
                dir: androidx.documentfile.provider.DocumentFile,
                depth: Int,
            ) {
                if (depth > 8) return
                dir.listFiles().forEach { f ->
                    val name = f.name ?: return@forEach
                    if (name.startsWith(".")) return@forEach
                    if (f.isDirectory) {
                        walk(f, depth + 1)
                    } else {
                        val isAudio =
                            f.type?.startsWith("audio/") == true ||
                                name.substringAfterLast('.', "").lowercase() in AUDIO_EXTS
                        if (isAudio) {
                            found += libraryTrackFor(f.uri.toString(), metadataFor(f.uri))
                        }
                    }
                }
            }
            walk(root, 0)
        }
        if (found.isNotEmpty()) {
            val merged = trackLibrary.addAll(found)
            merged?.let { withContext(Dispatchers.Main) { _library.update { s -> s.copy(tracks = it) } } }
        }
    }

    fun removeFromLibrary(uri: String) {
        trackLibrary.remove(uri)?.let { merged -> _library.update { it.copy(tracks = merged) } }
    }

    fun createMusicPlaylist(name: String) {
        if (name.isBlank()) return
        musicPlaylists.save(MusicPlaylist(name.trim()))
        _library.update { it.copy(playlists = musicPlaylists.list()) }
    }

    fun renameMusicPlaylist(
        oldName: String,
        newName: String,
    ) {
        if (musicPlaylists.rename(oldName, newName.trim())) {
            _library.update { it.copy(playlists = musicPlaylists.list()) }
        }
    }

    fun moveMusicPlaylistTrack(
        name: String,
        from: Int,
        to: Int,
    ) {
        musicPlaylists.move(name, from, to)
        _library.update { it.copy(playlists = musicPlaylists.list()) }
    }

    fun deleteMusicPlaylist(name: String) {
        musicPlaylists.delete(name)
        _library.update { it.copy(playlists = musicPlaylists.list()) }
    }

    fun addTrackToPlaylist(
        playlist: String,
        uri: String,
    ) {
        musicPlaylists.addTrack(playlist, uri)
        _library.update { it.copy(playlists = musicPlaylists.list()) }
    }

    fun removeTrackFromPlaylist(
        playlist: String,
        uri: String,
    ) {
        musicPlaylists.removeTrack(playlist, uri)
        _library.update { it.copy(playlists = musicPlaylists.list()) }
    }

    /** Resolves a playlist's track uris to library entries, preserving order. */
    fun playlistTracks(playlist: String): List<LibraryTrack> {
        val byUri = _library.value.tracks.associateBy { it.uri }
        val names =
            _library.value.playlists
                .firstOrNull { it.name == playlist }
                ?.trackUris
                .orEmpty()
        return names.map { uri -> byUri[uri] ?: LibraryTrack(uri = uri, title = titleFor(Uri.parse(uri))) }
    }

    /** Plays a music playlist from the given start index. */
    fun playPlaylist(
        playlist: String,
        startIndex: Int = 0,
    ) {
        val uris =
            _library.value.playlists
                .firstOrNull { it.name == playlist }
                ?.trackUris
                .orEmpty()
        if (uris.isEmpty()) return
        // Through the same funnel as every other list, so a later single-track
        // play of one of these rejoins the playlist instead of truncating the
        // queue to it (and so the titles come from the library, not a query).
        val byUri = _library.value.tracks.associateBy { it.uri }
        val tracks = uris.map { u -> byUri[u]?.let(PlaybackQueue::queueTrack) ?: QueueTrack(u) }
        playFrom(tracks, uris[startIndex.coerceIn(0, uris.size - 1)])
    }

    // ---- Navigation v2 additions ----

    fun recentlyPlayed() = historyStore.recentlyPlayed()

    fun mostPlayed() = historyStore.mostPlayed()

    /** Uri of whatever is loaded in the player, for artwork lookups. */
    fun currentTrackUri(): String? = currentUri?.toString()

    // ---- Player: favourites, waveform, lyrics, A-B loop, queue, fades ----

    /** Marks or unmarks the playing track. No-op with nothing loaded. */
    fun toggleFavourite(uri: String? = currentUri?.toString()) {
        val target = uri ?: return
        favouritesStore.toggle(target)
        _favourites.value = favouritesStore.all().toSet()
        _historyTick.update { it + 1 }
    }

    /**
     * Peak-per-bucket rather than mean.
     *
     * A mean of ~60 frames per bucket flattens a track into a low grey ridge -
     * the quiet parts pull every bucket down. Peak keeps the shape a person
     * recognises as their song, which is the entire reason to draw it.
     */
    private fun waveformOf(timeline: FeatureTimeline): FloatArray? {
        val frames = timeline.frames
        if (frames.size < WAVEFORM_BUCKETS) return null
        val out = FloatArray(WAVEFORM_BUCKETS)
        var peak = 0f
        for (b in 0 until WAVEFORM_BUCKETS) {
            val from = b * frames.size / WAVEFORM_BUCKETS
            val to = ((b + 1) * frames.size / WAVEFORM_BUCKETS).coerceAtMost(frames.size)
            var max = 0f
            for (i in from until to) {
                val v = frames[i].features.rms
                if (v > max) max = v
            }
            out[b] = max
            if (max > peak) peak = max
        }
        if (peak <= 0f) return null
        for (i in out.indices) out[i] = out[i] / peak
        return out
    }

    private fun loadLyricsFor(uri: Uri?) {
        _lyrics.value = null
        if (uri == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val found = LyricsLoader.load(getApplication(), uri)
            // A track change while the file was being read means these words
            // belong to a track that is no longer playing.
            withContext(Dispatchers.Main) { if (currentUri == uri) _lyrics.value = found }
        }
    }

    /** An A-B loop: [startMs] set, [endMs] null until the second tap. */
    data class AbLoop(
        val startMs: Long,
        val endMs: Long? = null,
    ) {
        val armed: Boolean get() = endMs != null
    }

    /**
     * One button, three states: tap to drop A, tap again to drop B and start
     * looping, tap a third time to clear.
     *
     * A B that lands before its A is treated as the user re-marking A rather
     * than as an error - they seeked backwards and tapped, and a loop of
     * negative length is not what anyone meant.
     */
    fun cycleAbLoop() {
        val at = player.currentPosition.coerceAtLeast(0)
        val loop = _abLoop.value
        _abLoop.value =
            when {
                loop == null -> AbLoop(at)
                loop.endMs == null && at > loop.startMs + MIN_LOOP_MS -> loop.copy(endMs = at)
                loop.endMs == null -> AbLoop(at)
                else -> null
            }
    }

    fun clearAbLoop() {
        _abLoop.value = null
    }

    /** Sends playback back to A when it runs past B. Called from the poll. */
    private fun enforceAbLoop() {
        val loop = _abLoop.value ?: return
        val end = loop.endMs ?: return
        if (player.currentPosition >= end) player.seekTo(loop.startMs)
    }

    private fun refreshQueue() {
        val tracks =
            (0 until player.mediaItemCount).map { i ->
                val item = player.getMediaItemAt(i)
                QueueTrack(
                    uri = item.localConfiguration?.uri?.toString().orEmpty(),
                    title =
                        item.mediaMetadata.title?.toString()
                            ?: item.localConfiguration
                                ?.uri
                                ?.lastPathSegment
                                ?.substringAfterLast('/')
                                ?.substringBeforeLast('.')
                            ?: "Track ${i + 1}",
                    artist =
                        item.mediaMetadata.artist
                            ?.toString()
                            .orEmpty(),
                )
            }
        val next = QueueUiState(tracks, player.currentMediaItemIndex)
        if (next != _queue.value) _queue.value = next
    }

    /** Drops one entry. Removing what is playing advances, as ExoPlayer does. */
    fun removeQueueItem(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        player.removeMediaItem(index)
        refreshQueue()
    }

    /** Drag-reorder in the queue tab. */
    fun moveQueueItem(
        from: Int,
        to: Int,
    ) {
        val count = player.mediaItemCount
        if (from !in 0 until count || to !in 0 until count || from == to) return
        player.moveMediaItem(from, to)
        refreshQueue()
    }

    private fun applyVolume() {
        player.volume = (sleepVolume * fadeVolume).coerceIn(0f, 1f)
    }

    /**
     * Ramps the output between [from] and [to] over the user's fade length,
     * then runs [then].
     *
     * Not a crossfade: one player cannot decode two tracks at once, and a
     * second ExoPlayer to overlap them would double the decode cost and give
     * the analyzer two streams to sum. This is the other half of what people
     * mean by crossfade - no hard edge on pause, resume or skip - and it is
     * honest about being that.
     */
    private fun fadeThen(
        from: Float,
        to: Float,
        then: () -> Unit,
    ) {
        val durationMs = _playerPrefs.value.fadeMs
        fadeJob?.cancel()
        if (durationMs <= 0) {
            fadeVolume = to
            applyVolume()
            then()
            return
        }
        fadeJob =
            viewModelScope.launch {
                val steps = (durationMs / FADE_STEP_MS).coerceAtLeast(1)
                for (i in 0..steps) {
                    fadeVolume = from + (to - from) * (i.toFloat() / steps)
                    applyVolume()
                    delay(FADE_STEP_MS)
                }
                fadeVolume = to
                applyVolume()
                then()
                fadeJob = null
            }
    }

    /** Pause with a fade out; resume with a fade in. */
    fun togglePlayPauseFaded() {
        if (micCapture.active) setMicEnabled(false)
        if (player.isPlaying) {
            fadeThen(fadeVolume, 0f) { player.pause() }
        } else {
            fadeVolume = 0f
            applyVolume()
            // Armed before play(), not after. play() delivers onIsPlayingChanged
            // synchronously, and the repair there exists to rescue a resume that
            // came from the notification and left the output at zero - it has to
            // see a fade already running so it leaves this one alone.
            fadeThen(0f, 1f) {}
            player.play()
        }
    }

    /** Skips with a fade across the edit, so a manual skip is not a click. */
    private fun skipFaded(action: () -> Unit) {
        if (_playerPrefs.value.fadeMs <= 0 || !player.isPlaying) {
            action()
            return
        }
        fadeThen(fadeVolume, 0f) {
            action()
            fadeThen(0f, 1f) {}
        }
    }

    /**
     * Wall-clock of the last accrual tick, or 0 when not accruing. Playback
     * time is measured between ticks rather than from the player position so
     * a seek does not book the jump as listening.
     */
    private var listenTickAtMs: Long = 0L
    private var listenTickUri: String? = null

    /**
     * Books the time since the previous tick against the playing track. Called
     * from the 500 ms poll; the store batches writes itself.
     */
    private fun accrueListenTime() {
        val uri = currentUri?.toString()
        val now = System.currentTimeMillis()
        val playing = player.isPlaying && uri != null
        if (!playing || uri != listenTickUri) {
            // A pause, a stop or a track change ends the interval; the next
            // tick starts a fresh one rather than booking the gap.
            if (listenTickAtMs != 0L) historyStore.flush()
            listenTickAtMs = if (playing) now else 0L
            listenTickUri = if (playing) uri else null
            return
        }
        val delta = now - listenTickAtMs
        listenTickAtMs = now
        // A delta far larger than the poll interval means the process was
        // suspended, not that the user listened through it.
        if (delta in 1..MAX_LISTEN_TICK_MS) historyStore.addListenTime(uri, delta, now)
    }

    /** Writes any accumulated listening time. Cheap when there is none. */
    private fun flushListenTime() {
        accrueListenTime()
        historyStore.flush()
    }

    /**
     * Continues the most recently played track, preparing it if the player is
     * empty. Home's hero card when nothing is loaded.
     */
    fun resumeLastPlayed() {
        if (player.currentMediaItem != null) {
            player.play()
            return
        }
        val last = historyStore.recentlyPlayed(1).firstOrNull() ?: return
        playTrack(last.uri)
    }

    fun togglePresetLock() {
        _presetLocked.update { !it }
    }

    /**
     * Cycles the one auto-visuals control: off, random, smart, sections.
     *
     * The four are mutually exclusive by construction rather than by three
     * independent switches that can contradict each other - "rotate randomly"
     * and "hold a look for each section" are opposite instructions, and a UI
     * that lets both be on has to pick a winner somewhere the user cannot see.
     */
    fun cycleAutoMode() {
        val next = (_autoMode.value + 1) % 4
        _autoMode.value = next
        setRandomEnabled(next == 1)
        setIntelligenceMode(if (next == 2) IntelligenceMode.AUTO else IntelligenceMode.MANUAL)
        setSectionStaging(next == 3)
    }

    fun playNext(uri: String) {
        val at = QueueOps.insertNextIndex(player.currentMediaItemIndex, player.mediaItemCount)
        player.addMediaItem(at, mediaItemFor(Uri.parse(uri)))
        refresh()
    }

    fun enqueue(uri: String) {
        player.addMediaItem(mediaItemFor(Uri.parse(uri)))
        refresh()
    }

    fun shuffleAllHistory() {
        // History rows carry their own title, so this builds the queue without
        // a per-uri metadata lookup like every other list does.
        playAll(historyStore.recentlyPlayed(100).map { QueueTrack(it.uri, it.title) }, shuffled = true)
    }

    // Preset folder tree
    fun presetFolders(): List<String> = presetStore.folders()

    fun presetFolderOf(name: String): String = presetStore.folderOf(name)

    fun addPresetFolder(path: String) = presetStore.addFolder(path)

    fun renamePresetFolder(
        from: String,
        to: String,
    ) = presetStore.renameFolder(from, to)

    fun movePresetToFolder(
        name: String,
        folder: String,
    ) {
        presetStore.moveToFolder(name, folder)
        _vizState.update { it.copy(presets = BuiltInPresets.ALL + presetStore.list()) }
    }

    /** User .milk files (imports + saves), newest first. Built-ins removed. */
    fun userMilkPresets(): List<java.io.File> {
        val dir = java.io.File(getApplication<Application>().filesDir, "milk")
        return dir
            .listFiles { f -> f.isFile && f.extension == "milk" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun seekBy(deltaMs: Long) {
        val d = player.duration
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(if (d > 0) target.coerceAtMost(d) else target)
    }

    /** Swipe left/right in Now Playing: step through this scene's presets. */
    private var quickPresetIndex = -1

    fun nextQuickPreset() = stepQuickPreset(+1)

    fun prevQuickPreset() = stepQuickPreset(-1)

    private fun stepQuickPreset(dir: Int) {
        val s0 = _vizState.value
        val pool = s0.presets.filter { it.sceneId == s0.sceneId }
        if (pool.isEmpty()) return
        quickPresetIndex = (quickPresetIndex + dir).mod(pool.size)
        applyPreset(pool[quickPresetIndex])
    }

    /**
     * Plays [uri], with the list it belongs to as the queue.
     *
     * A tap on a row used to call `setMediaItems(listOf(one))`, which left the
     * player holding a ONE-item queue - so Next and Previous had nowhere to go
     * and did nothing unless a playlist had been started. That is the bug this
     * funnel fixes: [PlaybackQueue.contextFor] finds the list the track came
     * from (the screen's own ordering if it handed one over, otherwise the
     * device index or the imported library), so the transport behaves the way
     * it does in any other music player.
     */
    fun playTrack(uri: String) = playFrom(PlaybackQueue.contextFor(uri, lastBrowseContext, _deviceTracks.value, _library.value.tracks), uri)

    /**
     * Opens [tracks] as the queue and starts at [startUri]. The one path a
     * "tap a row" play takes; screens with an explicit ordering (an album, a
     * folder, search results) call it directly so Next follows what the user
     * is looking at.
     */
    fun playFrom(
        tracks: List<QueueTrack>,
        startUri: String,
    ) {
        val window = PlaybackQueue.window(tracks, startUri)
        if (window.tracks.isEmpty()) return
        // One ring buffer, one source: playing a track ends live input rather
        // than summing a song and the room into a single spectrum.
        if (micCapture.active) setMicEnabled(false)
        lastBrowseContext = tracks
        player.setMediaItems(window.tracks.map { mediaItemFor(it) })
        player.prepare()
        player.seekTo(window.startIndex, 0L)
        player.play()
        currentUri = Uri.parse(window.tracks[window.startIndex].uri)
        onTrackChanged()
    }

    /** Plays a whole list from the top, optionally shuffled ("Play all"). */
    fun playAll(
        tracks: List<QueueTrack>,
        shuffled: Boolean = false,
    ) {
        val order = if (shuffled) tracks.shuffled() else tracks
        order.firstOrNull()?.let { playFrom(order, it.uri) }
    }

    /**
     * The last list a screen played from. Kept so a later single-track play of
     * something in that same list (a history chip, a search hit) rejoins it
     * instead of collapsing the queue back to one item.
     */
    private var lastBrowseContext: List<QueueTrack> = emptyList()

    /**
     * Analyzes every track in a playlist in the background, caching BPM +
     * duration into the library so results persist and show up later.
     */
    fun analyzePlaylist(playlist: String) {
        val uris =
            _library.value.playlists
                .firstOrNull { it.name == playlist }
                ?.trackUris
                .orEmpty()
        if (uris.isEmpty() || _library.value.analyzing) return
        _library.update { it.copy(analyzing = true, analyzeProgress = 0f) }
        viewModelScope.launch(Dispatchers.Default) {
            uris.forEachIndexed { index, uriStr ->
                val uri = Uri.parse(uriStr)
                val merged =
                    runCatching {
                        val t = analyzeCached(uri) { }
                        trackLibrary.updateAnalysis(uriStr, titleFor(uri), t.durationMs, t.bpm, t.key)
                    }.getOrNull()
                // Progress advances even for tracks that fail to decode, so
                // the bar never freezes on a bad file.
                withContext(Dispatchers.Main) {
                    _library.value =
                        _library.value.copy(
                            tracks = merged ?: _library.value.tracks,
                            analyzeProgress = (index + 1f) / uris.size,
                        )
                }
            }
            withContext(Dispatchers.Main) { _library.update { it.copy(analyzing = false) } }
        }
    }

    // ---- Queue ----

    /** Builds a MediaItem carrying library/tag metadata so the player state
     *  (and lockscreen) shows real titles, never document-id numbers. */
    private fun mediaItemFor(uri: Uri): MediaItem {
        val known = _library.value.tracks.firstOrNull { it.uri == uri.toString() }
        val (t, a) = if (known != null) known.title to known.artist else metadataQuick(uri)
        return MediaItem
            .Builder()
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata
                    .Builder()
                    .setTitle(t)
                    .setArtist(a.ifBlank { null })
                    .build(),
            ).build()
    }

    /**
     * [mediaItemFor] for a row a screen already has the metadata for. Skips
     * both the O(n) library scan and the per-uri ContentResolver query, which
     * is what makes opening a thousand-track queue instant instead of a
     * thousand main-thread lookups.
     */
    private fun mediaItemFor(track: QueueTrack): MediaItem {
        if (track.title.isBlank()) return mediaItemFor(Uri.parse(track.uri))
        return MediaItem
            .Builder()
            .setUri(Uri.parse(track.uri))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata
                    .Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist.ifBlank { null })
                    .build(),
            ).build()
    }

    /** Main-thread-safe metadata: display name only (no retriever I/O). */
    private fun metadataQuick(uri: Uri): Pair<String, String> {
        val app = getApplication<Application>()
        val name =
            runCatching {
                app.contentResolver
                    .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()?.substringBeforeLast('.')
        return (name ?: "Track") to ""
    }

    /**
     * One-shot repair for library entries imported before tag reading:
     * anything titled like a bare document number gets re-resolved from
     * its embedded tags / display name.
     */
    private fun refreshNumericTitles() {
        viewModelScope.launch(Dispatchers.IO) {
            val bad =
                _library.value.tracks.filter {
                    it.title.matches(Regex("^[0-9:%A-F]{4,}$")) || it.artist.isEmpty() && it.title.matches(Regex("^\\d+$"))
                }
            var latest: List<LibraryTrack>? = null
            for (t in bad) {
                runCatching {
                    val (title, artist) = metadataFor(Uri.parse(t.uri))
                    if (title != t.title || artist != t.artist) {
                        trackLibrary.updateMetadata(t.uri, title, artist)?.let { latest = it }
                    }
                }
            }
            latest?.let { l -> withContext(Dispatchers.Main) { _library.update { it.copy(tracks = l) } } }
        }
    }

    fun open(uris: List<Uri>) {
        if (uris.isEmpty()) return
        player.setMediaItems(uris.map { mediaItemFor(it) })
        player.prepare()
        player.play()
        currentUri = uris.first()
        onTrackChanged()
    }

    /** Human-readable labels for the playback queue, in play order. */
    fun queueTitles(): List<String> =
        (0 until player.mediaItemCount).map { i ->
            val item = player.getMediaItemAt(i)
            item.mediaMetadata.title?.toString()
                ?: item.localConfiguration
                    ?.uri
                    ?.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                ?: "Track ${i + 1}"
        }

    /** Jumps playback to the given queue position. */
    fun playQueueIndex(index: Int) {
        if (index in 0 until player.mediaItemCount) {
            player.seekTo(index, 0L)
            player.play()
        }
    }

    /**
     * Next track, wrapping to the top of the queue at the end.
     *
     * `seekToNextMediaItem()` alone is a no-op on the last item (and on a
     * one-item queue), which is how Next came to look broken outside a
     * playlist. Wrapping keeps the button meaningful wherever playback
     * started; the queue itself is built by [playFrom], so "the end" is the
     * end of the list the user was browsing, not of a single track.
     */
    fun next() {
        if (player.mediaItemCount == 0) return
        skipFaded {
            clearAbLoop()
            if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(0, 0L)
        }
    }

    /**
     * Previous: restarts the current track when more than
     * [PlaybackQueue.PREV_RESTART_MS] into it, steps back otherwise - the
     * behaviour every music player's Previous button has. Wraps to the last
     * item from the top of the queue, mirroring [next].
     *
     * Neither direction starts playback: skipping while paused changes the
     * track and leaves it paused, as it does everywhere else.
     */
    fun previous() {
        if (player.mediaItemCount == 0) return
        if (player.currentPosition > PlaybackQueue.PREV_RESTART_MS) {
            player.seekTo(0L)
            return
        }
        skipFaded {
            clearAbLoop()
            if (player.hasPreviousMediaItem()) {
                player.seekToPreviousMediaItem()
            } else {
                player.seekTo(player.mediaItemCount - 1, 0L)
            }
        }
    }

    fun togglePlayPause() {
        // Starting playback ends live input: one ring buffer, one source.
        if (!player.isPlaying && micCapture.active) setMicEnabled(false)
        if (_playerPrefs.value.fadeMs > 0) {
            togglePlayPauseFaded()
        } else if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
    }

    /** Seeks to an absolute position; what a tapped lyric line asks for. */
    fun seekToMs(positionMs: Long) {
        if (player.duration > 0) player.seekTo(positionMs.coerceIn(0L, player.duration))
    }

    fun seekTo(fraction: Float) {
        val d = player.duration
        if (d > 0) player.seekTo((d * fraction).toLong())
    }

    // ---- Intelligence ----

    private fun onTrackChanged() {
        // Before anything else: the live analyzer's beat grid, energy envelope
        // and flux history all describe the track that just ended.
        engine.reset()
        // A new track has a new structure; section 2 of this one is not
        // section 2 of the last.
        lastStagedSection = -1
        timeline = null
        clearAbLoop()
        loadLyricsFor(currentUri)
        _vizState.update { it.copy(suggestedSceneId = null, bpm = 0f, sections = emptyList()) }
        if (_vizState.value.intelligenceMode != IntelligenceMode.MANUAL) {
            analyzeCurrentTrack()
        } else {
            // MANUAL mode never runs the offline analyzer, but a cached
            // analysis is a cheap file read - load it so the fluid journey's
            // section re-seats match a later export of the same track
            // (export always detects sections from the same timeline).
            val uri = currentUri ?: return
            val gui = _guiPrefs.value
            viewModelScope.launch(Dispatchers.IO) {
                dev.musicviz.analysis.AnalysisCache
                    .load(getApplication<Application>(), uri, gui.beatThresholdSigma, gui.effectiveBeatMinIntervalMs)
                    ?.let { t ->
                        if (currentUri == uri) {
                            timeline = t
                            _vizState.update { it.copy(bpm = t.bpm, sections = t.detectSections()) }
                        }
                    }
            }
        }
    }

    /**
     * Hue shift the user had before the key took it over, so switching the
     * option off gives their own value back rather than leaving whatever the
     * last track happened to be in.
     */
    private var hueBeforeKeyColor: Float? = null

    /**
     * Colours the visuals from the track's key, when "Colour from the key" is
     * on.
     *
     * Drives the ordinary Hue shift slider rather than adding a second, hidden
     * colour source: the value is visible where colour is set, it is saved
     * into presets and takes with everything else, and dragging the slider is
     * how you disagree with it. A track with no detected key leaves the hue
     * alone - "not analysed" is not "in C".
     */
    fun applyKeyColor(key: String) {
        if (!_guiPrefs.value.keyColor) return
        val hue = dev.musicviz.analysis.KeyPalette.hueFor(key) ?: return
        val params = _vizState.value.params
        if (hueBeforeKeyColor == null) hueBeforeKeyColor = params.colorShift
        if (params.colorShift != hue) setSceneParams(params.copy(colorShift = hue))
    }

    /**
     * Turns key colouring on or off. On, it colours the current track at once
     * if its key is already known; off, it hands the user's own hue back.
     */
    fun setKeyColor(enabled: Boolean) {
        setGuiPrefs(_guiPrefs.value.copy(keyColor = enabled))
        if (enabled) {
            currentTrackKey()?.let { applyKeyColor(it) }
        } else {
            hueBeforeKeyColor?.let { setSceneParams(_vizState.value.params.copy(colorShift = it)) }
            hueBeforeKeyColor = null
        }
    }

    /** The current track's detected key, from the library cache; "" if none. */
    fun currentTrackKey(): String? {
        val uri = currentUri?.toString() ?: return null
        return _library.value.tracks
            .firstOrNull { it.uri == uri }
            ?.key
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Builds a palette from the current track's embedded artwork and applies
     * it to the first palette slot.
     *
     * Off the main thread: this decodes an image. Reported through
     * [artPaletteNote] rather than silently, because "the sleeve is greyscale"
     * and "this file has no artwork" are both ordinary outcomes the user is
     * owed an explanation for - a button that sometimes does nothing with no
     * message reads as broken.
     */
    fun applyArtworkPalette() {
        val uri =
            currentUri ?: run {
                _artPaletteNote.value = "Nothing is playing."
                return
            }
        viewModelScope.launch(Dispatchers.IO) {
            val pixels = artworkPixels(uri)
            val extracted = pixels?.let { dev.musicviz.analysis.ArtPalette.extract(it) }
            withContext(Dispatchers.Main) {
                when {
                    pixels == null -> _artPaletteNote.value = "This track has no embedded artwork."
                    extracted == null ->
                        _artPaletteNote.value =
                            "The artwork has no colour to take — it is greyscale or nearly black."
                    else -> {
                        setSceneParams(
                            PaletteStore.applyGradient(
                                _vizState.value.params,
                                extracted.baseHue,
                                extracted.span,
                            ),
                        )
                        _artPaletteNote.value =
                            "Palette taken from the artwork (${(extracted.confidence * 100).roundToInt()}% of it had colour)."
                    }
                }
            }
        }
    }

    private val _artPaletteNote = MutableStateFlow<String?>(null)

    /** Result of the last artwork-palette attempt, for the Colour tab to show. */
    val artPaletteNote: StateFlow<String?> = _artPaletteNote

    fun clearArtPaletteNote() {
        _artPaletteNote.value = null
    }

    /**
     * Embedded artwork as ARGB pixels, downsampled hard.
     *
     * [ART_SAMPLE_SIZE] square is far more than a hue histogram needs - the
     * answer is stable well below it - and decoding a full 3000px sleeve to
     * count hues would cost tens of megabytes for no extra precision.
     */
    private fun artworkPixels(uri: Uri): IntArray? =
        runCatching {
            // try/finally rather than use(): MediaMetadataRetriever only
            // became AutoCloseable in API 29 and this app runs from 26.
            val retriever = android.media.MediaMetadataRetriever()
            val bytes =
                try {
                    retriever.setDataSource(getApplication<Application>(), uri)
                    retriever.embeddedPicture
                } finally {
                    retriever.release()
                } ?: return null
            val bounds =
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sample =
                generateSequence(1) { it * 2 }
                    .first { bounds.outWidth / it <= ART_SAMPLE_SIZE && bounds.outHeight / it <= ART_SAMPLE_SIZE }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val out = IntArray(bmp.width * bmp.height)
            bmp.getPixels(out, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            bmp.recycle()
            out
        }.getOrNull()

    fun setIntelligenceMode(mode: IntelligenceMode) {
        _vizState.update { it.copy(intelligenceMode = mode) }
        if (mode != IntelligenceMode.MANUAL && timeline == null) analyzeCurrentTrack()
    }

    fun analyzeCurrentTrack() {
        val uri = currentUri ?: return
        if (_vizState.value.analyzing) return
        _vizState.update { it.copy(analyzing = true, analysisProgress = 0f) }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val t =
                    analyzeCached(uri) { p ->
                        _vizState.update { it.copy(analysisProgress = p) }
                    }
                trackLibrary
                    .updateAnalysis(uri.toString(), titleFor(uri), t.durationMs, t.bpm, t.key)
                    ?.let { merged -> _library.update { it.copy(tracks = merged) } }
                withContext(Dispatchers.Main) { applyKeyColor(t.key) }
                if (currentUri == uri) {
                    timeline = t
                    val suggestion = SceneSuggester.suggestForTrack(t)
                    // update, not a read-then-write: this runs on Default while
                    // the 500 ms poll writes the same flow from main, and a
                    // read-then-write here loses whatever the poll published in
                    // between - or, worse, the poll's own stale snapshot lands
                    // on top of this one and the track is left spinning at 0 BPM
                    // with no sections.
                    _vizState.update {
                        it.copy(
                            analyzing = false,
                            bpm = t.bpm,
                            sections = t.detectSections(),
                            suggestedSceneId = suggestion,
                        )
                    }
                    // ExoPlayer may only be accessed from its application thread;
                    // this coroutine runs on Dispatchers.Default.
                    withContext(Dispatchers.Main) { applyIntelligence() }
                } else {
                    _vizState.update { it.copy(analyzing = false) }
                    if (_vizState.value.intelligenceMode != IntelligenceMode.MANUAL) {
                        withContext(Dispatchers.Main) { analyzeCurrentTrack() }
                    }
                }
            } catch (t: Throwable) {
                _vizState.update { it.copy(analyzing = false) }
            }
        }
    }

    private fun applyIntelligence() {
        if (_presetLocked.value) return
        if (_vizState.value.intelligenceMode != IntelligenceMode.AUTO) return
        val t = timeline ?: return
        val f = t.featuresAt(player.currentPosition)
        val suggestion = SceneSuggester.suggest(t.bpm, f.rms, f.centroid)
        // The window between reading the state and writing it back spans
        // featuresAt and suggest, and analysis finishing on Dispatchers.Default
        // publishes into the same flow. A read-then-write here would put a
        // pre-analysis snapshot back over it: spinner still on, BPM 0, sections
        // empty - so section staging never fires for that track and the fluid
        // choreography loses its journey context. Only the scene id is this
        // function's to change.
        _vizState.update { if (it.sceneId == suggestion) it else it.copy(sceneId = suggestion) }
    }

    // ---- Performance takes: record the performance, not the render ----

    private var recorder: PerformanceTake.Recorder? = null
    private var recordStartMs = 0L
    private var recordJob: Job? = null
    private var replayJob: Job? = null

    /**
     * Starts recording the live visual state.
     *
     * Driven from [vizState] rather than from each control, so anything that
     * moves the visuals is captured by construction - sliders, presets,
     * Randomize, style switches, the auto-switcher - and a control added later
     * is recorded without being told to.
     */
    fun startRecording() {
        if (_takeState.value.recording) return
        stopReplay()
        val s = _vizState.value
        recorder = PerformanceTake.Recorder(s.sceneId, s.params, _activeMilkPath.value)
        recordStartMs = android.os.SystemClock.elapsedRealtime()
        _takeState.update { it.copy(recording = true, recordedEvents = 1, recordedMs = 0L) }
        recordJob =
            viewModelScope.launch {
                // One collector on the state flow, not a polling loop: a
                // keyframe exists because something changed, and the recorder
                // throttles the burst a slider drag produces.
                _vizState.collect { live ->
                    val rec = recorder ?: return@collect
                    val at = android.os.SystemClock.elapsedRealtime() - recordStartMs
                    rec.append(at, live.sceneId, live.params, _activeMilkPath.value)
                    _takeState.update { it.copy(recordedEvents = rec.size, recordedMs = at) }
                    if (!rec.hasRoom) stopRecording()
                }
            }
    }

    /**
     * Stops recording and saves the take.
     *
     * A take with a single keyframe is discarded: it is a still, and offering
     * to replay one would be offering to replay nothing.
     *
     * The naming and the save go to IO and the name is not returned, because
     * both halves are disk work: [defaultTakeName] reads and fully parses every
     * saved take to find the lowest free number, and the save writes the whole
     * take document - a long performance is megabytes of JSON. The Takes list
     * is where the saved name shows up, and [refreshTakes] republishes it when
     * the write lands.
     */
    fun stopRecording(name: String? = null) {
        val rec = recorder ?: return
        recordJob?.cancel()
        recordJob = null
        recorder = null
        val durationMs = android.os.SystemClock.elapsedRealtime() - recordStartMs
        _takeState.update { it.copy(recording = false, recordedEvents = 0, recordedMs = 0L) }
        if (rec.size <= 1) {
            refreshTakes()
            return
        }
        // Off the recorder before the hop: it is the ViewModel's only reference
        // and startRecording() may replace it before the IO thread gets there.
        val trackUri = currentUri?.toString()
        val requested = name?.takeIf { it.isNotBlank() }
        viewModelScope.launch(Dispatchers.IO) {
            val label = requested ?: defaultTakeName()
            takeStore.save(label, rec.finish(label, trackUri, durationMs))
            refreshTakes()
        }
    }

    /** "Take 3" — the lowest number not already on disk. Reads every take; IO only. */
    private fun defaultTakeName(): String {
        val taken = takeStore.list().map { it.name }.toSet()
        var n = 1
        while ("Take $n" in taken) n++
        return "Take $n"
    }

    /**
     * Replays a take over the live visuals.
     *
     * Ticks at [TAKE_REPLAY_HZ] rather than riding the 500 ms housekeeping
     * loop: a take's keyframes are 80 ms apart, so a coarser clock would turn
     * a swept slider into a staircase. The take drives the same
     * [setSceneParams] / [selectScene] funnels a hand does, which is why the
     * renderer's settings fade smooths between keyframes for free.
     */
    fun playTake(name: String) {
        if (_takeState.value.recording) stopRecording()
        stopReplay()
        replayJob =
            viewModelScope.launch {
                // Reading the take back is a whole document parsed - the same
                // work refreshTakes goes to IO for, times one take rather than
                // divided across the list.
                val timeline = withContext(Dispatchers.IO) { takeStore.load(name) } ?: return@launch
                if (timeline.isEmpty) return@launch
                val endMs = maxOf(timeline.lastEventMs(), timeline.durationMs)
                _takeState.update { it.copy(replaying = name, replayMs = 0L, replayEndMs = endMs) }
                val startedAt = android.os.SystemClock.elapsedRealtime()
                while (true) {
                    val at = android.os.SystemClock.elapsedRealtime() - startedAt
                    timeline.stateAt(at)?.let { state ->
                        if (state.sceneId.isNotEmpty() && state.sceneId != _vizState.value.sceneId) {
                            selectScene(state.sceneId)
                        }
                        if (state.params != _vizState.value.params) setSceneParams(state.params)
                        state.milkPath?.takeIf { it != _activeMilkPath.value }?.let { path ->
                            _vizApply.tryEmit(VizApply(milkPath = path, sceneId = state.sceneId))
                        }
                    }
                    _takeState.update { it.copy(replayMs = at) }
                    if (at >= endMs) break
                    delay(1000L / TAKE_REPLAY_HZ)
                }
                _takeState.update { it.copy(replaying = null, replayMs = 0L, replayEndMs = 0L) }
            }
    }

    /** Stops a replay, leaving the visuals wherever the take had reached. */
    fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        _takeState.update { it.copy(replaying = null, replayMs = 0L, replayEndMs = 0L) }
    }

    fun deleteTake(name: String) {
        if (_takeState.value.replaying == name) stopReplay()
        takeStore.delete(name)
        refreshTakes()
    }

    fun renameTake(
        from: String,
        to: String,
    ) {
        if (takeStore.rename(from, to)) {
            if (_takeState.value.replaying == from) stopReplay()
            refreshTakes()
        }
    }

    /**
     * Re-reads the takes list off the main thread.
     *
     * Listing means parsing each take's JSON for its header, and a set of long
     * takes is megabytes of it - cheap in absolute terms, but not something to
     * do on the main thread at launch, which is one of the callers.
     */
    private fun refreshTakes() {
        viewModelScope.launch(Dispatchers.IO) {
            val listed = takeStore.list()
            withContext(Dispatchers.Main) { _takeState.update { it.copy(takes = listed) } }
        }
    }

    /**
     * The take the video export should replay, or null for "render the live
     * settings". Parameter automation only - see [TakeUiState.exportTake].
     */
    fun setExportTake(name: String?) {
        _takeState.update { it.copy(exportTake = name) }
    }

    // ---- Visual settings ----

    fun selectScene(sceneId: String) {
        _vizState.update { it.copy(sceneId = sceneId) }
        persistVizState()
    }

    fun setReactivity(
        attack: Float,
        decay: Float,
    ) {
        engine.smoother.attack = attack
        engine.smoother.decay = decay
        _vizState.update { it.copy(attack = attack, decay = decay) }
        persistVizState()
    }

    fun setSceneParams(params: SceneParams) {
        _vizState.update { it.copy(params = params) }
        persistVizState()
    }

    /**
     * Puts every Customize control back to its default.
     *
     * Goes through [setSceneParams] like a slider does, so the renderer's
     * settings fade glides into it and the live state is persisted - the same
     * path a preset apply takes. The selected style, saved presets and the
     * modulation routing (LFOs, envelopes) are untouched: this is "undo my
     * slider fiddling", not "reset the app".
     */
    fun resetSceneParams() = setSceneParams(SceneParams.DEFAULT)

    /**
     * Pinch and twist on the canvas, as moves on the Zoom and Rotation
     * sliders. Through [setSceneParams] like every other control, so the
     * gesture is captured by a running take, saved into a preset and shown on
     * the sliders themselves - a transform the panel could not see would drift
     * from it the moment either was touched.
     */
    fun nudgeTransform(
        zoomFactor: Float,
        rotationDegrees: Float,
    ) {
        val p = _vizState.value.params
        val next =
            p.copy(
                zoom = dev.musicviz.render.scene.TouchTransform.zoom(p.zoom, zoomFactor),
                rotation = dev.musicviz.render.scene.TouchTransform.rotation(p.rotation, rotationDegrees),
            )
        if (next != p) setSceneParams(next)
    }

    fun reportShaderError(error: String?) {
        _vizState.update { it.copy(shaderError = error) }
    }

    fun savePreset(
        name: String,
        customShader: String?,
        folder: String = "",
    ) {
        // " · " is reserved for built-in presets (isBuiltIn matches on it);
        // a user preset containing it would be undeletable in the browser.
        @Suppress("NAME_SHADOWING")
        val name = name.replace(" · ", " - ").trim().ifEmpty { "Preset" }
        val s = _vizState.value
        // On the milkdrop scene the parameters are only half the look: the
        // .milk preset paints the picture they post-process. Its SOURCE goes
        // into the preset itself so the saved state is the whole visual - a
        // preset that carries only the params reloads as projectM's idle "M"
        // logo, which is the bug this closes - and a copy is materialized in
        // the user's milk dir so the file is reachable from the MilkDrop tab
        // like any other .milk they loaded.
        val milkSource =
            if (s.sceneId == SceneIds.MILKDROP) {
                _activeMilkPath.value?.let { src -> runCatching { java.io.File(src).readText() }.getOrNull() }
            } else {
                null
            }
        milkSource?.let { source -> runCatching { milkFileFor(name).writeText(source) } }
        presetStore.save(Preset(name, s.sceneId, s.attack, s.decay, customShader, s.params, milkSource), folder)
        mirrorPresetToChosenFolder(name)
        _vizState.value = s.copy(presets = BuiltInPresets.ALL + presetStore.list())
    }

    /**
     * Mirrors the just-saved preset JSON (and paired .milk on the milkdrop
     * scene) into the user's chosen preset folder (Settings > Paths) so their
     * own file-manager sorting stays in sync. Internal storage remains the
     * working store; mirroring is best-effort.
     */
    private fun mirrorPresetToChosenFolder(name: String) {
        val uriStr = _guiPrefs.value.presetMirrorUri ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val tree =
                    androidx.documentfile.provider.DocumentFile
                        .fromTreeUri(app, Uri.parse(uriStr))
                        ?: return@runCatching

                fun copyInto(
                    src: java.io.File,
                    mime: String,
                ) {
                    if (!src.exists()) return
                    tree.findFile(src.name)?.delete()
                    val dest = tree.createFile(mime, src.name) ?: return
                    app.contentResolver.openOutputStream(dest.uri)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                }
                presetStore.fileOf(name)?.let { copyInto(it, "application/json") }
                // Same sanitized base name the .json got (PresetStore.milkFileName):
                // the raw name was a different file for anything with a slash or
                // a colon in it, so the mirror silently skipped the .milk.
                milkFileFor(name).let { copyInto(it, "text/plain") }
            }
        }
    }

    /**
     * The .milk file a preset named [presetName] owns, whether or not it
     * exists yet. Named through [PresetStore.milkFileName] so a preset's
     * .milk and its .json always share one sanitized base name.
     */
    private fun milkFileFor(presetName: String): java.io.File =
        java.io.File(
            java.io.File(getApplication<Application>().filesDir, "milk").apply { mkdirs() },
            PresetStore.milkFileName(presetName),
        )

    /**
     * The .milk file [preset] should render, materializing its carried source
     * on the way, or null when it has none.
     *
     * Two eras resolve here. Presets saved with their source (the [Preset]
     * `milkPreset` field) write it out under the preset's own name, so they
     * work after a share, an import or a reinstall. Presets saved before that
     * only ever left the copied file behind, so an existing file under the
     * same name is used as-is rather than declaring the preset broken.
     */
    internal fun milkPresetPathFor(preset: Preset): String? {
        if (preset.sceneId != SceneIds.MILKDROP) return null
        val file = milkFileFor(preset.name)
        preset.milkPreset?.let { source ->
            runCatching { if (!file.isFile || file.readText() != source) file.writeText(source) }
        }
        return file.takeIf { it.isFile }?.absolutePath
    }

    /**
     * The .milk preset the engine is showing, or null on a style that is not
     * MilkDrop (or before one was ever loaded).
     *
     * Persisted, because the selected style survives a restart
     * (`restoreVizState`) while the engine's loaded preset did not:
     * relaunching on the milkdrop style came back to projectM's idle "M"
     * instead of the visual that was on screen. Restored only if the file is
     * still there, so a path left behind by a deleted preset is not offered to
     * the engine. `VisualizerEngineBindings` re-queues it on the first
     * composition after launch, and the wallpaper reads the same key.
     */
    private val _activeMilkPath =
        MutableStateFlow(vizPrefs().getString("milk_path", null)?.takeIf { java.io.File(it).isFile })
    val activeMilkPath: StateFlow<String?> = _activeMilkPath

    fun noteMilkPreset(path: String) {
        _activeMilkPath.value = path
        vizPrefs().edit().putString("milk_path", path).apply()
    }

    /** Preset morphing: applied params fade over [GuiPrefs.morphBeats] beats
     *  of the detected BPM (renderer's displayedParams does the lerp). The
     *  fade travels as a transient [morphFade] event - baking it into
     *  paramFadeSec permanently inflated the user's "Fade time" setting and
     *  got persisted into every preset saved afterwards. */
    private fun emitPresetMorph() {
        val beats = _guiPrefs.value.morphBeats
        if (beats <= 0) return
        val bpm = features.value.bpm.takeIf { it > 40f } ?: 120f
        _morphFade.tryEmit(beats * 60f / bpm)
    }

    fun applyPreset(preset: Preset) {
        // One atomic state update (was setReactivity + selectScene +
        // setSceneParams: three emissions + three disk persists). The engine
        // bindings observe sceneId and params through separate effects, so
        // separate emissions could land the scene switch a frame before the
        // new params - the new scene flashed in wearing the old look.
        engine.smoother.attack = preset.attack
        engine.smoother.decay = preset.decay
        _vizState.update {
            it.copy(
                sceneId = preset.sceneId,
                params = preset.params,
                attack = preset.attack,
                decay = preset.decay,
            )
        }
        persistVizState()
        emitPresetMorph()
        // Every apply path must push the preset's custom shader; returning it
        // for the caller to forward let two call sites (quick-preset swipe,
        // search overlay) silently drop it - the preset rendered with the
        // stock shader instead of the saved GLSL.
        preset.customShader?.let {
            _vizApply.tryEmit(VizApply(customShader = it, sceneId = preset.sceneId))
        }
        // The MilkDrop half of the same rule: params alone are not the look,
        // and a preset applied without its .milk left the engine on whatever
        // was loaded before - projectM's idle "M" logo on a cold start.
        milkPresetPathFor(preset)?.let {
            _vizApply.tryEmit(VizApply(milkPath = it, sceneId = preset.sceneId))
        }
    }

    /**
     * A shareable link for [name], or null when it is too long to survive a
     * chat app (a preset carrying a custom shader) - the caller then offers
     * the file instead.
     */
    fun presetShareLink(name: String): String? {
        val preset = _vizState.value.presets.firstOrNull { it.name == name } ?: return null
        val link = PresetLink.encode(PresetStore.toJson(preset))
        return link.takeIf { it.length <= PresetLink.MAX_LINK_LENGTH }
    }

    /**
     * Imports a preset from a link (or from text containing one). Returns the
     * name it was saved under, or null when the text holds no readable preset.
     *
     * Imported under its own name with a numeric suffix on collision, like a
     * take: overwriting a preset the user built because a stranger's happens
     * to share its name would be destroying work to save a rename.
     */
    fun importPresetLink(text: String): String? {
        val link = PresetLink.findIn(text) ?: return null
        return importPresetJson(PresetLink.decode(link) ?: return null)
    }

    /**
     * Imports a preset from a picked `.json` file - the other half of sharing.
     *
     * A preset too long to survive a chat message goes out as its file
     * instead ([presetFile]), and MilkDrop presets always do now that they
     * carry their .milk source. Without a way back IN, that branch of Share
     * produced a file the receiving app could do nothing with.
     */
    fun importPresetFile(uri: Uri): String? =
        runCatching {
            getApplication<Application>()
                .contentResolver
                .openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }.getOrNull()?.let { importPresetJson(it) }

    /** Saves an incoming preset document; the shared tail of both imports. */
    private fun importPresetJson(json: String): String? {
        val incoming = runCatching { PresetStore.fromJson(json) }.getOrNull() ?: return null
        val existing = _vizState.value.presets.map { it.name }.toSet()
        val base = incoming.name.ifBlank { "Shared preset" }
        var name = base
        var n = 2
        while (name in existing) {
            name = "$base $n"
            n++
        }
        presetStore.save(incoming.copy(name = name))
        _vizState.update { it.copy(presets = BuiltInPresets.ALL + presetStore.list()) }
        return name
    }

    /** On-disk file for a preset, for sharing one too big to be a link. */
    fun presetFile(name: String): java.io.File? = presetStore.fileOf(name)

    fun deletePreset(name: String) {
        if (BuiltInPresets.isBuiltIn(name)) return
        presetStore.delete(name)
        _vizState.update { it.copy(presets = BuiltInPresets.ALL + presetStore.list()) }
    }

    // ---- Export ----

    fun startExport(
        aspect: ExportAspect,
        fps: Int,
        sceneFactory: VideoExporter.SceneFactory,
        destination: Uri? = null,
        /** Trim to whole bars so the clip loops without a stumble. */
        loopSafe: Boolean = false,
    ) {
        val uri = currentUri ?: return
        if (_exportState.value.running) return
        exportCancelled = false
        _exportState.value = ExportUiState(running = true, customDestination = destination != null)
        exportJob =
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val analysed =
                        timeline ?: analyzeCached(uri) { p ->
                            _exportState.update { it.copy(progress = p * 0.2f) }
                        }.also { if (currentUri == uri) timeline = it }
                    // Always re-decide the beats from the stored onset curve
                    // at the sensitivity in force right now: the in-memory
                    // timeline may have been analysed (or last re-decided)
                    // under other settings, and a video that flashes
                    // differently from the playback the user just watched is
                    // the whole bug this guards against.
                    val gui = _guiPrefs.value
                    val t =
                        analysed.withBeatSensitivity(
                            gui.beatThresholdSigma,
                            // Same floor the live engine runs under, or an
                            // export would flash faster than the screen did.
                            gui.effectiveBeatMinIntervalMs,
                        )
                    // Publish the section context the exporter is about to
                    // journey through, so live playback of the same track
                    // re-seats identically from now on (journey parity even
                    // in MANUAL mode, where onTrackChanged only reads cache).
                    if (currentUri == uri && _vizState.value.sections.isEmpty()) {
                        _vizState.update { it.copy(bpm = t.bpm, sections = t.detectSections()) }
                    }
                    val name = "musicviz_${System.currentTimeMillis()}.mp4"
                    val result =
                        exporter.export(
                            audioUri = uri,
                            timeline = t,
                            sceneFactory = sceneFactory,
                            aspect = aspect,
                            fileName = name,
                            sceneParams = _vizState.value.params,
                            lfoConfigs = _lfos.value,
                            adsrConfigs = _adsrs.value,
                            safety = gui.safety,
                            requestedFps = fps,
                            // A chosen take renders the performance instead of
                            // the live settings. Loaded once, outside the frame
                            // loop: the Timeline is a stateful cursor, and the
                            // export coroutine is its only reader.
                            paramsAt =
                                _takeState.value.exportTake
                                    ?.let { takeStore.load(it) }
                                    ?.takeUnless { it.isEmpty }
                                    ?.let { take -> { ms: Long -> take.stateAt(ms)?.params ?: _vizState.value.params } },
                            loopSafe = loopSafe,
                            destination = destination,
                            onProgress = { p ->
                                _exportState.update { it.copy(progress = 0.2f + p * 0.8f) }
                            },
                            isCancelled = { exportCancelled },
                        )
                    _exportState.value = exportUiStateFor(result, customDestination = destination != null)
                } catch (t: Throwable) {
                    if (exportCancelled) {
                        // User-initiated cancel (can surface as our own
                        // CancellationException from the transcoder): not an
                        // error, just reset the state.
                        _exportState.value = ExportUiState(running = false)
                    } else if (t is kotlinx.coroutines.CancellationException) {
                        _exportState.value = ExportUiState(running = false)
                        throw t
                    } else {
                        val detail = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
                        _exportState.value = ExportUiState(running = false, error = detail)
                    }
                }
            }
    }

    fun cancelExport() {
        exportCancelled = true
    }

    /** Clears a finished export's result/error so the next dialog open shows the options again. */
    fun resetExportState() {
        if (!_exportState.value.running) _exportState.value = ExportUiState()
    }

    // ---- Export Studio ----

    private val studioExporter = dev.musicviz.export.StudioExporter(application)

    private val _studio = MutableStateFlow(StudioUiState())

    /** Clip list and export progress for the Studio tab. */
    val studio: StateFlow<StudioUiState> = _studio

    private var studioJob: Job? = null

    /** Re-reads Movies/MusicViz. Cheap enough to run on every tab entry. */
    fun refreshStudioClips() {
        viewModelScope.launch {
            _studio.update { it.copy(loading = true) }
            val clips = withContext(Dispatchers.IO) { dev.musicviz.export.StudioClips.list(getApplication()) }
            _studio.update { it.copy(clips = clips, loading = false) }
        }
    }

    /** Describes a clip the user picked through the system file picker. */
    fun describeStudioClip(
        uri: Uri,
        onReady: (dev.musicviz.export.StudioClip) -> Unit,
    ) {
        viewModelScope.launch {
            val clip = withContext(Dispatchers.IO) { dev.musicviz.export.StudioClips.describe(getApplication(), uri) }
            onReady(clip)
        }
    }

    /**
     * Renders an edit to a new file in Movies/MusicViz.
     *
     * Always a new file: an edit that overwrote its source would make the one
     * irreversible action in the app the DEFAULT one, and the original render
     * can be minutes of GPU time.
     */
    fun startStudioExport(
        clip: dev.musicviz.export.StudioClip,
        edit: dev.musicviz.export.ClipEdit,
    ) {
        if (_studio.value.running) return
        _studio.update { it.copy(running = true, progress = 0f, resultUri = null, error = null) }
        studioJob =
            viewModelScope.launch {
                val name = "musicviz_studio_${System.currentTimeMillis()}.mp4"
                val result =
                    studioExporter.export(
                        source = Uri.parse(clip.uri),
                        sourceDurationMs = clip.durationMs,
                        edit = edit,
                        displayName = name,
                    ) { p -> _studio.update { it.copy(progress = p.coerceIn(0f, 1f)) } }
                when (result) {
                    is dev.musicviz.export.StudioExporter.Result.Saved ->
                        _studio.update { it.copy(running = false, progress = 1f, resultUri = result.uri) }
                    is dev.musicviz.export.StudioExporter.Result.Failed ->
                        _studio.update { it.copy(running = false, error = result.message) }
                    dev.musicviz.export.StudioExporter.Result.Cancelled ->
                        _studio.update { it.copy(running = false, progress = 0f) }
                }
                refreshStudioClips()
                studioJob = null
            }
    }

    fun cancelStudioExport() {
        studioExporter.cancel()
        studioJob?.cancel()
        studioJob = null
        _studio.update { it.copy(running = false, progress = 0f) }
    }

    /** Clears a finished Studio export so the editor shows its controls again. */
    fun clearStudioResult() {
        _studio.update { it.copy(resultUri = null, error = null, progress = 0f) }
    }

    override fun onCleared() {
        // Whatever was playing when the process went away still counts, and it
        // has to be on disk before this method returns - the queued write has
        // no later moment to land in.
        flushListenTime()
        historyStore.awaitWrites()
        // The debounced live-state write rides viewModelScope, which is
        // cancelled BEFORE onCleared runs, so the last slider the user touched
        // is only on disk if it is written here.
        if (vizStateDirty) writeVizState()
        // A running export is not stopped by that same cancellation:
        // VideoExporter's render loop never suspends, so the cancel flag is its
        // only exit. Left set false, a hardware AVC encoder, an EGL context and
        // a full GPU loop keep running for minutes with the UI gone and
        // cancelExport() unreachable - and re-entering builds a ViewModel whose
        // export state says idle, so a second export starts against a codec the
        // first still holds. The flag is also what makes the exporter delete
        // its half-written file, exactly as a user-cancel does.
        cancelExport()
        exportJob = null
        // The microphone goes first: an open AudioRecord outliving the
        // ViewModel would keep the recording indicator up with nothing left
        // to read it.
        micCapture.stop()
        // Same for the playback capture, which additionally holds a
        // foreground service and its "this app can hear you" notification.
        if (_externalAudio.value.active) stopExternalAudio()
        // Stop feeding the wallpaper, so it falls back to its own idle motion
        // instead of holding the last frame this session produced.
        dev.musicviz.audio.AudioBus
            .clear()
        engine.stop()
        // Both hooks into the player have to come off it by hand now that the
        // player outlives this object, or a ViewModel nobody can see goes on
        // writing history and retuning an analyzer that has stopped.
        //
        // The identity check is not paranoia. Android is allowed to build the
        // next screen's ViewModel before it clears this one - a relaunch runs
        // the new Activity's onCreate before the old one's onDestroy - so by
        // the time this line runs, the hook on the player may already belong to
        // the ViewModel that replaced this one, and clearing it would leave the
        // live screen's analyzer deaf to every sample-rate change.
        playerListener?.let { player.removeListener(it) }
        playerListener = null
        if (playback.onAudioFormat === audioFormatHook) playback.onAudioFormat = null
        // This is where the app stops owning playback and starts merely being
        // one of its two owners. Music that is playing keeps playing: the
        // service holds the other reference and the notification is now the
        // only transport, which is the whole point. Music that is NOT playing
        // has nothing to keep alive, so the service comes down, and its
        // onDestroy gives back the last reference - which is what actually
        // releases the player, here as before, just one hop later.
        if (!playback.playbackWanted) PlaybackService.stop(getApplication())
        PlaybackEngine.releaseUi()
    }

    private companion object {
        /**
         * Longest gap the listening accrual will believe. The poll runs every
         * 500 ms, so anything past a few seconds is a suspended process rather
         * than time the user spent listening.
         */
        const val MAX_LISTEN_TICK_MS = 5_000L

        /** Columns in the waveform seek bar. */
        const val WAVEFORM_BUCKETS = 240

        /** Shortest A-B loop worth arming; below it a second tap means "re-mark A". */
        const val MIN_LOOP_MS = 1_000L

        /** Volume ramp granularity. 25 ms is inaudible as steps. */
        const val FADE_STEP_MS = 25L

        /**
         * How long the live viz state is allowed to sit unwritten. Long enough
         * that a slider drag, a pinch or a second of take replay is one write
         * instead of tens; short enough that a process killed moments after the
         * user let go still comes back to what they left.
         */
        const val VIZ_PERSIST_WINDOW_MS = 400L
    }
}
