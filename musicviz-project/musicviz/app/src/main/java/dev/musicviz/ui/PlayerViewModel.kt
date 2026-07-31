package dev.musicviz.ui

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.musicviz.analysis.AnalysisEngine
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.analysis.AudioQualityInfo
import dev.musicviz.analysis.FeatureTimeline
import dev.musicviz.analysis.IntelligenceMode
import dev.musicviz.analysis.OfflineAnalyzer
import dev.musicviz.analysis.SceneSuggester
import dev.musicviz.audio.AudioFxState
import dev.musicviz.export.ExportAspect
import dev.musicviz.export.VideoExporter
import dev.musicviz.playback.PlaybackEngine
import dev.musicviz.playback.PlaybackService
import dev.musicviz.playback.PlaybackSnapshot
import dev.musicviz.render.SwitchTiming
import dev.musicviz.render.TransitionStyle
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The transport state the screens render is now [PlaybackSnapshot], declared
// next to the player it is sampled from. Screens reach it through [uiState]
// and never name the type, so nothing outside this file changed.

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
 * Shortest time the intelligent visual playlist will hold a look before a
 * strong beat may replace it. Higher than Random mode's floor because a
 * playlist is a sequence the user arranged, not a shuffle.
 */
private const val VIZ_PLAYLIST_MIN_DWELL_MS = 8_000L

/** Random mode's equivalent floor: it is meant to feel livelier. */
private const val RANDOM_MODE_MIN_DWELL_MS = 6_000L

/**
 * Owns playback (queue + audio focus + PCM tap), live analysis, offline
 * analysis/intelligence, presets and export orchestration.
 */
