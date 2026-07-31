package dev.musicviz.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.musicviz.analysis.AnalysisEngine
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.analysis.AudioQualityInfo
import dev.musicviz.analysis.FeatureTimeline
import dev.musicviz.analysis.IntelligenceMode
import dev.musicviz.analysis.OfflineAnalyzer
import dev.musicviz.analysis.PlaybackMath
import dev.musicviz.analysis.SceneSuggester
import dev.musicviz.audio.AudioFxController
import dev.musicviz.audio.AudioFxState
import dev.musicviz.audio.AudioQualityTracker
import dev.musicviz.audio.PcmRingBuffer
import dev.musicviz.audio.PcmTapSink
import dev.musicviz.audio.TapRenderersFactory
import dev.musicviz.export.ExportAspect
import dev.musicviz.export.VideoExporter
import dev.musicviz.render.TransitionStyle
import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.PcmChunk
import dev.musicviz.render.scene.SceneFactory
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

/** One row of the device music index (MediaStore). */
data class DeviceTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val durationMs: Long,
)

/** Music library + playlists + batch-analysis progress. */
data class LibraryState(
    val tracks: List<LibraryTrack> = emptyList(),
    val playlists: List<MusicPlaylist> = emptyList(),
    val analyzing: Boolean = false,
    val analyzeProgress: Float = 0f,
)

