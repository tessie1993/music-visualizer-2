package dev.musicviz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.musicviz.export.ExportAspect
import dev.musicviz.render.VisualizerView

/** An export request waiting for the user to choose an output file. */
private data class PendingExport(
    val aspect: ExportAspect,
    val fps: Int,
    val sceneId: String,
    val loopSafe: Boolean,
)

/**
 * Flattens [PendingExport] into saved instance state. The system document
 * picker is a separate activity, so a rotation (or process death) while it is
 * up recreates this composition; a plain `remember` came back null and the
 * picked destination was silently dropped. `null` (no pick in flight) saves as
 * an empty list; restore's `null` falls back to the initializer, which is the
 * same `null`.
 */
private val PendingExportSaver =
    listSaver<PendingExport?, Any>(
        save = { req ->
            if (req == null) {
                emptyList()
            } else {
                listOf(req.aspect.width, req.aspect.height, req.aspect.bitRate, req.fps, req.sceneId, req.loopSafe)
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
                )
            }
        },
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

    /** Aspect, fps, scene id and the loop-safe choice, held across the picker
     *  - saveable, because the picker activity outlives a rotation. */
    var pendingExport by rememberSaveable(stateSaver = PendingExportSaver) {
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
