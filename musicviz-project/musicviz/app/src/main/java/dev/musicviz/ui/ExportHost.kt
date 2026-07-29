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
    var pendingExport by remember { mutableStateOf<Triple<dev.musicviz.export.ExportAspect, Int, String>?>(null) }
    val destinationPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { dest ->
            val req = pendingExport
            pendingExport = null
            if (dest != null && req != null) {
                viewModel.startExport(
                    req.first,
                    req.second,
                    visualizerView.visualizerRenderer.exportSceneFactory(req.third),
                    destination = dest,
                )
            }
        }
    SettingsDialog(
        export = export,
        hasMedia = state.hasMedia,
        onStart = { aspect, fps ->
            viewModel.startExport(aspect, fps, visualizerView.visualizerRenderer.exportSceneFactory(viz.sceneId))
        },
        onStartToDestination = { aspect, fps ->
            pendingExport = Triple(aspect, fps, viz.sceneId)
            destinationPicker.launch("musicviz_${System.currentTimeMillis()}.mp4")
        },
        onCancel = viewModel::cancelExport,
        onDismiss = onDismiss,
    )
}
