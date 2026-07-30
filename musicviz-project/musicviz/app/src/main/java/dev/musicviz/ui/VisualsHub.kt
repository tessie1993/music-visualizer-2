package dev.musicviz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
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
 *
 * With [liveBackdrop] (Settings › "Clear-overlay Visuals menu", or the
 * layers toggle in the header) the hub hosts the live visualizer canvas
 * fullscreen behind text-only chrome — no panels, just shadowed text — so
 * every adjustment is visible on the visuals while it's being made.
 */
@Composable
fun VisualsHub(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onOpenNowPlaying: () -> Unit,
    liveBackdrop: Boolean = false,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Presets", "Styles", "Customize", "Textures")
    val gui by viewModel.guiPrefs.collectAsState()
    Box(Modifier.fillMaxSize()) {
        if (liveBackdrop) {
            VisualizerCanvasHost(visualizerView, Modifier.fillMaxSize())
            // Gentle dim so text stays legible over bright visuals; the
            // menu itself stays clear (no panel fills in this mode).
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        }
        val bodyStyle =
            if (liveBackdrop) {
                LocalTextStyle.current.copy(
                    shadow = Shadow(color = Color.Black.copy(alpha = 0.9f), blurRadius = 10f),
                )
            } else {
                LocalTextStyle.current
            }
        ProvideTextStyle(bodyStyle) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        CrystalOverline(if (liveBackdrop) "Live overlay" else "MusicViz")
                        GlowTitle("Visuals")
                    }
                    IconButton(onClick = {
                        viewModel.setGuiPrefs(gui.copy(clearVisualsMenu = !gui.clearVisualsMenu))
                    }) {
                        Icon(
                            if (liveBackdrop) Icons.Filled.LayersClear else Icons.Filled.Layers,
                            if (liveBackdrop) "Solid menu" else "Clear overlay on live visuals",
                            tint = if (liveBackdrop) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    CrystalButton("View Live", kind = CrystalButtonKind.GHOST, onClick = onOpenNowPlaying)
                }
                CrystalTabRow(tabs, tab, onSelect = { tab = it })
                when (tab) {
                    0 -> PresetsTreeTab(viewModel, visualizerView)
                    1 -> StylesTab(viewModel, visualizerView, onOpenTextures = { tab = 3 })
                    2 -> CustomizeHubTab(viewModel, visualizerView)
                    3 -> TexturesHubTab(viewModel, visualizerView)
                }
            }
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
    val userPresets = viz.presets.filterNot { BuiltInPresets.isBuiltIn(it.name) }.distinctBy { it.name }
    val byFolder = userPresets.groupBy { viewModel.presetFolderOf(it.name) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrystalTextField(
                    value = newFolder,
                    onValueChange = { newFolder = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "New folder name",
                )
                CrystalButton("Add", kind = CrystalButtonKind.SECONDARY, onClick = {
                    if (newFolder.isNotBlank()) {
                        viewModel.addPresetFolder(newFolder.trim())
                        newFolder = ""
                        folderRefresh++
                    }
                })
            }
        }
        (listOf("") + folders).forEach { folder ->
            val inFolder = byFolder[folder].orEmpty()
            if (folder.isNotEmpty() || inFolder.isNotEmpty()) {
                item(key = "hdr_$folder") {
                    CrystalOverline(
                        if (folder.isEmpty()) "Presets" else "📁 $folder",
                        Modifier.padding(top = 10.dp),
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
            CrystalOverline("Built-in", Modifier.padding(top = 10.dp))
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
                CrystalTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "Save current as…",
                )
                CrystalButton("Save", onClick = {
                    if (saveName.isNotBlank()) {
                        viewModel.savePreset(
                            saveName.trim(),
                            visualizerView.visualizerRenderer.customShaderFor(viewModel.vizState.value.sceneId),
                            saveFolder,
                        )
                        saveName = ""
                    }
                })
            }
            if (folders.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    (listOf("") + folders).forEach { f ->
                        CrystalChip(f.ifEmpty { "root" }, selected = saveFolder == f, onClick = { saveFolder = f })
                    }
                }
            }
        }
    }
}

/** Applies a preset; its shader side reaches the renderer via vizApply. */
private fun applyPresetLive(
    viewModel: PlayerViewModel,
    @Suppress("UNUSED_PARAMETER") visualizerView: VisualizerView,
    p: Preset,
) {
    viewModel.applyPreset(p)
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
    // Single pick path: selectScene updates vizState and the engine bindings
    // (EnginePlumbing) push requestedSceneId to the renderer. The old code
    // ALSO wrote requestedSceneId directly here - two writers for the same
    // renderer field, and the direct write bypassed the transition-aware
    // state flow. One source of truth is what keeps switching stable.
    val pickScene: (String) -> Unit = { viewModel.selectScene(it) }
    Column(Modifier.fillMaxSize()) {
        CrystalTabRow(listOf("Particles", "Shaders", "Fluid", "MilkDrop"), sub, onSelect = { sub = it })
        when (sub) {
            0 -> SceneList(VisualizerRenderer.PARTICLE_SCENES, viz.sceneId, pickScene)
            1 -> SceneList(VisualizerRenderer.SHADER_SCENES.keys.toList(), viz.sceneId, pickScene)
            2 -> SceneList(listOf(SceneIds.FLUID, SceneIds.CURLFLOW, SceneIds.WATER), viz.sceneId, pickScene)
            3 -> MilkDropTab(viewModel, visualizerView, onOpenTextures)
        }
    }
}

