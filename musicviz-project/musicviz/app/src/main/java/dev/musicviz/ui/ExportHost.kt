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

/** Hosts the existing export/settings dialog from the Settings destination. */
@Composable
fun ExportHost(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onDismiss: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val viz by viewModel.vizState.collectAsState()
    val export by viewModel.exportState.collectAsState()
    val appTheme by viewModel.theme.collectAsState()
    val gui by viewModel.guiPrefs.collectAsState()
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val presetFolderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                viewModel.setGuiPrefs(viewModel.guiPrefs.value.copy(presetMirrorUri = uri.toString()))
            }
        }
    SettingsDialog(
        export = export,
        hasMedia = state.hasMedia,
        currentTheme = appTheme,
        onThemeChange = viewModel::setTheme,
        guiPrefs = gui,
        onGuiPrefsChange = viewModel::setGuiPrefs,
        onPickPresetFolder = { presetFolderPicker.launch(null) },
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
