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
    val context = androidx.compose.ui.platform.LocalContext.current
    // rememberSaveable: the CreateDocument picker is a foreign activity, so a
    // rotation (or process death) while it is open used to reset a plain
    // remember{} to null - the chosen file was then silently never written.
    var pendingExport by androidx.compose.runtime.saveable.rememberSaveable(
        stateSaver =
            androidx.compose.runtime.saveable.Saver(
                save = { v -> v?.let { arrayListOf<Any>(it.first.width, it.first.height, it.first.bitRate, it.second, it.third) } },
                restore = { saved ->
                    Triple(
                        dev.musicviz.export.ExportAspect(saved[0] as Int, saved[1] as Int, saved[2] as Int),
                        saved[3] as Int,
                        saved[4] as String,
                    )
                },
            ),
    ) { mutableStateOf<Triple<dev.musicviz.export.ExportAspect, Int, String>?>(null) }
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
            } else if (dest != null) {
                // Request lost (shouldn't happen with the saveable state, but
                // stay safe): don't leave the picker's zero-byte .mp4 behind.
                runCatching {
                    android.provider.DocumentsContract.deleteDocument(context.contentResolver, dest)
                }
            }
        }
    val presetFolderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                // Not every provider grants persistable permissions; an
                // uncaught SecurityException here crashed the app.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
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
