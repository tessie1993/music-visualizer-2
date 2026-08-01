package dev.musicviz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.musicviz.render.VisualizerView

/** An export request waiting for the user to choose an output file. */
private data class PendingExport(
    val aspect: dev.musicviz.export.ExportAspect,
    val fps: Int,
    val sceneId: String,
    val loopSafe: Boolean,
)

/** Hosts the export dialog from the Settings destination. */
@Composable
fun ExportHost(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val viz by viewModel.vizState.collectAsState()
    val export by viewModel.exportState.collectAsState()

    /** Aspect, fps, scene id and the loop-safe choice, held across the picker. */
    var pendingExport by remember {
        mutableStateOf<PendingExport?>(null)
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
                )
            }
        }
    val takes by viewModel.takeState.collectAsState()
    SettingsDialog(
        export = export,
        hasMedia = state.hasMedia,
        takes = takes.takes.map { it.name },
        selectedTake = takes.exportTake,
        onSelectTake = viewModel::setExportTake,
        bpm = viz.bpm,
        onStart = { aspect, fps, loopSafe ->
            viewModel.startExport(
                aspect,
                fps,
                visualizerView.visualizerRenderer.exportSceneFactory(viz.sceneId),
                loopSafe = loopSafe,
            )
        },
        onStartToDestination = { aspect, fps, loopSafe ->
            pendingExport = PendingExport(aspect, fps, viz.sceneId, loopSafe)
            destinationPicker.launch("musicviz_${System.currentTimeMillis()}.mp4")
        },
        onCancel = viewModel::cancelExport,
        onDismiss = {
            viewModel.resetExportState()
            onDismiss()
        },
    )
}