/** Display name + one-line description per scene, for the style cards. */
private val SCENE_META: Map<String, Pair<String, String>> =
    mapOf(
        SceneIds.FLUID to ("Fluid" to "Dye + force simulation"),
        SceneIds.CURLFLOW to ("Curlflow" to "Curl-noise particle flow"),
        SceneIds.WATER to ("Water" to "Surface waves + refraction"),
        SceneIds.NEBULA to ("Nebula" to "Aurora particle bloom"),
        SceneIds.BURSTS to ("Bursts" to "Beat-synced particle bursts"),
        SceneIds.SWARM to ("Swarm" to "Flocking particle swarm"),
        SceneIds.FOUNTAIN to ("Fountain" to "Gravity particle fountain"),
        SceneIds.ORBITS to ("Orbits" to "Orbital particle trails"),
        SceneIds.JULIA to ("Julia" to "Julia-set fractal dive"),
        SceneIds.TUNNEL to ("Tunnel" to "Warp tunnel flight"),
        SceneIds.BARS to ("Bars" to "Classic spectrum bars"),
        SceneIds.RING to ("Ring" to "Radial spectrum ring"),
        SceneIds.SCOPE to ("Scope" to "Oscilloscope waveform"),
        SceneIds.PLASMA to ("Plasma" to "Flowing plasma field"),
        SceneIds.KALEIDO to ("Kaleido" to "Kaleidoscope mirror folds"),
        SceneIds.WARP to ("Warp" to "Hyperspace star warp"),
        SceneIds.GRID to ("Grid" to "Pulsing neon grid"),
        SceneIds.VORONOI to ("Voronoi" to "Cellular crystal facets"),
        SceneIds.MANDEL to ("Mandel" to "Mandelbrot fractal zoom"),
        SceneIds.LISS to ("Liss" to "Lissajous curve weave"),
        SceneIds.METABALLS to ("Metaballs" to "Liquid metaball blobs"),
        SceneIds.RIPPLES to ("Ripples" to "Beat-driven ripple rings"),
        SceneIds.STARFIELD to ("Starfield" to "Deep-space star drift"),
        SceneIds.WAVES to ("Waves" to "Layered waveform ribbons"),
        SceneIds.HEXGRID to ("Hexgrid" to "Hex lattice pulse"),
        SceneIds.SPIRAL to ("Spiral" to "Spinning spiral bloom"),
        SceneIds.AURORA to ("Aurora" to "Northern-lights curtains"),
        SceneIds.SOLAR to ("Solar" to "Solar flare corona"),
        SceneIds.MILKDROP to ("MilkDrop" to "MilkDrop preset engine"),
    )

@Composable
private fun SceneList(
    ids: List<String>,
    current: String,
    onPick: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(ids) { id ->
            val (name, desc) = SCENE_META[id] ?: (id.replaceFirstChar { it.uppercase() } to "Visual style")
            val sel = id == current
            CrystalListRow(
                title = name,
                subtitle = desc,
                onClick = { onPick(id) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                thumbSeed = id,
                selected = sel,
            ) {
                CrystalRadio(sel, Modifier.padding(end = 8.dp))
            }
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
            CrystalButton("Load .milk file", onClick = { milkPicker.launch(arrayOf("*/*")) })
            CrystalButton("Textures…", kind = CrystalButtonKind.GHOST, onClick = onOpenTextures)
        }
        CrystalOverline("Your .milk presets", Modifier.padding(top = 4.dp))
        if (milkFiles.isEmpty()) {
            Text("None yet — load a .milk file or save one from the milkdrop scene.", style = MaterialTheme.typography.bodySmall)
        }
        milkFiles.forEach { f ->
            CrystalListRow(
                title = f.nameWithoutExtension,
                subtitle = null,
                onClick = { selectMilk(viewModel, visualizerView, f.absolutePath) },
                thumbSeed = f.name,
            )
        }
    }
}

private fun selectMilk(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    path: String,
) {
    // Scene switch flows through vizState -> EnginePlumbing like every other
    // pick; only the .milk load itself talks to the renderer directly.
    viewModel.selectScene(SceneIds.MILKDROP)
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
        CrystalTabRow(tabs, sub, onSelect = { sub = it })
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            CrystalButton(
                "Randomize Unlocked",
                icon = Icons.Filled.Casino,
                kind = CrystalButtonKind.SECONDARY,
                onClick = viewModel::randomizeParams,
            )
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
                                    viz.sceneId == dev.musicviz.render.scene.SceneIds.CURLFLOW ||
                                    viz.sceneId == dev.musicviz.render.scene.SceneIds.WATER,
                            isWaterScene = viz.sceneId == dev.musicviz.render.scene.SceneIds.WATER,
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
        CrystalButton("Import images", onClick = { picker.launch(arrayOf("image/*")) })
        textures.forEach { tex ->
            CrystalListRow(
                title = tex.name,
                subtitle = null,
                onClick = { viewModel.useTexture(tex.name) { path -> selectMilk(viewModel, visualizerView, path) } },
                thumbSeed = tex.name,
            ) {
                CrystalButton("Use", kind = CrystalButtonKind.GHOST, onClick = {
                    viewModel.useTexture(tex.name) { path -> selectMilk(viewModel, visualizerView, path) }
                })
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
            CrystalButton("Apply shader", onClick = { viewModel.applyCustomShader(source) })
            TextButton(onClick = {
                source = visualizerView.visualizerRenderer.customShaderFor(viz.sceneId) ?: ""
            }) { Text("Revert") }
        }
    }
}
