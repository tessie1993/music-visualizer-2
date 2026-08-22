package dev.geode.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.export.ExportAspect
import dev.geode.export.ExportRange
import dev.geode.render.VisualizerView

private data class PendingExport(
    val aspect: ExportAspect,
    val fps: Int,
    val sceneId: String,
    val loopSafe: Boolean,
    val rangeStartMs: Long,
    val rangeDurationMs: Long,
) {
    val range: ExportRange? get() = if (rangeDurationMs > 0) ExportRange(rangeStartMs, rangeDurationMs) else null
}

private val PendingExportSaver =
    listSaver<PendingExport?, Any>(
        save = { req ->
            if (req == null) {
                emptyList()
            } else {
                listOf(
                    req.aspect.width,
                    req.aspect.height,
                    req.aspect.bitRate,
                    req.fps,
                    req.sceneId,
                    req.loopSafe,
                    req.rangeStartMs,
                    req.rangeDurationMs,
                )
            }
        },
        restore = { saved ->
            if (saved.isEmpty()) {
                null
            } else {
                PendingExport(
                    aspect = ExportAspect(saved[0] as Int, saved[1] as Int, saved[2] as Int),
                    fps = saved[3] as Int,
                    sceneId = saved[4] as String,
                    loopSafe = saved[5] as Boolean,
                    rangeStartMs = saved[6] as Long,
                    rangeDurationMs = saved[7] as Long,
                )
            }
        },
    )

@Composable
fun ExportHost(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val viz by viewModel.vizState.collectAsStateWithLifecycle()
    val export by viewModel.exportState.collectAsStateWithLifecycle()

    var pendingExport by rememberSaveable(stateSaver = PendingExportSaver) {
        mutableStateOf<PendingExport?>(null)
    }

    val sceneFactoryFor: (String, String) -> dev.geode.export.VideoExporter.SceneFactory =
        { requested, fallback ->
            val renderer = visualizerView.visualizerRenderer
            renderer.exportSceneFactory(
                if (requested in renderer.availableSceneIds()) requested else fallback,
            )
        }
    val destinationPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { dest ->
            val req = pendingExport
            pendingExport = null
            if (dest != null && req != null) {
                viewModel.startExport(
                    req.aspect,
                    req.fps,
                    visualizerView.visualizerRenderer.exportSceneFactory(req.sceneId),
                    destination = dest,
                    loopSafe = req.loopSafe,
                    range = req.range,
                    sceneFactoryFor = { id -> sceneFactoryFor(id, req.sceneId) },
                )
            }
        }
    val takes by viewModel.takeState.collectAsStateWithLifecycle()
    SettingsDialog(
        export = export,
        hasMedia = state.hasMedia,
        takes = takes.takes.map { it.name },
        selectedTake = takes.exportTake,
        onSelectTake = viewModel::setExportTake,
        bpm = viz.bpm,
        trackDurationMs = state.durationMs,
        onStart = { aspect, fps, loopSafe, range ->
            viewModel.startExport(
                aspect,
                fps,
                visualizerView.visualizerRenderer.exportSceneFactory(viz.sceneId),
                loopSafe = loopSafe,
                range = range,
                sceneFactoryFor = { id -> sceneFactoryFor(id, viz.sceneId) },
            )
        },
        onStartToDestination = { aspect, fps, loopSafe, range ->
            pendingExport =
                PendingExport(
                    aspect,
                    fps,
                    viz.sceneId,
                    loopSafe,
                    range?.startMs ?: 0L,
                    range?.durationMs ?: 0L,
                )
            destinationPicker.launch("geode_${System.currentTimeMillis()}.mp4")
        },
        onCancel = viewModel::cancelExport,
        onDismiss = {
            viewModel.resetExportState()
            onDismiss()
        },
    )
}