@OptIn(UnstableApi::class)
class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    /**
     * Playback (ExoPlayer + queue + PCM tap + sleep timer). Borrowed from
     * [PlaybackEngine] rather than constructed: audio outlives this ViewModel,
     * so a second one here would be a second player fighting the first.
     *
     * Declared first because [engine] analyses the PCM its tap captures. Its
     * callbacks are wired in the init block below, not here, so nothing can
     * fire against a half-constructed ViewModel.
     */
    private val playback = PlaybackEngine.acquireForUi(application)
    private val engine = AnalysisEngine(playback.ring)

    // ---- Audio-quality readout ----
    // Combines the selected track's source Format (onTracksChanged) with the
    // decoded output format the read-only tap reports (playback thread), so
    // the UI can show whether playback is lossless / bit-perfect. Declared
    // before [sink] on purpose: its callback touches these fields (see the
    // construction-order note above the init block).

    /** Decoded output format from the tap's flush callback. */
    private data class TapFormat(
        val sampleRateHz: Int,
        val channelCount: Int,
        val encoding: Int,
    )

    @Volatile
    private var tapFormat: TapFormat? = null

    @Volatile
    private var sourceAudioFormat: Format? = null

    private val _audioQuality = MutableStateFlow<AudioQualityInfo?>(null)

    /** Source vs decoded-output quality of the current track; null when idle. */
    val audioQuality: StateFlow<AudioQualityInfo?> = _audioQuality

    /** Called from the playback thread on every audio-pipeline reconfigure. */
    private fun onTapFormat(
        sampleRateHz: Int,
        channelCount: Int,
        encoding: Int,
    ) {
        tapFormat = TapFormat(sampleRateHz, channelCount, encoding)
        recomputeAudioQuality()
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

    /** Container guess from the uri's file extension ("" for opaque uris). */
    private fun containerGuess(): String {
        val name = currentUri?.lastPathSegment?.substringAfterLast('/') ?: return ""
        val ext = name.substringAfterLast('.', "")
        return if (ext.length in 1..4) ext.lowercase() else ""
    }

    private fun recomputeAudioQuality() {
        val src = sourceAudioFormat
        if (src == null) {
            _audioQuality.value = null
            return
        }
        val tap = tapFormat
        _audioQuality.value =
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

    private val offlineAnalyzer = OfflineAnalyzer(application)
    private val presetStore = PresetStore(application)
    private val trackLibrary = TrackLibrary(application)
    private val themeStore = ThemeStore(application)
    private val playerPrefsStore = PlayerPrefsStore(application)
    private val textureStore = TextureStore(application)
    private val lfoStore = LfoStore(application)
    private val musicPlaylists = MusicPlaylistStore(application)
    private val exporter = VideoExporter(application)

    // Also borrowed: the effects chain hangs off the player's audio session,
    // so it has to live exactly as long as the player does.
    private val audioFxController = PlaybackEngine.audioFx(application)

    private val _uiState = MutableStateFlow(PlaybackSnapshot())
    val uiState: StateFlow<PlaybackSnapshot> = _uiState

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

    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = _exportState

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
        // Wired here, not at construction: the tap fires these from the
        // playback thread and they touch fields declared further down.
        playback.onAudioFormat = { rate, channels, encoding ->
            engine.sampleRateHz = rate
            onTapFormat(rate, channels, encoding)
        }
        playback.mediaItemFactory = ::mediaItemFor
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
        playback.applyPlaybackPrefs(p.speed, p.pitchSemitones, p.skipSilence, p.pauseOnNoisy)
    }

    /** Mirrors the player's shuffle/repeat state into the persisted prefs. */
    private fun persistPlayerOptions() {
        val p = _playerPrefs.value.copy(shuffle = playback.shuffle, repeatMode = playback.repeatMode)
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
        val n = playback.ring.copyNewSince(pcmCursor, pcmScratch)
        pcmCursor = playback.ring.lastCopyEndIndex
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

    private var timeline: FeatureTimeline? = null
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

    /** Keep the current preset: auto/random switching skips while locked. */
    private val _presetLocked = MutableStateFlow(false)
    val presetLocked: StateFlow<Boolean> = _presetLocked

    /** 0 = off, 1 = random, 2 = intelligent. */
    private val _autoMode = MutableStateFlow(0)
    val autoMode: StateFlow<Int> = _autoMode

    /**
     * Held so [onCleared] can unregister it. The player is shared across the
     * process and outlives this ViewModel, so a listener left behind would
     * keep a dead ViewModel alive and go on recording plays into its history
     * alongside its replacement's.
     */
    private val playerListener =
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

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Audio just started, so from here on it has to survive the
                // app leaving the screen. Starting the service here rather
                // than at each play/queue call site covers every route into
                // playback, including the auto-advance to the next track and
                // the notification's own transport buttons.
                if (isPlaying) PlaybackService.ensureRunning(getApplication<Application>())
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
                // bar, gestures, the notification controls), which is why
                // this hangs off the listener and not seekTo().
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
                sourceAudioFormat = fmt
                recomputeAudioQuality()
            }
        }

    init {
        engine.start(viewModelScope)
        refreshNumericTitles()
        // Restore persisted playback options onto the player. Auto-resume runs
        // BEFORE the listener registers so the startup preparation never
        // records a phantom play into history (ExoPlayer only delivers events
        // to listeners registered when they occurred).
        val pp = _playerPrefs.value
        playback.shuffle = pp.shuffle
        playback.repeatMode = pp.repeatMode
        applyPlaybackPrefs(pp)
        if (pp.autoResume) prepareLastPlayed()
        playback.addListener(playerListener)
        // The sink may already have a session id (attach ignores UNSET = 0).
        audioFxController.attach(playback.audioSessionId)
        refreshAudioFx()
        // Tick only while audio is actually moving. This used to be an
        // unconditional `while (true) { ...; delay(500) }` for the ViewModel's
        // whole life, which woke the main thread twice a second forever - with
        // the app idle, with the screen off, with nothing playing. Everything
        // in the tick is about a position that is advancing: refresh() resamples
        // it, and the three rotations below all return immediately unless
        // something is playing.
        //
        // collectLatest is what stops it: a pause cancels the inner loop, and a
        // play starts a fresh one. The pause itself is not missed, because the
        // Player.Listener calls refresh() on every event, so the final position
        // lands before the loop is cancelled.
        viewModelScope.launch {
            _uiState
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collectLatest { playing ->
                    if (!playing) return@collectLatest
                    while (true) {
                        refresh()
                        applyIntelligence()
                        advanceVizPlaylist()
                        advanceRandomMode()
                        delay(500)
                    }
                }
        }
    }

    private fun refresh() {
        _uiState.value = playback.snapshot()
    }

    // ---- Player options ----

    fun toggleShuffle() {
        playback.toggleShuffle()
        persistPlayerOptions()
        refresh()
    }

    fun cycleRepeatMode() {
        playback.cycleRepeatMode()
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
            playback.prepareOnly(uri)
            currentUri = uri
        }
    }

    // ---- Sleep timer ----

    /** Remaining sleep-timer time, or null when no timer is running. */
    val sleepTimerRemainingMs: StateFlow<Long?> = playback.sleepTimerRemainingMs

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
        playback.startSleepTimer(minutes)
    }

    /** Cancels a running sleep timer and restores full volume. */
    fun cancelSleepTimer() = playback.cancelSleepTimer()

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
        val due =
            SwitchTiming.isDue(
                elapsedMs = now - lastVizSwitchMs,
                intervalMs = s.vizPlaylistIntervalSec * 1000L,
                onStrongMoment = s.vizPlaylistIntelligent,
                beatImpulse = engine.features.value.beatImpulse,
                minDwellFloorMs = VIZ_PLAYLIST_MIN_DWELL_MS,
            )
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
        val due =
            SwitchTiming.isDue(
                elapsedMs = android.os.SystemClock.elapsedRealtime() - lastRandomSwitchMs,
                intervalMs = s.randomIntervalSec * 1000L,
                onStrongMoment = s.randomOnBeat,
                beatImpulse = engine.features.value.beatImpulse,
                minDwellFloorMs = RANDOM_MODE_MIN_DWELL_MS,
            )
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

    /** Embedded-tag metadata read from a file; fields blank/zero when absent. */
    private data class FileMeta(
        val title: String,
        val artist: String = "",
        val album: String = "",
        val genre: String = "",
        val year: Int = 0,
        val trackNo: Int = 0,
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
        if (title == null) {
            title =
                runCatching {
                    app.contentResolver
                        .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()?.substringBeforeLast('.')
        }
        return FileMeta(
            title = title ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Track",
            artist = artist ?: "",
            album = album,
            genre = genre,
            year = year,
            trackNo = trackNo,
        )
    }

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
        currentUri = playback.setQueue(uris.map { Uri.parse(it) }, startIndex)
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
        playback.addNext(Uri.parse(uri))
        refresh()
    }

    fun enqueue(uri: String) {
        playback.addLast(Uri.parse(uri))
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
        playback.setQueue(uris.map { Uri.parse(it) })
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

    fun seekBy(deltaMs: Long) = playback.seekBy(deltaMs)

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
        currentUri = playback.setQueue(listOf(Uri.parse(uri)))
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
                        latest = trackLibrary.updateMetadata(t.uri, title, artist)
                    }
                }
            }
            latest?.let { l -> withContext(Dispatchers.Main) { _library.update { it.copy(tracks = l) } } }
        }
    }

    fun open(uris: List<Uri>) {
        if (uris.isEmpty()) return
        currentUri = playback.setQueue(uris)
        onTrackChanged()
    }

    /** Human-readable labels for the playback queue, in play order. */
    fun queueTitles(): List<String> = playback.queueTitles()

    /** Jumps playback to the given queue position. */
    fun playQueueIndex(index: Int) = playback.playQueueIndex(index)

    fun next() = playback.next()

    fun previous() = playback.previous()

    fun togglePlayPause() = playback.togglePlayPause()

    fun seekTo(fraction: Float) = playback.seekToFraction(fraction)

    // ---- Intelligence ----

    private fun onTrackChanged() {
        // Before anything else: the live analyzer's beat grid, energy envelope
        // and flux history all describe the track that just ended.
        engine.reset()
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

    fun setIntelligenceMode(mode: IntelligenceMode) {
        _vizState.update { it.copy(intelligenceMode = mode) }
        if (mode != IntelligenceMode.MANUAL && timeline == null) {
            analyzeCurrentTrack()
        } else {
            // Act on the switch now rather than on the next tick: with an
            // already-analysed track the tick may be up to half a second away,
            // and while paused it never comes at all.
            applyIntelligence()
        }
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
                val merged = trackLibrary.updateAnalysis(uri.toString(), titleFor(uri), t.durationMs, t.bpm, t.key)
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
        val f = t.featuresAt(playback.positionMs)
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
            activeMilkPath?.let { src ->
                runCatching {
                    val app = getApplication<Application>()
                    val dir = java.io.File(app.filesDir, "milk").apply { mkdirs() }
                    val dest = java.io.File(dir, if (name.endsWith(".milk")) name else "$name.milk")
                    java.io.File(src).copyTo(dest, overwrite = true)
                }
            }
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
                java.io.File(java.io.File(app.filesDir, "milk"), "$name.milk").let { copyInto(it, "application/octet-stream") }
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
        sceneFactory: VideoExporter.SceneFactory,
        destination: Uri? = null,
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
                            destination = destination,
                            onProgress = { p ->
                                _exportState.update { it.copy(progress = 0.2f + p * 0.8f) }
                            },
                            isCancelled = { exportCancelled },
                        )
                    _exportState.value =
                        ExportUiState(
                            running = false,
                            progress = 1f,
                            resultUri = result,
                            customDestination = destination != null,
                        )
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

    /**
     * Drops this ViewModel's hold on playback without stopping it. The player
     * and its effects chain belong to [PlaybackEngine] and keep going in the
     * service; only the things that exist to feed a screen — the live analyzer
     * and this ViewModel's player listener — go away with the screen.
     */
    override fun onCleared() {
        engine.stop()
        playback.removeListener(playerListener)
        PlaybackEngine.releaseUi()
    }
}