private val AUDIO_EXTS = setOf("mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "wma", "aiff")

data class ExportUiState(
    val running: Boolean = false,
    /** True when the user picked the output location via the file picker. */
    val customDestination: Boolean = false,
    val progress: Float = 0f,
    val resultUri: Uri? = null,
    val error: String? = null,
)

/**
 * Owns playback (queue + audio focus + PCM tap), live analysis, offline
 * analysis/intelligence, presets and export orchestration.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val ring = PcmRingBuffer()
    private val engine = AnalysisEngine(ring)

    // Declared before [sink] on purpose: the sink's format callback calls
    // into it (see the construction-order note above the init block).
    private val audioQualityTracker = AudioQualityTracker { currentUri }

    /** Source vs decoded-output quality of the current track; null when idle. */
    val audioQuality: StateFlow<AudioQualityInfo?> = audioQualityTracker.info

    private val sink =
        PcmTapSink(ring) { rate, channels, encoding ->
            engine.sampleRateHz = rate
            audioQualityTracker.onTapFormat(rate, channels, encoding)
        }
    private val offlineAnalyzer = OfflineAnalyzer(application)
    private val metadataReader = TrackMetadataReader(application)
    private val deviceIndex = DeviceMusicIndex(application)
    private val milkAssets = MilkAssetStore(application)
    private val presetStore: PresetRepository = PresetStore(application)
    private val trackLibrary: LibraryRepository = TrackLibrary(application)
    private val themeStore = ThemeStore(application)
    private val playerPrefsStore = PlayerPrefsStore(application)
    private val textureStore = TextureStore(application)
    private val lfoStore = LfoStore(application)
    private val musicPlaylists: MusicPlaylistRepository = MusicPlaylistStore(application)
    private val exporter = VideoExporter(application)
    private val audioFxController = AudioFxController(application)

    val player: ExoPlayer =
        ExoPlayer
            .Builder(application, TapRenderersFactory(application, sink))
            // AIFF/AIFC support: Media3 ships no AIFF extractor, so ours is
            // appended after the defaults (sniff order keeps defaults first).
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    application,
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
                true,
            ).build()

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
     */
    private fun restoreVizState(): VizUiState {
        val base = VizUiState(presets = BuiltInPresets.ALL + presetStore.list())
        val json = vizPrefs().getString("live_state", null) ?: return base
        return runCatching {
            val p = PresetStore.fromJson(json)
            base.copy(sceneId = p.sceneId, attack = p.attack, decay = p.decay, params = p.params)
        }.getOrDefault(base)
    }

    /** Persists the live viz state; called from every mutation funnel. */
    private fun persistVizState() {
        val s = _vizState.value
        val json = PresetStore.toJson(Preset("__live__", s.sceneId, s.attack, s.decay, null, s.params))
        vizPrefs().edit().putString("live_state", json).apply()
    }

    /**
     * What export reaches into. Declared as its own object rather than making
     * the ViewModel implement [ExportSource]: `currentUri` and `timeline` are
     * private state, and they must not become public just to satisfy an
     * interface.
     */
    private val exportSource =
        object : ExportSource {
            override val currentUri: Uri?
                get() = this@PlayerViewModel.currentUri

            override val timeline: FeatureTimeline?
                get() = this@PlayerViewModel.timeline

            override fun cacheTimeline(
                uri: Uri,
                timeline: FeatureTimeline,
            ) {
                if (this@PlayerViewModel.currentUri == uri) this@PlayerViewModel.timeline = timeline
            }

            override suspend fun analyze(
                uri: Uri,
                onProgress: (Float) -> Unit,
            ): FeatureTimeline = analyzeCached(uri, onProgress)

            override fun beatSensitivity(): BeatSensitivity =
                BeatSensitivity(_guiPrefs.value.beatThresholdSigma, _guiPrefs.value.beatMinIntervalMs)

            override fun visuals(): ExportVisuals = ExportVisuals(_vizState.value.params, _lfos.value, _adsrs.value)

            override fun publishJourney(
                uri: Uri,
                timeline: FeatureTimeline,
            ) {
                if (this@PlayerViewModel.currentUri == uri && _vizState.value.sections.isEmpty()) {
                    _vizState.update { it.copy(bpm = timeline.bpm, sections = timeline.detectSections()) }
                }
            }
        }

    private val exportCoordinator = ExportCoordinator(viewModelScope, exporter, exportSource)

    val exportState: StateFlow<ExportUiState> = exportCoordinator.state

    private val _library = MutableStateFlow(LibraryState(trackLibrary.list(), musicPlaylists.list()))
    val library: StateFlow<LibraryState> = _library

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
        engine.beatMinIntervalMs = _guiPrefs.value.beatMinIntervalMs
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
        engine.beatMinIntervalMs = prefs.beatMinIntervalMs
        val sensitivityChanged =
            previous.beatThresholdSigma != prefs.beatThresholdSigma ||
                previous.beatMinIntervalMs != prefs.beatMinIntervalMs
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
                val updated = base.withBeatSensitivity(prefs.beatThresholdSigma, prefs.beatMinIntervalMs)
                val now = _guiPrefs.value
                val stillCurrent =
                    now.beatThresholdSigma == prefs.beatThresholdSigma &&
                        now.beatMinIntervalMs == prefs.beatMinIntervalMs
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

    private val _textures = MutableStateFlow(textureStore.list())
    val textures: StateFlow<List<MilkTexture>> = _textures

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
     * Randomizes every unlocked Customize parameter within its slider range.
     *
     * The roll itself lives in [ParamRandomizer] so it stays pure and unit
     * testable; locks are keyed by the slider label the lock chip persists.
     */
    fun randomizeParams() {
        setSceneParams(ParamRandomizer.randomize(_vizState.value.params, _lockedParams.value))
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

    /** Async: next .milk preset in name order; path arrives on main. */
    fun nextMilkPresetAsync(onDone: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = milkAssets.nextPreset()
            withContext(Dispatchers.Main) { onDone(path) }
        }
    }

    /** Async listing of all .milk files for the browser. */
    fun milkPresetFilesAsync(onDone: (List<MilkFile>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val files = milkAssets.listPresets()
            withContext(Dispatchers.Main) { onDone(files) }
        }
    }

    /** Async import of a user-picked .milk preset; path arrives on main. */
    fun importMilkPresetAsync(
        uri: Uri,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = milkAssets.importPreset(uri)
            withContext(Dispatchers.Main) { onDone(path) }
        }
    }

    private var timeline: FeatureTimeline? = null
    private var currentUri: Uri? = null
    private var beatRedecideJob: Job? = null

    // Fields used by the construction-time main loop (launched in the init
    // block below on Main.immediate, which executes synchronously until its
    // first delay). They MUST be declared before that init block: on-device
    // this crashed at launch with an NPE when applyIntelligence() read
    // _presetLocked before its initializer had run. Robolectric's deferred
    // looper hid the crash, which is why the smoke test passed.
    private val historyStore: HistoryRepository = HistoryStore(application)
    private val _historyTick = MutableStateFlow(0)
    val historyTick: StateFlow<Int> = _historyTick

    /** Keep the current preset: auto/random switching skips while locked. */
    private val _presetLocked = MutableStateFlow(false)
    val presetLocked: StateFlow<Boolean> = _presetLocked

    /** 0 = off, 1 = random, 2 = intelligent. */
    private val _autoMode = MutableStateFlow(0)
    val autoMode: StateFlow<Int> = _autoMode

    init {
        engine.start(viewModelScope)
        refreshNumericTitles()
        // Restore persisted playback options onto the freshly built player.
        // Auto-resume runs BEFORE the listener registers so the startup
        // preparation never records a phantom play into history (ExoPlayer
        // only delivers events to listeners registered when they occurred).
        val pp = _playerPrefs.value
        player.shuffleModeEnabled = pp.shuffle
        player.repeatMode = pp.repeatMode
        applyPlaybackPrefs(pp)
        if (pp.autoResume) prepareLastPlayed()
        player.addListener(
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
                            historyStore.recordPlay(u.toString(), title)
                            _historyTick.update { it + 1 }
                        }
                        onTrackChanged()
                    }
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    // The audiofx chain must follow the sink's session; attach
                    // rebuilds the effects and restores persisted settings.
                    audioFxController.attach(audioSessionId)
                    refreshAudioFx()
                }

                override fun onTracksChanged(tracks: Tracks) {
                    // Selected audio track's source Format (mime, sample rate,
                    // pcm encoding, bitrate) for the quality readout. Defensive
                    // scan: take the first selected audio track, else null.
                    var fmt: Format? = null
                    outer@ for (group in tracks.groups) {
                        if (group.type != C.TRACK_TYPE_AUDIO) continue
                        for (i in 0 until group.length) {
                            if (group.isTrackSelected(i)) {
                                fmt = group.getTrackFormat(i)
                                break@outer
                            }
                        }
                    }
                    audioQualityTracker.onSourceFormat(fmt)
                }
            },
        )
        // The sink may already have a session id (attach ignores UNSET = 0).
        audioFxController.attach(player.audioSessionId)
        refreshAudioFx()
        viewModelScope.launch {
            while (true) {
                refresh()
                applyIntelligence()
                advanceVizPlaylist()
                advanceRandomMode()
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

    private val sleepTimer = SleepTimerController(viewModelScope, player)

    /** Remaining sleep-timer time, or null when no timer is running. */
    val sleepTimerRemainingMs: StateFlow<Long?> = sleepTimer.remainingMs

    /**
     * Starts (or restarts) the sleep timer. Persists [minutes] as the
     * last-chosen duration (never a running state); the countdown itself
     * lives in [SleepTimerController].
     */
    fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            cancelSleepTimer()
            return
        }
        setPlayerPrefs(_playerPrefs.value.copy(sleepTimerMinutes = minutes))
        sleepTimer.start(minutes)
    }

    /** Cancels a running sleep timer and restores full volume. */
    fun cancelSleepTimer() {
        sleepTimer.cancel()
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
        _vizState.update { it.copy(transitionStyle = style) }
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
                // Intelligent: after a minimum dwell, switch on a strong musical
                // moment (beat + high energy); force a switch at 2x interval so
                // quiet passages still rotate.
                val f = engine.features.value
                val minDwell = maxOf(8_000L, intervalMs / 2)
                (elapsed >= minDwell && f.beat && f.rms > 0.28f) || elapsed >= intervalMs * 2
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
                val f = engine.features.value
                val minDwell = maxOf(6_000L, intervalMs / 2)
                (elapsed >= minDwell && f.beat && f.rms > 0.25f) || elapsed >= intervalMs * 2
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
            val cur = _vizState.value
            val rolled =
                cur.params.copy(
                    palette = randomRng.nextInt(SceneParams.PALETTES.size),
                    palette2 = randomRng.nextInt(SceneParams.PALETTES.size),
                    paletteMix = if (randomRng.nextBoolean()) randomRng.nextFloat() * 0.6f else 0f,
                    colorShift = randomRng.nextFloat(),
                )
            // A custom-palette override outranks the PALETTES lookup, so the
            // new indices stay invisible unless both slots are cleared too.
            _vizState.value =
                cur.copy(params = PaletteStore.clear(PaletteStore.clear(rolled), second = true))
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
            _deviceTracks.value = deviceIndex.query()
        }
    }

    /** Imports picked audio files into the library (persist read permission first). */
    fun importTracks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // Tag reading runs a content-resolver query per file; a large
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
                    metadataReader.libraryTrack(uri)
                }
            val merged = trackLibrary.addAll(tracks)
            _library.update { it.copy(tracks = merged) }
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
            metadataReader.libraryTrack(uriStr, metadataReader.read(Uri.parse(uriStr)))
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
            withContext(Dispatchers.Main) { _library.update { it.copy(tracks = merged) } }
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
            .load(app, uri, gui.beatThresholdSigma, gui.beatMinIntervalMs)
            ?.let {
                onProgress(1f)
                return it
            }
        return offlineAnalyzer
            .analyze(uri, gui.beatThresholdSigma, gui.beatMinIntervalMs, onProgress)
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
                            found += metadataReader.libraryTrack(f.uri)
                        }
                    }
                }
            }
            walk(root, 0)
        }
        if (found.isNotEmpty()) {
            val merged = trackLibrary.addAll(found)
            withContext(Dispatchers.Main) { _library.update { it.copy(tracks = merged) } }
        }
    }

    fun removeFromLibrary(uri: String) {
        _library.update { it.copy(tracks = trackLibrary.remove(uri)) }
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

    fun moveTrackInPlaylist(
        playlist: String,
        from: Int,
        to: Int,
    ) {
        musicPlaylists.move(playlist, from, to)
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
        return names.map { uri -> byUri[uri] ?: LibraryTrack(uri = uri, title = metadataReader.titleOf(Uri.parse(uri))) }
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
        player.setMediaItems(uris.map { mediaItemFor(Uri.parse(it)) })
        player.prepare()
        player.seekTo(startIndex.coerceIn(0, uris.size - 1), 0L)
        player.play()
        currentUri = Uri.parse(uris[startIndex.coerceIn(0, uris.size - 1)])
        onTrackChanged()
    }

    // ---- Navigation v2 additions ----

    fun recentlyPlayed() = historyStore.recentlyPlayed()

    fun mostPlayed() = historyStore.mostPlayed()

    fun togglePresetLock() {
        _presetLocked.update { !it }
    }

    fun cycleAutoMode() {
        val next = (_autoMode.value + 1) % 3
        _autoMode.value = next
        setRandomEnabled(next == 1)
        setIntelligenceMode(if (next == 2) IntelligenceMode.AUTO else IntelligenceMode.MANUAL)
    }

    fun playNext(uri: String) {
        val at = (player.currentMediaItemIndex + 1).coerceAtMost(player.mediaItemCount)
        player.addMediaItem(at, mediaItemFor(Uri.parse(uri)))
        refresh()
    }

    fun enqueue(uri: String) {
        player.addMediaItem(mediaItemFor(Uri.parse(uri)))
        refresh()
    }

    fun shuffleAllHistory() {
        val uris = historyStore.recentlyPlayed(100).map { it.uri }.shuffled()
        if (uris.isNotEmpty()) {
            openStrings(uris)
        }
    }

    fun openStringsPublic(uris: List<String>) = openStrings(uris)

    private fun openStrings(uris: List<String>) {
        player.setMediaItems(uris.map { mediaItemFor(Uri.parse(it)) })
        player.prepare()
        player.play()
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
    fun userMilkPresets(): List<java.io.File> = milkAssets.userPresets()

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

    /** Plays a single library track immediately. */
    fun playTrack(uri: String) {
        player.setMediaItems(listOf(mediaItemFor(Uri.parse(uri))))
        player.prepare()
        player.play()
        currentUri = Uri.parse(uri)
        onTrackChanged()
    }

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
                        trackLibrary.updateAnalysis(uriStr, metadataReader.titleOf(uri), t.durationMs, t.bpm, t.key)
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
        val (t, a) = if (known != null) known.title to known.artist else metadataReader.quick(uri)
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
                    val (title, artist) = metadataReader.read(Uri.parse(t.uri))
                    if (title != t.title || artist != t.artist) {
                        latest = trackLibrary.updateMetadata(t.uri, title, artist)
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

    fun next() = player.seekToNextMediaItem()

    fun previous() = player.seekToPreviousMediaItem()

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(fraction: Float) {
        val d = player.duration
        if (d > 0) player.seekTo((d * fraction).toLong())
    }

    // ---- Intelligence ----

    private fun onTrackChanged() {
        timeline = null
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
                    .load(getApplication<Application>(), uri, gui.beatThresholdSigma, gui.beatMinIntervalMs)
                    ?.let { t ->
                        if (currentUri == uri) {
                            timeline = t
                            _vizState.update { it.copy(bpm = t.bpm, sections = t.detectSections()) }
                        }
                    }
            }
        }
    }

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
                val merged = trackLibrary.updateAnalysis(uri.toString(), metadataReader.titleOf(uri), t.durationMs, t.bpm, t.key)
                _library.update { it.copy(tracks = merged) }
                if (currentUri == uri) {
                    timeline = t
                    val suggestion = SceneSuggester.suggestForTrack(t)
                    _vizState.value =
                        _vizState.value.copy(
                            analyzing = false,
                            bpm = t.bpm,
                            sections = t.detectSections(),
                            suggestedSceneId = suggestion,
                        )
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
        val s = _vizState.value
        if (s.intelligenceMode != IntelligenceMode.AUTO) return
        val t = timeline ?: return
        val f = t.featuresAt(player.currentPosition)
        val suggestion = SceneSuggester.suggest(t.bpm, f.rms, f.centroid)
        if (suggestion != s.sceneId) _vizState.value = s.copy(sceneId = suggestion)
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
        // For the milkdrop scene, ALSO persist the actual .milk file so the
        // saved preset is a real MilkDrop preset the user can reload/share,
        // not just a Customize bundle. The .milk is copied into the user
        // milk-preset dir under the given name; the SceneParams bundle is
        // saved alongside so post-processing customizations are kept too.
        if (s.sceneId == SceneIds.MILKDROP) {
            activeMilkPath?.let { src -> milkAssets.savePresetCopy(src, name) }
        }
        presetStore.save(Preset(name, s.sceneId, s.attack, s.decay, customShader, s.params), folder)
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
                // Deliberately "$name.milk" rather than MilkAssetStore's
                // endsWith-aware naming: a preset literally named "x.milk"
                // saves as x.milk but has always mirrored as x.milk.milk (so
                // not at all). Kept as-is here so this stays a pure refactor.
                java.io.File(milkAssets.importDir(), "$name.milk").let { copyInto(it, "application/octet-stream") }
            }
        }
    }

    /** Path of the .milk preset currently shown, tracked so it can be saved. */
    private var activeMilkPath: String? = null

    fun noteMilkPreset(path: String) {
        activeMilkPath = path
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
    }

    fun deletePreset(name: String) {
        if (BuiltInPresets.isBuiltIn(name)) return
        presetStore.delete(name)
        _vizState.update { it.copy(presets = BuiltInPresets.ALL + presetStore.list()) }
    }

    // ---- Export ----

    fun startExport(
        aspect: ExportAspect,
        fps: Int,
        sceneFactory: SceneFactory,
        destination: Uri? = null,
    ) {
        exportCoordinator.start(aspect, fps, sceneFactory, destination)
    }

    fun cancelExport() {
        exportCoordinator.cancel()
    }

    /** Clears a finished export's result/error so the next dialog open shows the options again. */
    fun resetExportState() {
        exportCoordinator.reset()
    }

    override fun onCleared() {
        engine.stop()
        audioFxController.release()
        player.release()
    }
}
