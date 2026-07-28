package dev.musicviz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.musicviz.render.VisualizerRenderer
import dev.musicviz.render.VisualizerView
import dev.musicviz.render.scene.SceneIds

/**
 * The Visuals nav destination: everything visual in one hub. Style/Customize
 * changes apply straight to the shared renderer, so switching to Now Playing
 * shows them live ("same content, two doors").
 */
@Composable
fun VisualsHub(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onOpenNowPlaying: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Presets", "Styles", "Customize", "Textures")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Visuals", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onOpenNowPlaying) { Text("View live") }
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> PresetsTreeTab(viewModel, visualizerView)
            1 -> StylesTab(viewModel, visualizerView, onOpenTextures = { tab = 3 })
            2 -> CustomizeHubTab(viewModel, visualizerView)
            3 -> TexturesHubTab(viewModel, visualizerView)
        }
    }
}

// ---------------------------------------------------------------- Presets

@Composable
private fun PresetsTreeTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val viz by viewModel.vizState.collectAsState()
    var folderRefresh by remember { mutableStateOf(0) }
    val folders = remember(folderRefresh, viz.presets) { viewModel.presetFolders() }
    var newFolder by remember { mutableStateOf("") }
    var saveName by remember { mutableStateOf("") }
    var saveFolder by rememberSaveable { mutableStateOf("") }
    val userPresets = viz.presets.filterNot { BuiltInPresets.isBuiltIn(it.name) }
    val byFolder = userPresets.groupBy { viewModel.presetFolderOf(it.name) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newFolder,
                    onValueChange = { newFolder = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("New folder name") },
                    singleLine = true,
                )
                Button(onClick = {
                    if (newFolder.isNotBlank()) {
                        viewModel.addPresetFolder(newFolder.trim())
                        newFolder = ""
                        folderRefresh++
                    }
                }) { Text("Add") }
            }
        }
        (listOf("") + folders).forEach { folder ->
            val inFolder = byFolder[folder].orEmpty()
            if (folder.isNotEmpty() || inFolder.isNotEmpty()) {
                item(key = "hdr_$folder") {
                    Text(
                        if (folder.isEmpty()) "Presets" else "📁 $folder",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            items(inFolder, key = { "p_${it.name}" }) { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { applyPresetLive(viewModel, visualizerView, p) }) {
                        Icon(Icons.Filled.PlayArrow, "Apply", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = {
                            viewModel.addToVizPlaylist(
                                VizPlaylistEntry(sceneId = p.sceneId, presetName = p.name, label = p.name),
                            )
                        },
                    ) {
                        Icon(Icons.Filled.Favorite, "Add to visual playlist")
                    }
                    IconButton(onClick = { viewModel.deletePreset(p.name) }) {
                        Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            Text(
                "Built-in",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(viz.presets.filter { BuiltInPresets.isBuiltIn(it.name) && it.sceneId == viz.sceneId }, key = { "b_${it.name}" }) { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { applyPresetLive(viewModel, visualizerView, p) }) {
                    Icon(Icons.Filled.PlayArrow, "Apply", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 10.dp),
            ) {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Save current as…") },
                    singleLine = true,
                )
                Button(onClick = {
                    if (saveName.isNotBlank()) {
                        viewModel.savePreset(
                            saveName.trim(),
                            visualizerView.visualizerRenderer.customShaderFor(viewModel.vizState.value.sceneId),
                            saveFolder,
                        )
                        saveName = ""
                    }
                }) { Text("Save") }
            }
            if (folders.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    (listOf("") + folders).forEach { f ->
                        OutlinedButton(onClick = { saveFolder = f }) {
                            Text((if (saveFolder == f) "● " else "") + f.ifEmpty { "root" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

/** Applies a preset AND pushes its shader/milk side straight to the renderer. */
private fun applyPresetLive(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    p: Preset,
) {
    val shader = viewModel.applyPreset(p)
    shader?.let { visualizerView.visualizerRenderer.submitShader(p.sceneId, it) }
}

// ---------------------------------------------------------------- Styles

@Composable
private fun StylesTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onOpenTextures: () -> Unit,
) {
    var sub by rememberSaveable { mutableStateOf(0) }
    val viz by viewModel.vizState.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = sub, edgePadding = 8.dp) {
            listOf("Particles", "Shaders", "Fluid", "MilkDrop").forEachIndexed { i, t ->
                Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t) })
            }
        }
        when (sub) {
            0 ->
                SceneList(VisualizerRenderer.PARTICLE_SCENES, viz.sceneId) {
                    viewModel.selectScene(it)
                    visualizerView.visualizerRenderer.requestedSceneId = it
                }
            1 ->
                SceneList(VisualizerRenderer.SHADER_SCENES.keys.toList(), viz.sceneId) {
                    viewModel.selectScene(it)
                    visualizerView.visualizerRenderer.requestedSceneId = it
                }
            2 ->
                SceneList(
                    listOf(
                        dev.musicviz.render.scene.SceneIds.FLUID,
                        dev.musicviz.render.scene.SceneIds.CURLFLOW,
                    ),
                    viz.sceneId,
                ) {
                    viewModel.selectScene(it)
                    visualizerView.visualizerRenderer.requestedSceneId = it
                }
            3 -> MilkDropTab(viewModel, visualizerView, onOpenTextures)
        }
    }
}

@Composable
private fun SceneList(
    ids: List<String>,
    current: String,
    onPick: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(ids) { id ->
            Text(
                (if (id == current) "● " else "") + id,
                Modifier.fillMaxWidth().clickable { onPick(id) }.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** Dedicated MilkDrop tab: Load .milk, user list, Next, Textures shortcut. */
@Composable
private fun MilkDropTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onOpenTextures: () -> Unit,
) {
    var refresh by remember { mutableStateOf(0) }
    val milkFiles = remember(refresh) { viewModel.userMilkPresets() }
    val milkPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.importMilkPresetAsync(uri) { path ->
                    if (path != null) {
                        selectMilk(viewModel, visualizerView, path)
                        refresh++
                    }
                }
            }
        }
    if (!visualizerView.visualizerRenderer.milkdropAvailable) {
        Text("MilkDrop engine unavailable on this device.", Modifier.padding(16.dp))
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { milkPicker.launch(arrayOf("*/*")) }) { Text("Load .milk file") }
            OutlinedButton(onClick = onOpenTextures) { Text("Textures…") }
        }
        Text("Your .milk presets", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (milkFiles.isEmpty()) {
            Text("None yet — load a .milk file or save one from the milkdrop scene.", style = MaterialTheme.typography.bodySmall)
        }
        milkFiles.forEach { f ->
            Text(
                f.nameWithoutExtension,
                Modifier.fillMaxWidth().clickable { selectMilk(viewModel, visualizerView, f.absolutePath) }.padding(vertical = 8.dp),
            )
        }
    }
}

private fun selectMilk(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    path: String,
) {
    viewModel.selectScene(SceneIds.MILKDROP)
    visualizerView.visualizerRenderer.requestedSceneId = SceneIds.MILKDROP
    visualizerView.visualizerRenderer.loadMilkPreset(path)
    viewModel.noteMilkPreset(path)
}

// ---------------------------------------------------------------- Customize

@Composable
private fun CustomizeHubTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val viz by viewModel.vizState.collectAsState()
    var sub by rememberSaveable { mutableStateOf(0) }
    val isShader = viz.sceneId in dev.musicviz.render.VisualizerRenderer.SHADER_SCENES
    val tabs = listOf("Motion", "Shape", "Behavior", "Color", "FX", "Fluid") + if (isShader) listOf("GLSL") else emptyList()
    LaunchedEffect(isShader) { if (!isShader && sub >= 6) sub = 0 }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = sub, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t) }) }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Button(onClick = viewModel::randomizeParams) { Text("⚄ Randomize unlocked") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val locked by viewModel.lockedParams.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(
                LocalParamLocks provides (locked to viewModel::toggleParamLock),
            ) {
                val p = viz.params
                val onChange: (dev.musicviz.render.scene.SceneParams) -> Unit = { viewModel.setSceneParams(it) }
                val lfos by viewModel.lfos.collectAsState()
                when (sub) {
                    0 -> MotionTab(p, onChange)
                    1 -> ShapeTab(p, onChange)
                    2 ->
                        BehaviorTab(
                            p,
                            onChange,
                            transitionStyle = viz.transitionStyle,
                            transitionDurationSec = viz.transitionDurationSec,
                            onTransitionStyle = viewModel::setTransitionStyle,
                            onTransitionDuration = viewModel::setTransitionDuration,
                            attack = viz.attack,
                            decay = viz.decay,
                            onReactivityChange = viewModel::setReactivity,
                            intelligenceMode = viz.intelligenceMode,
                            onIntelligenceModeChange = viewModel::setIntelligenceMode,
                        )
                    3 -> ColorTab(p, onChange)
                    4 -> {
                        val adsrs by viewModel.adsrs.collectAsState()
                        FxTab(
                            p,
                            onChange,
                            lfos = lfos,
                            onLfoChange = viewModel::setLfo,
                            adsr = adsrs,
                            onAdsrChange = viewModel::setAdsr,
                        )
                    }
                    5 ->
                        FluidTab(
                            p,
                            onChange,
                            isFluidScene = viz.sceneId == dev.musicviz.render.scene.SceneIds.FLUID,
                            isJourneyScene =
                                viz.sceneId == dev.musicviz.render.scene.SceneIds.FLUID ||
                                    viz.sceneId == dev.musicviz.render.scene.SceneIds.CURLFLOW,
                            injectionError = if (viz.sceneId == dev.musicviz.render.scene.SceneIds.FLUID) viz.shaderError else null,
                            onApplyInjectionShaders = { force, dye ->
                                visualizerView.visualizerRenderer.submitFluidInjectionShaders(force, dye)
                            },
                        )
                    6 -> GlslHubTab(viewModel, visualizerView)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Textures

@Composable
private fun TexturesHubTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val textures by viewModel.textures.collectAsState()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                viewModel.importTextures(uris) { visualizerView.visualizerRenderer.reloadCurrentMilkPreset() }
            }
        }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Import images") }
        textures.forEach { tex ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tex.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                OutlinedButton(onClick = {
                    viewModel.useTexture(tex.name) { path -> selectMilk(viewModel, visualizerView, path) }
                }) { Text("Use") }
            }
        }
        if (textures.isEmpty()) Text("No textures imported yet.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------- GLSL

/**
 * Shader-scene GLSL editor, restored after the navigation refactor: seeds
 * from the scene's current custom shader, applies through the ViewModel so
 * the shell-level engine bindings reach the renderer from any screen.
 */
@Composable
private fun GlslHubTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val viz by viewModel.vizState.collectAsState()
    var source by rememberSaveable(viz.sceneId) {
        mutableStateOf(visualizerView.visualizerRenderer.customShaderFor(viz.sceneId) ?: "")
    }
    Column {
        Text(
            "Fragment source for this shader scene (view(), pal(), grade() and " +
                "the audio uniforms are available). When FlowField is enabled " +
                "the fluid velocity field is bound as `uniform sampler2D uFlow` " +
                "with `uniform float uFlowStrength` - declare and sample it for " +
                "fluid-driven distortion.",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedTextField(
            value = source,
            onValueChange = { source = it },
            modifier = Modifier.fillMaxWidth().height(360.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        viz.shaderError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.applyCustomShader(source) }) { Text("Apply shader") }
            TextButton(onClick = {
                source = visualizerView.visualizerRenderer.customShaderFor(viz.sceneId) ?: ""
            }) { Text("Revert") }
        }
    }
}
