package dev.musicviz.ui

import android.net.Uri
import dev.musicviz.analysis.FeatureTimeline
import dev.musicviz.export.ExportAspect
import dev.musicviz.export.VideoExporter
import dev.musicviz.render.AdsrConfig
import dev.musicviz.render.LfoConfig
import dev.musicviz.render.scene.SceneFactory
import dev.musicviz.render.scene.SceneParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Beat-detection settings in force right now, applied to every export.
 *
 * [minIntervalMs] is a Float because that is what the refractory gate takes —
 * it comes off a slider and feeds [FeatureTimeline.withBeatSensitivity]
 * directly.
 */
data class BeatSensitivity(
    val thresholdSigma: Float,
    val minIntervalMs: Float,
)

/** The visual configuration an export renders with, sampled when it starts. */
data class ExportVisuals(
    val params: SceneParams,
    val lfoConfigs: List<LfoConfig>,
    val adsrConfigs: List<AdsrConfig>,
)

/**
 * Everything [ExportCoordinator] needs from the rest of the app. Naming it
 * here is the point: this is the complete list of what export reaches into,
 * and `timeline` in particular is state it shares with live analysis.
 */
interface ExportSource {
    /** The track to export, or null when nothing is loaded. */
    val currentUri: Uri?

    /** Analysis already in memory, if the current track has been analysed. */
    val timeline: FeatureTimeline?

    /** Offers a freshly analysed timeline back for reuse by live playback. */
    fun cacheTimeline(
        uri: Uri,
        timeline: FeatureTimeline,
    )

    /** Runs the offline analysis, or loads it from the persistent cache. */
    suspend fun analyze(
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): FeatureTimeline

    fun beatSensitivity(): BeatSensitivity

    fun visuals(): ExportVisuals

    /**
     * Publishes the section context the exporter is about to journey through,
     * so live playback of the same track re-seats identically from now on.
     */
    fun publishJourney(
        uri: Uri,
        timeline: FeatureTimeline,
    )
}

/**
 * Orchestrates a video export: resolve the analysis, re-decide its beats at
 * the current sensitivity, run [VideoExporter], and report progress/result as
 * one [ExportUiState].
 *
 * The first 20% of the reported progress is analysis, the rest is encoding.
 */
class ExportCoordinator(
    private val scope: CoroutineScope,
    private val exporter: VideoExporter,
    private val source: ExportSource,
) {
    private val _state = MutableStateFlow(ExportUiState())

    /** Progress, result and errors for the export UI. */
    val state: StateFlow<ExportUiState> = _state

    @Volatile
    private var cancelled = false

    /** Starts an export; a no-op while one is already running. */
    fun start(
        aspect: ExportAspect,
        fps: Int,
        sceneFactory: SceneFactory,
        destination: Uri? = null,
    ) {
        val uri = source.currentUri ?: return
        if (_state.value.running) return
        cancelled = false
        _state.value = ExportUiState(running = true, customDestination = destination != null)
        scope.launch(Dispatchers.Default) {
            try {
                val analysed =
                    source.timeline ?: source
                        .analyze(uri) { p -> _state.update { it.copy(progress = p * 0.2f) } }
                        .also { source.cacheTimeline(uri, it) }
                // Always re-decide the beats from the stored onset curve at the
                // sensitivity in force right now: the in-memory timeline may
                // have been analysed (or last re-decided) under other settings,
                // and a video that flashes differently from the playback the
                // user just watched is the whole bug this guards against.
                val sensitivity = source.beatSensitivity()
                val t = analysed.withBeatSensitivity(sensitivity.thresholdSigma, sensitivity.minIntervalMs)
                source.publishJourney(uri, t)
                val visuals = source.visuals()
                val result =
                    exporter.export(
                        audioUri = uri,
                        timeline = t,
                        sceneFactory = sceneFactory,
                        aspect = aspect,
                        fileName = "musicviz_${System.currentTimeMillis()}.mp4",
                        sceneParams = visuals.params,
                        lfoConfigs = visuals.lfoConfigs,
                        adsrConfigs = visuals.adsrConfigs,
                        requestedFps = fps,
                        destination = destination,
                        onProgress = { p -> _state.update { it.copy(progress = 0.2f + p * 0.8f) } },
                        isCancelled = { cancelled },
                    )
                _state.value =
                    ExportUiState(
                        running = false,
                        progress = 1f,
                        resultUri = result,
                        customDestination = destination != null,
                    )
            } catch (t: Throwable) {
                if (cancelled) {
                    // User-initiated cancel (can surface as our own
                    // CancellationException from the transcoder): not an
                    // error, just reset the state.
                    _state.value = ExportUiState(running = false)
                } else if (t is CancellationException) {
                    _state.value = ExportUiState(running = false)
                    throw t
                } else {
                    val detail = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
                    _state.value = ExportUiState(running = false, error = detail)
                }
            }
        }
    }

    /** Asks a running export to stop at its next cancellation check. */
    fun cancel() {
        cancelled = true
    }

    /** Clears a finished result/error so the next dialog open shows the options again. */
    fun reset() {
        if (!_state.value.running) _state.value = ExportUiState()
    }
}
