package dev.musicviz.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.musicviz.analysis.IntelligenceMode
import dev.musicviz.export.ExportAspect
import dev.musicviz.render.VisualizerView
import dev.musicviz.render.scene.SceneIds

/**
 * Fullscreen GL visualizer. Tapping the canvas toggles the control overlay
 * (ambient mode). ui -> audio/analysis/render only.
 */
@Composable
fun VisualizerScreen(
    viewModel: PlayerViewModel,
    onPersistUri: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val viz by viewModel.vizState.collectAsState()
    val export by viewModel.exportState.collectAsState()
    val features by viewModel.features.collectAsState()
    val visualizerView = remember { VisualizerView(context) }
    var controlsVisible by remember { mutableStateOf(true) }
    var showEditor by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showPresetSave by remember { mutableStateOf(false) }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach(onPersistUri)
            viewModel.open(uris)
        }
    val milkPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.importMilkPreset(uri)?.let { path ->
                    visualizerView.visualizerRenderer.loadMilkPreset(path)
                }
            }
        }

    LaunchedEffect(Unit) {
        visualizerView.visualizerRenderer.onShaderError = viewModel::reportShaderError
        visualizerView.visualizerRenderer.pcmProvider = { viewModel.latestPcm() }
        viewModel.features.collect { visualizerView.visualizerRenderer.features = it }
    }
    LaunchedEffect(viz.sceneId) {
        visualizerView.visualizerRenderer.requestedSceneId = viz.sceneId
    }
    LaunchedEffect(viz.params) {
        visualizerView.visualizerRenderer.sceneParams = viz.params
    }
    LaunchedEffect(viz.milkPresetPath) {
        viz.milkPresetPath?.let { visualizerView.visualizerRenderer.loadMilkPreset(it) }
    }

    LifecycleResumeEffect(Unit) {
        visualizerView.onResume()
        onPauseOrDispose { visualizerView.onPause() }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { visualizerView.apply { keepScreenOn = true } },
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clickable { controlsVisible = !controlsVisible },
            )
            if (controlsVisible) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .safeDrawingPadding()
                            .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AnalysisOverlay(features, viz.bpm, viz.sections.size)
                    SceneRow(
                        viz = viz,
                        viewModel = viewModel,
                        sceneIds = visualizerView.visualizerRenderer.availableSceneIds(),
                        onCustomize = { showEditor = true },
                        onLoadMilk = { milkPicker.launch(arrayOf("*/*")) },
                    )
                    IntelligenceRow(viz, viewModel)
                    ReactivityRow(viz, viewModel)
                    if (viz.analyzing) LinearProgressIndicator(progress = { viz.analysisProgress }, modifier = Modifier.fillMaxWidth())
                    TransportRow(
                        state = state,
                        viewModel = viewModel,
                        onPick = { picker.launch(arrayOf("audio/*")) },
                        onExport = { showExport = true },
                        onSavePreset = { showPresetSave = true },
                    )
                }
            }

            if (showEditor) {
                CustomizeDialog(
                    params = viz.params,
                    onParamsChange = viewModel::setSceneParams,
                    shaderSource = visualizerView.visualizerRenderer.shaderSourceFor(viz.sceneId),
                    shaderError = viz.shaderError,
                    onApplyShader = { src -> visualizerView.visualizerRenderer.submitShader(viz.sceneId, src) },
                    onDismiss = { showEditor = false },
                )
            }
            if (showExport) {
                ExportDialog(
                    export = export,
                    onStart = { aspect ->
                        viewModel.startExport(aspect, visualizerView.visualizerRenderer.exportSceneFactory(viz.sceneId))
                    },
                    onCancel = viewModel::cancelExport,
                    onDismiss = { showExport = false },
                )
            }
            if (showPresetSave) {
                PresetSaveDialog(
                    onSave = { name ->
                        viewModel.savePreset(name, null)
                        showPresetSave = false
                    },
                    onDismiss = { showPresetSave = false },
                )
            }
        }
    }
}

