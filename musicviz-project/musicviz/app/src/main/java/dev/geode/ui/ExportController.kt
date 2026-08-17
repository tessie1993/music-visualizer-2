package dev.geode.ui

import android.app.Application
import android.net.Uri
import dev.geode.analysis.FeatureTimeline
import dev.geode.data.PerformanceTake
import dev.geode.export.ExportAspect
import dev.geode.export.ExportRange
import dev.geode.export.ExportRun
import dev.geode.export.ExportService
import dev.geode.export.VideoExporter
import dev.geode.render.scene.SceneParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Clip list and export progress behind the Studio tab. */
data class StudioUiState(
    val clips: List<dev.geode.export.StudioClip> = emptyList(),
    val loading: Boolean = false,
    val running: Boolean = false,
    val progress: Float = 0f,
    /** Where the finished file landed, for the Share and Open actions. */
    val resultUri: Uri? = null,
    val error: String? = null,
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
 * Lifted out of [ExportController.startExport] because this mapping is the only
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
 * The scene the video export should build when it replays [take]: the take's
 * first scene event, falling back to [liveSceneId] when the take is missing,
 * empty, or carries no scene. Top-level so the headless suite can pin it
 * without an export pipeline. Calling [PerformanceTake.Timeline.stateAt] at 0
 * leaves the take's forward-walking cursor at the start, where the export's
 * ascending per-frame reads expect it.
 */
internal fun exportSceneIdFor(
    take: PerformanceTake.Timeline?,
    liveSceneId: String,
): String =
    take
        ?.stateAt(0L)
        ?.sceneId
        ?.takeIf { it.isNotEmpty() }
        ?: liveSceneId

/**
 * Video export and the Studio editor, extracted from [PlayerViewModel]: the
 * export pipeline state, its cancel flag and both exporters live here; what
 * an export needs from the rest of the app arrives through [Host], so the
 * dependency surface is a named contract instead of a reach into the
 * ViewModel's internals.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ExportController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    /** What an export reads from the player/visual state, named explicitly. */
    interface Host {
        /** Track to export; null = nothing loaded, the export refuses to start. */
        val exportUri: Uri?

        /** The player's in-memory analysis for [exportUri], if it has one. */
        var cachedTimeline: FeatureTimeline?

        suspend fun analyze(
            uri: Uri,
            onProgress: (Float) -> Unit,
        ): FeatureTimeline

        val guiPrefs: GuiPrefs

        /** Live scene id and params, rendered when no take is chosen. */
        val sceneId: String
        val sceneParams: SceneParams

        fun lfoConfigs(): List<dev.geode.render.LfoConfig>

        fun adsrConfigs(): List<dev.geode.render.AdsrConfig>

        /** The chosen export take, loaded from disk; null for live settings. */
        fun loadExportTake(): PerformanceTake.Timeline?

        /**
         * Publish the bpm + section grid derived for [uri] so live playback of
         * the same track re-seats identically from now on.
         */
        fun publishSections(
            uri: Uri,
            timeline: FeatureTimeline,
        )
    }

    private val exporter = VideoExporter(application)
    private val studioExporter = dev.geode.export.StudioExporter(application)

    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = _exportState

    private val _studio = MutableStateFlow(StudioUiState())

    /** Clip list and export progress for the Studio tab. */
    val studio: StateFlow<StudioUiState> = _studio

    @Volatile
    private var exportCancelled = false

    private var exportJob: Job? = null

    init {
        // A render started before this controller existed - the user left the
        // app mid-export and came back - is adopted rather than ignored, so the
        // UI shows progress instead of an idle dialog over a running encoder.
        if (ExportRun.running) {
            scope.launch {
                ExportRun.state.collect { run ->
                    if (!run.running) {
                        if (_exportState.value.running) _exportState.value = ExportUiState(running = false)
                        return@collect
                    }
                    _exportState.value = ExportUiState(running = true, progress = run.progress ?: 0f)
                }
            }
        }
    }

    private var studioJob: Job? = null

    fun startExport(
        aspect: ExportAspect,
        fps: Int,
        sceneFactory: VideoExporter.SceneFactory,
        destination: Uri? = null,
        /** Trim to whole bars so the clip loops without a stumble. */
        loopSafe: Boolean = false,
        /** Null renders the whole track; see [ExportRange]. */
        range: ExportRange? = null,
        /**
         * Builds a factory for an arbitrary scene id, so a chosen export take
         * renders on the style it was RECORDED on ([exportSceneIdFor]) rather
         * than whatever style happens to be live when Export is pressed.
         * Null (or no take) keeps [sceneFactory]. The take's scene id has to
         * be read off disk, which is why this is a resolver and not a value.
         */
        sceneFactoryFor: ((String) -> VideoExporter.SceneFactory)? = null,
    ) {
        val uri = host.exportUri ?: return
        // Both guards: the local one is the ordinary re-tap, the process-wide
        // one catches a fresh ViewModel started while an earlier render is
        // still holding the encoder.
        if (_exportState.value.running || ExportRun.running) return
        exportCancelled = false
        _exportState.value = ExportUiState(running = true, customDestination = destination != null)
        // Announce the run BEFORE starting the service: the service reads its
        // first notification straight out of this state, and one that starts
        // against an idle run stops itself immediately.
        ExportRun.begin(uri.lastPathSegment.orEmpty())
        ExportService.start(application)
        exportJob =
            ExportRun.scope.launch(Dispatchers.Default) {
                try {
                    val analysed =
                        host.cachedTimeline ?: host.analyze(uri) { p ->
                            _exportState.update { it.copy(progress = p * 0.2f) }
                            ExportRun.publish(p * 0.2f)
                        }.also { if (host.exportUri == uri) host.cachedTimeline = it }
                    // Always re-decide the beats from the stored onset curve
                    // at the sensitivity in force right now: the in-memory
                    // timeline may have been analysed (or last re-decided)
                    // under other settings, and a video that flashes
                    // differently from the playback the user just watched is
                    // the whole bug this guards against.
                    val gui = host.guiPrefs
                    val t =
                        analysed.withBeatSensitivity(
                            gui.beatSensitivity,
                            // Same floor the live engine runs under, or an
                            // export would flash faster than the screen did.
                            gui.effectiveBeatMinIntervalMs,
                        )
                    // Publish the section context the exporter is about to
                    // journey through, so live playback of the same track
                    // re-seats identically from now on (journey parity even
                    // in MANUAL mode, where onTrackChanged only reads cache).
                    host.publishSections(uri, t)
                    val name = "geode_${System.currentTimeMillis()}.mp4"
                    // A chosen take renders the performance instead of the
                    // live settings. Loaded once, outside the frame loop: the
                    // Timeline is a stateful cursor, and the export coroutine
                    // is its only reader.
                    val exportTake = host.loadExportTake()
                    // Take export honesty, first half: the scene comes from
                    // the take's own first scene event, not from whatever the
                    // user was looking at. Mid-take scene switches still do
                    // not render (see TakeUiState.exportTake).
                    val factory =
                        if (exportTake != null && sceneFactoryFor != null) {
                            sceneFactoryFor(exportSceneIdFor(exportTake, host.sceneId))
                        } else {
                            sceneFactory
                        }
                    val result =
                        exporter.export(
                            audioUri = uri,
                            timeline = t,
                            sceneFactory = factory,
                            aspect = aspect,
                            fileName = name,
                            sceneParams = host.sceneParams,
                            lfoConfigs = host.lfoConfigs(),
                            adsrConfigs = host.adsrConfigs(),
                            safety = gui.safety,
                            requestedFps = fps,
                            paramsAt =
                                exportTake
                                    ?.let { take -> { ms: Long -> take.stateAt(ms)?.params ?: host.sceneParams } },
                            loopSafe = loopSafe,
                            range = range,
                            destination = destination,
                            onProgress = { p ->
                                val overall = 0.2f + p * 0.8f
                                _exportState.update { it.copy(progress = overall) }
                                ExportRun.publish(overall)
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
                } finally {
                    // Saved, cancelled and failed alike: the service's only
                    // question is whether the process still has work to
                    // protect, and it does not. In a finally so a throw on the
                    // CancellationException path cannot leave a foreground
                    // notification standing over nothing.
                    ExportRun.finish()
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

    /** Re-reads Movies/Geode. Cheap enough to run on every tab entry. */
    fun refreshStudioClips() {
        scope.launch {
            _studio.update { it.copy(loading = true) }
            val clips = withContext(Dispatchers.IO) { dev.geode.export.StudioClips.list(application) }
            _studio.update { it.copy(clips = clips, loading = false) }
        }
    }

    /** Describes a clip the user picked through the system file picker. */
    fun describeStudioClip(
        uri: Uri,
        onReady: (dev.geode.export.StudioClip) -> Unit,
    ) {
        scope.launch {
            val clip = withContext(Dispatchers.IO) { dev.geode.export.StudioClips.describe(application, uri) }
            onReady(clip)
        }
    }

    /**
     * Renders an edit to a new file in Movies/Geode.
     *
     * Always a new file: an edit that overwrote its source would make the one
     * irreversible action in the app the DEFAULT one, and the original render
     * can be minutes of GPU time.
     */
    fun startStudioExport(
        clip: dev.geode.export.StudioClip,
        edit: dev.geode.export.ClipEdit,
    ) {
        if (_studio.value.running) return
        _studio.update { it.copy(running = true, progress = 0f, resultUri = null, error = null) }
        studioJob =
            scope.launch {
                val name = "geode_studio_${System.currentTimeMillis()}.mp4"
                val result =
                    studioExporter.export(
                        source = Uri.parse(clip.uri),
                        sourceDurationMs = clip.durationMs,
                        edit = edit,
                        displayName = name,
                    ) { p -> _studio.update { it.copy(progress = p.coerceIn(0f, 1f)) } }
                when (result) {
                    is dev.geode.export.StudioExporter.Result.Saved ->
                        _studio.update { it.copy(running = false, progress = 1f, resultUri = result.uri) }
                    is dev.geode.export.StudioExporter.Result.Failed ->
                        _studio.update { it.copy(running = false, error = result.message) }
                    dev.geode.export.StudioExporter.Result.Cancelled ->
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
}
