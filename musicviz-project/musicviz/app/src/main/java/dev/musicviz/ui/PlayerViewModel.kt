package dev.musicviz.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.musicviz.analysis.AnalysisEngine
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.analysis.FeatureTimeline
import dev.musicviz.analysis.IntelligenceMode
import dev.musicviz.analysis.OfflineAnalyzer
import dev.musicviz.analysis.SceneSuggester
import dev.musicviz.audio.PcmRingBuffer
import dev.musicviz.audio.PcmTapSink
import dev.musicviz.audio.TapRenderersFactory
import dev.musicviz.export.ExportAspect
import dev.musicviz.export.VideoExporter
import dev.musicviz.render.TransitionStyle
import dev.musicviz.render.scene.PcmChunk
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
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
data class MilkFile(val name: String, val path: String)

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
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val ring = PcmRingBuffer()
    private val engine = AnalysisEngine(ring)
    private val sink = PcmTapSink(ring) { rate -> engine.sampleRateHz = rate }
    private val offlineAnalyzer = OfflineAnalyzer(application)
    private val presetStore = PresetStore(application)
    private val trackLibrary = TrackLibrary(application)
    private val themeStore = ThemeStore(application)
    private val textureStore = TextureStore(application)
    private val lfoStore = LfoStore(application)
    private val musicPlaylists = MusicPlaylistStore(application)
    private val exporter = VideoExporter(application)

    val player: ExoPlayer =
        ExoPlayer
            .Builder(application, TapRenderersFactory(application, sink))
            // AIFF/AIFC support: Media3 ships no AIFF extractor, so ours is
            // appended after the defaults (sniff order keeps defaults first).
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    application,
                    androidx.media3.extractor.ExtractorsFactory {
                        androidx.media3.extractor.DefaultExtractorsFactory().createExtractors() +
                            dev.musicviz.audio.AiffExtractor()
                    },
                ),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .build()

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

    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = _exportState

    private val _library = MutableStateFlow(LibraryState(trackLibrary.list(), musicPlaylists.list()))
    val library: StateFlow<LibraryState> = _library

    private val _theme = MutableStateFlow(themeStore.load())
    val theme: StateFlow<AppTheme> = _theme

    private val _guiPrefs = MutableStateFlow(themeStore.loadGui())

    init {
        engine.beatThresholdSigma = _guiPrefs.value.beatThresholdSigma
        // Apply the restored reactivity to the engine (setReactivity normally
        // does this, but the restored values arrive outside that path).
        engine.smoother.attack = _vizState.value.attack
        engine.smoother.decay = _vizState.value.decay
    }

    val guiPrefs: StateFlow<GuiPrefs> = _guiPrefs

    fun setGuiPrefs(prefs: GuiPrefs) {
        themeStore.saveGui(prefs)
        _guiPrefs.value = prefs
        engine.beatThresholdSigma = prefs.beatThresholdSigma
    }

    fun setTheme(theme: AppTheme) {
        themeStore.save(theme)
        _theme.value = theme
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

    /** Randomizes every unlocked Customize parameter within its slider range. */
    fun randomizeParams() {
        val locked = _lockedParams.value
        val rnd = java.util.Random()

        fun f(
            lo: Float,
            hi: Float,
        ) = lo + rnd.nextFloat() * (hi - lo)
        var p = _vizState.value.params

        fun r(
            label: String,
            block: () -> SceneParams,
        ) {
            if (label !in locked) p = block()
        }
        r("Speed") { p.copy(speed = f(0.2f, 2.5f)) }
        r("Zoom") { p.copy(zoom = f(0.6f, 2f)) }
        r("Rotation") { p.copy(rotation = f(-1.5f, 1.5f)) }
        r("Sway") { p.copy(sway = f(0f, 0.8f)) }
        r("Drift X") { p.copy(driftX = f(-0.5f, 0.5f)) }
        r("Drift Y") { p.copy(driftY = f(-0.5f, 0.5f)) }
        r("Beat pulse") { p.copy(pulse = f(0f, 1f)) }
        r("Beat shake") { p.copy(shake = f(0f, 0.7f)) }
        r("Warp") { p.copy(warp = f(0f, 0.8f)) }
        r("Ripple") { p.copy(ripple = f(0f, 0.8f)) }
        r("Twist") { p.copy(twist = f(-0.8f, 0.8f)) }
        r("Tile") { p.copy(tile = f(1f, 4f)) }
        r("Morph") { p.copy(morph = f(0f, 0.8f)) }
        r("Kaleidoscope") { p.copy(kaleidoscope = rnd.nextInt(3) == 0, symmetry = 2 + rnd.nextInt(7)) }
        r("Turbulence") { p.copy(turbulence = f(0f, 1f)) }
        r("Audio drive") { p.copy(audioDrive = f(0.6f, 1.8f)) }
        r("Beat response") { p.copy(beatResponse = f(0.3f, 2f)) }
        r("Palette") { p.copy(palette = rnd.nextInt(18)) }
        r("Palette 2") { p.copy(palette2 = rnd.nextInt(18)) }
        r("Palette mix") { p.copy(paletteMix = f(0f, 1f)) }
        r("Hue range") { p.copy(hueRange = f(0.5f, 1.5f)) }
        r("Saturation") { p.copy(saturation = f(0.4f, 1.4f)) }
        r("Brightness") { p.copy(brightness = f(0.7f, 1.3f)) }
        r("Contrast") { p.copy(contrast = f(0.8f, 1.3f)) }
        r("Intensity") { p.copy(intensity = f(0.7f, 1.4f)) }
        r("Bloom") { p.copy(bloom = f(0f, 0.7f)) }
        r("Temperature") { p.copy(temperature = f(-0.6f, 0.6f)) }
        r("Chromatic aberration") { p.copy(chromaAb = f(0f, 0.5f)) }
        r("Vignette") { p.copy(vignette = f(0f, 0.6f)) }
        r("Scanlines") { p.copy(scanlines = f(0f, 0.5f)) }
        r("Grain") { p.copy(grain = f(0f, 0.4f)) }
        r("Glitch") { p.copy(glitch = f(0f, 0.4f)) }
        r("Fisheye") { p.copy(fisheye = f(0f, 0.5f)) }
        r("Flash") { p.copy(flash = f(0f, 0.6f)) }
        // Fluid scene (curated ranges so a roll stays watchable; quality and
        // the FlowField master toggle are deliberately never randomized).
        r("Fluid curl") { p.copy(fluidCurl = f(5f, 45f)) }
        r("Motion fade") { p.copy(fluidVelocityDissipation = f(0.02f, 0.6f)) }
        r("Fluid fade") { p.copy(fluidDensityDissipation = f(0.2f, 2.2f)) }
        r("Chromatic aging") { p.copy(fluidChromaticAging = f(0f, 0.8f)) }
        r("Beat pattern") { p.copy(fluidBeatPattern = rnd.nextInt(4)) }
        r("Beat splats") { p.copy(fluidBeatSplats = 1 + rnd.nextInt(6)) }
        r("Stirrers") { p.copy(fluidStirrers = rnd.nextInt(4)) }
        r("Stirrer speed") { p.copy(fluidStirrerSpeed = f(0.3f, 1.6f)) }
        r("Fluid splat radius") { p.copy(fluidSplatRadius = f(0.05f, 0.25f)) }
        r("Fluid splat force") { p.copy(fluidSplatForce = f(0.5f, 2f)) }
        r("Bass pump") { p.copy(fluidBassPump = rnd.nextInt(4) == 0) }
        r("Particle drag") { p.copy(fluidParticleDrag = f(0.15f, 0.9f)) }
        r("Fluid glow") { p.copy(fluidBloomIntensity = f(0.4f, 1.4f)) }
        r("Glow threshold") { p.copy(fluidBloomThreshold = f(0.4f, 0.8f)) }
        r("Sunrays weight") { p.copy(fluidSunraysWeight = f(0.4f, 1f)) }
        // Journey (spawn/catch progression); the progression amount itself is
        // never randomized - it expresses how much the song drives the look.
        r("Path") { p.copy(fluidSpawnPath = rnd.nextInt(SceneParams.FLUID_PATHS.size)) }
        r("Spawn points") { p.copy(fluidSpawnPoints = 2 + rnd.nextInt(4)) }
        r("Catch points") { p.copy(fluidCatchPoints = rnd.nextInt(4)) }
        r("Catch pull") { p.copy(fluidCatchPull = f(0.4f, 1.8f)) }
        r("Catch radius") { p.copy(fluidCatchRadius = f(0.06f, 0.2f)) }
        r("Particle life (s)") { p.copy(fluidParticleLife = f(3f, 12f)) }
        r("Treble sparkle") { p.copy(fluidSparkle = rnd.nextInt(3) != 0) }
        setSceneParams(p)
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
                importDir().listFiles { f -> f.extension == "milk" }.orEmpty()
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
                    importDir().listFiles { f -> f.extension == "milk" }.orEmpty()
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

    init {
        engine.start(viewModelScope)
        refreshNumericTitles()
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
                                    ?: player.currentMediaItem?.localConfiguration?.uri?.lastPathSegment.orEmpty()
                            historyStore.recordPlay(u.toString(), title)
                            _historyTick.update { it + 1 }
                        }
                        onTrackChanged()
                    }
                }
            },
        )
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
        refresh()
    }

    fun cycleRepeatMode() {
        player.repeatMode =
            when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        refresh()
    }

    // ---- Visual playlist ----

    private val _vizApply = MutableSharedFlow<VizApply>(extraBufferCapacity = 8)

    /** Renderer side effects (milk preset loads, custom shaders) to apply. */
    val vizApply: SharedFlow<VizApply> = _vizApply

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
            _vizState.value =
                cur.copy(
                    params =
                        cur.params.copy(
                            palette = randomRng.nextInt(SceneParams.PALETTES.size),
                            palette2 = randomRng.nextInt(SceneParams.PALETTES.size),
                            paletteMix = if (randomRng.nextBoolean()) randomRng.nextFloat() * 0.6f else 0f,
                            colorShift = randomRng.nextFloat(),
                        ),
                )
        }
    }

    /** Applies a playlist entry: scene, saved preset params and side effects. */
    fun applyVizEntry(entry: VizPlaylistEntry) {
        selectScene(entry.sceneId)
        var shader: String? = null
        if (entry.presetName != null) {
            _vizState.value.presets.firstOrNull { it.name == entry.presetName && it.sceneId == entry.sceneId }
                ?.let { shader = applyPreset(it) }
        }
        if (entry.milkPath != null || shader != null) {
            _vizApply.tryEmit(VizApply(milkPath = entry.milkPath, customShader = shader, sceneId = entry.sceneId))
        }
    }

    // ---- Music library & playlists ----

    /**
     * Resolves (title, artist) the way real media players do: embedded tags
     * first (MediaMetadataRetriever), then the provider's display name, and
     * only then the URI path - so content URIs never surface as bare
     * document numbers. Call on Dispatchers.IO; the retriever hits disk.
     */
    private fun metadataFor(uri: Uri): Pair<String, String> {
        val app = getApplication<Application>()
        var title: String? = null
        var artist: String? = null
        runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(app, uri)
                title = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null }
                artist = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.ifBlank { null }
            } finally {
                runCatching { r.release() }
            }
        }
        if (title == null) {
            title =
                runCatching {
                    app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull()?.substringBeforeLast('.')
        }
        return (title ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Track") to (artist ?: "")
    }

    private fun titleFor(uri: Uri): String = metadataFor(uri).first

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
                    metadataFor(uri).let { (t, a) ->
                        LibraryTrack(uri = uri.toString(), title = t, artist = a)
                    }
                }
            val merged = trackLibrary.addAll(tracks)
            _library.update { it.copy(tracks = merged) }
        }
    }

    /**
     * Analysis with the persistent cache: a hit skips the whole offline
     * pass (the dominant cost of export). Call on Dispatchers.IO.
     */
    private suspend fun analyzeCached(
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): dev.musicviz.analysis.FeatureTimeline {
        val app = getApplication<Application>()
        dev.musicviz.analysis.AnalysisCache.load(app, uri)?.let {
            onProgress(1f)
            return it
        }
        return offlineAnalyzer.analyze(uri, onProgress).also {
            dev.musicviz.analysis.AnalysisCache.save(app, uri, it)
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
            val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, treeUri) ?: return@runCatching

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
                            found +=
                                metadataFor(f.uri).let { (t, a) ->
                                    LibraryTrack(uri = f.uri.toString(), title = t, artist = a)
                                }
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
        val names = _library.value.playlists.firstOrNull { it.name == playlist }?.trackUris.orEmpty()
        return names.map { uri -> byUri[uri] ?: LibraryTrack(uri = uri, title = titleFor(Uri.parse(uri))) }
    }

    /** Plays a music playlist from the given start index. */
    fun playPlaylist(
        playlist: String,
        startIndex: Int = 0,
    ) {
        val uris = _library.value.playlists.firstOrNull { it.name == playlist }?.trackUris.orEmpty()
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
    fun userMilkPresets(): List<java.io.File> {
        val dir = java.io.File(getApplication<Application>().filesDir, "milk")
        return dir.listFiles { f -> f.isFile && f.extension == "milk" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    fun seekBy(deltaMs: Long) {
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, player.duration.coerceAtLeast(0L)))
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
        val uris = _library.value.playlists.firstOrNull { it.name == playlist }?.trackUris.orEmpty()
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
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(t)
                    .setArtist(a.ifBlank { null })
                    .build(),
            )
            .build()
    }

    /** Main-thread-safe metadata: display name only (no retriever I/O). */
    private fun metadataQuick(uri: Uri): Pair<String, String> {
        val app = getApplication<Application>()
        val name =
            runCatching {
                app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
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
                ?: item.localConfiguration?.uri?.lastPathSegment?.substringAfterLast('/')
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
        if (_vizState.value.intelligenceMode != IntelligenceMode.MANUAL) analyzeCurrentTrack()
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
                timeline = t
                currentUri?.let { u ->
                    val merged = trackLibrary.updateAnalysis(u.toString(), titleFor(u), t.durationMs, t.bpm, t.key)
                    _library.update { it.copy(tracks = merged) }
                }
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
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(app, Uri.parse(uriStr))
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
     *  of the detected BPM (renderer's displayedParams does the lerp). */
    private fun morphedParams(p: dev.musicviz.render.scene.SceneParams): dev.musicviz.render.scene.SceneParams {
        val beats = _guiPrefs.value.morphBeats
        if (beats <= 0) return p
        val bpm = features.value.bpm.takeIf { it > 40f } ?: 120f
        val sec = beats * 60f / bpm
        return p.copy(paramFadeSec = maxOf(p.paramFadeSec, sec))
    }

    fun applyPreset(preset: Preset): String? {
        setReactivity(preset.attack, preset.decay)
        selectScene(preset.sceneId)
        setSceneParams(morphedParams(preset.params))
        return preset.customShader
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
                    val t =
                        timeline ?: analyzeCached(uri) { p ->
                            _exportState.update { it.copy(progress = p * 0.2f) }
                        }.also { timeline = it }
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

    override fun onCleared() {
        engine.stop()
        player.release()
    }
}