@Composable
private fun SceneRow(
    viz: VizUiState,
    viewModel: PlayerViewModel,
    sceneIds: List<String>,
    onCustomize: () -> Unit,
    onLoadMilk: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sceneIds.forEach { id ->
            val suggested = viz.suggestedSceneId == id
            FilterChip(
                selected = viz.sceneId == id,
                onClick = { viewModel.selectScene(id) },
                label = { Text(if (suggested) "$id *" else id) },
            )
        }
        OutlinedButton(onClick = onCustomize) { Text("Customize") }
        if (viz.sceneId == SceneIds.MILKDROP) {
            OutlinedButton(onClick = onLoadMilk) { Text("Load .milk") }
        }
        viz.presets.forEach { preset ->
            FilterChip(
                selected = false,
                onClick = { viewModel.applyPreset(preset) },
                label = { Text("P: ${preset.name}") },
            )
        }
    }
}

@Composable
private fun IntelligenceRow(
    viz: VizUiState,
    viewModel: PlayerViewModel,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IntelligenceMode.entries.forEach { mode ->
            FilterChip(
                selected = viz.intelligenceMode == mode,
                onClick = { viewModel.setIntelligenceMode(mode) },
                label = { Text(mode.name.lowercase()) },
            )
        }
        OutlinedButton(onClick = viewModel::analyzeCurrentTrack, enabled = !viz.analyzing) {
            Text("Analyze")
        }
    }
}

@Composable
private fun ReactivityRow(
    viz: VizUiState,
    viewModel: PlayerViewModel,
) {
    Column {
        Text("Attack ${"%.2f".format(viz.attack)} / Decay ${"%.2f".format(viz.decay)}", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Slider(
                value = viz.attack,
                onValueChange = { viewModel.setReactivity(it.coerceIn(0.05f, 1f), viz.decay) },
                modifier = Modifier.weight(1f),
            )
            Slider(
                value = viz.decay,
                onValueChange = { viewModel.setReactivity(viz.attack, it.coerceIn(0.01f, 1f)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TransportRow(
    state: PlayerUiState,
    viewModel: PlayerViewModel,
    onPick: () -> Unit,
    onExport: () -> Unit,
    onSavePreset: () -> Unit,
) {
    Column {
        state.title?.let { Text(text = it) }
        if (state.durationMs > 0) {
            Slider(
                value = state.positionMs.toFloat() / state.durationMs,
                onValueChange = viewModel::seekTo,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onPick) { Text("Pick") }
            Button(onClick = viewModel::previous, enabled = state.queueSize > 1) { Text("Prev") }
            Button(onClick = viewModel::togglePlayPause, enabled = state.hasMedia) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }
            Button(onClick = viewModel::next, enabled = state.queueSize > 1) { Text("Next") }
            OutlinedButton(onClick = onExport, enabled = state.hasMedia) { Text("Export") }
            OutlinedButton(onClick = onSavePreset) { Text("Save preset") }
            if (state.queueSize > 1) {
                Text(
                    "${state.queueIndex + 1}/${state.queueSize}",
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}

@Composable
private fun ExportDialog(
    export: ExportUiState,
    onStart: (ExportAspect) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export video (1080p)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (export.running) {
                    Text("Rendering offline at 60 fps...")
                    LinearProgressIndicator(progress = { export.progress }, modifier = Modifier.fillMaxWidth())
                } else if (export.resultUri != null) {
                    Text("Saved to your Videos library.")
                } else if (export.error != null) {
                    Text("Failed: ${export.error}", color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Choose aspect ratio. The current scene renders deterministically with the analyzed track.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onStart(ExportAspect.LANDSCAPE) }) { Text("16:9") }
                        Button(onClick = { onStart(ExportAspect.PORTRAIT) }) { Text("9:16") }
                    }
                }
            }
        },
        confirmButton = {
            if (export.running) {
                TextButton(onClick = onCancel) { Text("Cancel export") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun PresetSaveDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save preset") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
