package dev.musicviz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
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
                    OutlinedButton(onClick = onOpenNowPlaying) { Text("View live") }
                }
                ScrollableTabRow(
                    selectedTabIndex = tab,
                    edgePadding = 8.dp,
                    containerColor = Color.Transparent,
                ) {
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
        ScrollableTabRow(selectedTabIndex = sub, edgePadding = 8.dp, containerColor = Color.Transparent) {
            listOf("Particles", "Shaders", "Fluid", "MilkDrop").forEachIndexed { i, t ->
                Tab(selected = sub == i, onClick = { sub = i }, text = { Text(t) })
            }
        }
        when (sub) {
            0 -> SceneList(VisualizerRenderer.PARTICLE_SCENES, viz.sceneId, pickScene)
            1 -> SceneList(VisualizerRenderer.SHADER_SCENES.keys.toList(), viz.sceneId, pickScene)
            2 -> SceneList(listOf(SceneIds.FLUID, SceneIds.CURLFLOW, SceneIds.WATER), viz.sceneId, pickScene)
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
    // Scene switch flows through vizState -> EnginePlumbing like every other
    // pick; only the .milk load itself talks to the renderer directly.
    viewModel.selectScene(SceneIds.MILKDROP)
    visualizerView.visualizerRenderer.loadMilkPreset(path)
    viewModel.noteMilkPreset(path)
}

// ---------------------------------------------------------------- Customize

// Fluid-tab gating. The fluid styles each read a DIFFERENT slice of the
// fluid params, so the tab is gated per slice rather than per style:
// showing a control the active style ignores is as much of a bug as hiding
// one it reads. Kept as plain functions (not inline expressions) so the
// slices are unit-testable and documented in one place.

/** Only FluidScene runs the Navier-Stokes solver, dye/ink and its look passes. */
internal fun isFluidSceneId(sceneId: String): Boolean = sceneId == SceneIds.FLUID

/** Styles driven by FluidChoreography's spawn/catch journey progression. */
internal fun isJourneySceneId(sceneId: String): Boolean =
    sceneId == SceneIds.FLUID ||
        sceneId == SceneIds.CURLFLOW ||
        sceneId == SceneIds.WATER

/**
 * Styles that run the shared FluidEmitters splat schedule and the
 * FluidQuality tiers. WaterScene reuses the emitter schedule verbatim
 * (WaterScene.kt "Emitter schedule reused verbatim") and its own quality
 * tiers key off fluidQuality/fluidAutoQuality, so those controls belong on
 * Water even though the solver ones do not.
 */
internal fun isEmitterSceneId(sceneId: String): Boolean = sceneId == SceneIds.FLUID || sceneId == SceneIds.WATER

/** Only WaterScene reads the heightfield surface params. */
internal fun isWaterSceneId(sceneId: String): Boolean = sceneId == SceneIds.WATER

/**
 * Styles that run the shared FluidParticles lifecycle layer, i.e. the ones
 * that read `fluidParticleDrag`. CURLFLOW *is* that layer (CurlFlowScene's
 * "particles.drag = params.fluidParticleDrag"), yet the drag slider used to
 * live in the FLUID-only Particles section AND behind `fluidParticlesEnabled`,
 * a param CurlFlow never reads - so a control the style genuinely consumes was
 * unreachable on it. WATER has no particle layer at all.
 */
internal fun isParticleLayerSceneId(sceneId: String): Boolean = sceneId == SceneIds.FLUID || sceneId == SceneIds.CURLFLOW

/**
 * Shape/Color gating, same rule as the Fluid tab: a control only shows up on
 * the styles that actually read it.
 *
 * Most of Shape and Color survived the "customizations on every style" work
 * because the COMPOSITE pass re-implements them (`composite_frag.glsl` has
 * uPostWarp / uPostRipple / uPostTwist / uPostKaleido / uPostTile /
 * uPostPixelate / uPostPosterize / uPostBloom / uPostSolarize / uPostInvert /
 * uPostHue / uPostSat ... ), so they bend particles, MilkDrop and the fluid
 * family too. Four do not: `morph`, `paletteMix`, `duotone` and the second
 * palette slot (`palette2`, resolved into `palette2Base`/`palette2Range`).
 * They are uploaded ONLY by `ShaderScene` - uMorph, uPaletteMix, uDuotone,
 * uPal2Base, uPal2Range, declared by all twenty scene fragment shaders - and
 * the composite has no counterpart uniform for any of them. On every other
 * style those four sliders move nothing, so they are hidden there.
 */
internal fun isShaderLookSceneId(sceneId: String): Boolean = sceneId in VisualizerRenderer.SHADER_SCENES

/**
 * Styles that draw the CPU particle system, i.e. the only readers of
 * `particleShape`. `ParticleSceneBase.draw` is the one place `uShape` is
 * uploaded (`particle_frag.glsl`'s shapeMask), and its five subclasses -
 * Nebula / Bursts / Swarm / Fountain / Orbits - are exactly
 * [VisualizerRenderer.PARTICLE_SCENES]. The fluid point layer
 * (`FluidParticles`) has no shape uniform at all: its sprites are always
 * round, so the chip row is dead on FLUID and CURLFLOW too, not only on
 * shader / MilkDrop / Water styles.
 */
internal fun isParticleShapeSceneId(sceneId: String): Boolean = sceneId in VisualizerRenderer.PARTICLE_SCENES

/**
 * Styles that size a point sprite from `particleSize`, a strictly wider set
 * than [isParticleShapeSceneId]: `ParticleSceneBase.kt` uploads it as `uSize`
 * (`:152`), `FluidScene.kt` folds it into `pointScale` (`:333`) and
 * `CurlFlowScene.kt` into its `particles.draw` point scale (`:212`). The fluid
 * half of that is exactly [isParticleLayerSceneId] - the same FluidParticles
 * layer that reads `fluidParticleDrag` - so this predicate is composed from
 * the two rather than restating FLUID/CURLFLOW a third time; if the layer ever
 * gains or loses a style, both move together.
 *
 * Note FLUID can switch its point layer off (`fluidParticlesEnabled`), which
 * makes the slider *temporarily* inert there. That is deliberately NOT part of
 * this predicate: gating is about what a style can read, and a control the
 * user can revive with one checkbox should not vanish from a different tab
 * with no visible cause. The Shape tab says so instead.
 */
internal fun isPointSpriteSceneId(sceneId: String): Boolean = isParticleShapeSceneId(sceneId) || isParticleLayerSceneId(sceneId)

@Composable
private fun CustomizeHubTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val viz by viewModel.vizState.collectAsState()
    var sub by rememberSaveable { mutableStateOf(0) }
    // Shader styles own the GLSL tab AND are the only readers of the
    // shader-only look params gated out of Shape/Color - one predicate, so
    // the two can never drift apart.
    val isShader = isShaderLookSceneId(viz.sceneId)
    val tabs = listOf("Motion", "Shape", "Behavior", "Color", "FX", "Fluid") + if (isShader) listOf("GLSL") else emptyList()
    LaunchedEffect(isShader) { if (!isShader && sub >= 6) sub = 0 }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = sub, edgePadding = 8.dp, containerColor = Color.Transparent) {
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
                    1 ->
                        ShapeTab(
                            p,
                            onChange,
                            isShaderLookScene = isShader,
                            isParticleShapeScene = isParticleShapeSceneId(viz.sceneId),
                            isPointSpriteScene = isPointSpriteSceneId(viz.sceneId),
                            particleLayerOff = isFluidSceneId(viz.sceneId) && !p.fluidParticlesEnabled,
                        )
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
                    3 -> ColorTab(p, onChange, isShaderLookScene = isShader)
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
                            isFluidScene = isFluidSceneId(viz.sceneId),
                            isJourneyScene = isJourneySceneId(viz.sceneId),
                            isWaterScene = isWaterSceneId(viz.sceneId),
                            isEmitterScene = isEmitterSceneId(viz.sceneId),
                            isParticleLayerScene = isParticleLayerSceneId(viz.sceneId),
                            injectionError = if (isFluidSceneId(viz.sceneId)) viz.shaderError else null,
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
