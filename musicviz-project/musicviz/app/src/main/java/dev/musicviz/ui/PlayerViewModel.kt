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
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val title: String? = null,
    val hasMedia: Boolean = false,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
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
)

data class ExportUiState(
    val running: Boolean = false,
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
    private val exporter = VideoExporter(application)

    val player: ExoPlayer =
        ExoPlayer
            .Builder(application, TapRenderersFactory(application, sink))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .build()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState

    private val _vizState = MutableStateFlow(VizUiState(presets = presetStore.list()))
    val vizState: StateFlow<VizUiState> = _vizState

    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = _exportState

    val features: StateFlow<AudioFeatures> = engine.features

    private val pcmScratch = FloatArray(1024)

    /** Newest mono PCM window for the milkdrop scene; called from the GL thread. */
    fun latestPcm(): FloatArray? = if (ring.snapshotLatest(pcmScratch)) pcmScratch else null

    /** Copies a user-picked .milk preset into app storage; returns the file path. */
    fun importMilkPreset(uri: Uri): String? =
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

    init {
        engine.start(viewModelScope)
        player.addListener(
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    refresh()
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                        currentUri = player.currentMediaItem?.localConfiguration?.uri
                        onTrackChanged()
                    }
                }
            },
        )
        viewModelScope.launch {
            while (true) {
                refresh()
                applyIntelligence()
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
                title = player.mediaMetadata.title?.toString(),
                hasMedia = player.currentMediaItem != null,
                queueSize = player.mediaItemCount,
                queueIndex = player.currentMediaItemIndex,
            )
    }

    // ---- Queue ----

    fun open(uris: List<Uri>) {
        if (uris.isEmpty()) return
        player.setMediaItems(uris.map { MediaItem.fromUri(it) })
        player.prepare()
        player.play()
        currentUri = uris.first()
        onTrackChanged()
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
        _vizState.value = _vizState.value.copy(suggestedSceneId = null, bpm = 0f, sections = emptyList())
        if (_vizState.value.intelligenceMode != IntelligenceMode.MANUAL) analyzeCurrentTrack()
    }

    fun setIntelligenceMode(mode: IntelligenceMode) {
        _vizState.value = _vizState.value.copy(intelligenceMode = mode)
        if (mode != IntelligenceMode.MANUAL && timeline == null) analyzeCurrentTrack()
    }

    fun analyzeCurrentTrack() {
        val uri = currentUri ?: return
        if (_vizState.value.analyzing) return
        _vizState.value = _vizState.value.copy(analyzing = true, analysisProgress = 0f)
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val t =
                    offlineAnalyzer.analyze(uri) { p ->
                        _vizState.value = _vizState.value.copy(analysisProgress = p)
                    }
                timeline = t
                val suggestion = SceneSuggester.suggestForTrack(t)
                _vizState.value =
                    _vizState.value.copy(
                        analyzing = false,
                        bpm = t.bpm,
                        sections = t.detectSections(),
                        suggestedSceneId = suggestion,
                    )
                applyIntelligence()
            } catch (t: Throwable) {
                _vizState.value = _vizState.value.copy(analyzing = false)
            }
        }
    }

    private fun applyIntelligence() {
        val s = _vizState.value
        if (s.intelligenceMode != IntelligenceMode.AUTO) return
        val t = timeline ?: return
        val f = t.featuresAt(player.currentPosition)
        val suggestion = SceneSuggester.suggest(t.bpm, f.rms, f.centroid)
        if (suggestion != s.sceneId) _vizState.value = s.copy(sceneId = suggestion)
    }

    // ---- Visual settings ----

    fun selectScene(sceneId: String) {
        _vizState.value = _vizState.value.copy(sceneId = sceneId)
    }

    fun setReactivity(
        attack: Float,
        decay: Float,
    ) {
        engine.smoother.attack = attack
        engine.smoother.decay = decay
        _vizState.value = _vizState.value.copy(attack = attack, decay = decay)
    }

    fun setSceneParams(params: SceneParams) {
        _vizState.value = _vizState.value.copy(params = params)
    }

    fun reportShaderError(error: String?) {
        _vizState.value = _vizState.value.copy(shaderError = error)
    }

    fun savePreset(
        name: String,
        customShader: String?,
    ) {
        val s = _vizState.value
        presetStore.save(Preset(name, s.sceneId, s.attack, s.decay, customShader))
        _vizState.value = s.copy(presets = presetStore.list())
    }

    fun applyPreset(preset: Preset): String? {
        setReactivity(preset.attack, preset.decay)
        selectScene(preset.sceneId)
        setSceneParams(preset.params)
        return preset.customShader
    }

    fun deletePreset(name: String) {
        presetStore.delete(name)
        _vizState.value = _vizState.value.copy(presets = presetStore.list())
    }

    // ---- Export ----

    fun startExport(
        aspect: ExportAspect,
        sceneFactory: VideoExporter.SceneFactory,
    ) {
        val uri = currentUri ?: return
        if (_exportState.value.running) return
        exportCancelled = false
        _exportState.value = ExportUiState(running = true)
        exportJob =
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val t =
                        timeline ?: offlineAnalyzer.analyze(uri) { p ->
                            _exportState.value = _exportState.value.copy(progress = p * 0.2f)
                        }.also { timeline = it }
                    val name = "musicviz_${System.currentTimeMillis()}.mp4"
                    val result =
                        exporter.export(
                            audioUri = uri,
                            timeline = t,
                            sceneFactory = sceneFactory,
                            aspect = aspect,
                            fileName = name,
                            onProgress = { p ->
                                _exportState.value = _exportState.value.copy(progress = 0.2f + p * 0.8f)
                            },
                            isCancelled = { exportCancelled },
                        )
                    _exportState.value = ExportUiState(running = false, progress = 1f, resultUri = result)
                } catch (t: Throwable) {
                    _exportState.value = ExportUiState(running = false, error = t.message ?: "Export failed")
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
