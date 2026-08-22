package dev.geode.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.data.Preset
import dev.geode.render.VisualizerView
import dev.geode.render.scene.CustomizeTab
import dev.geode.render.scene.SceneCapabilities
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.VisualStyleCatalog
import dev.geode.ui.theme.StoneIcon
import dev.geode.ui.theme.StoneIconArt

@Composable
fun VisualsHub(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onOpenNowPlaying: () -> Unit,
    liveBackdrop: Boolean = false,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Presets", "Styles", "Customize", "Textures", "Takes")
    val gui by viewModel.guiPrefs.collectAsStateWithLifecycle()
    val takes by viewModel.takeState.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        if (liveBackdrop) {
            VisualizerCanvasHost(visualizerView, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.1f)))
        }
        val bodyStyle =
            if (liveBackdrop) {
                LocalTextStyle.current.copy(
                    shadow = Shadow(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.9f), blurRadius = 10f),
                )
            } else {
                LocalTextStyle.current
            }
        val plate =
            if (liveBackdrop) {
                Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .readingPlate(
                        opacity = (gui.barOpacity * 0.62f).coerceIn(0.18f, 0.7f),
                        tint = MaterialTheme.colorScheme.surface,
                        corner = 20.dp,
                        glow = MaterialTheme.colorScheme.primary,
                    )
            } else {
                Modifier
            }
        ProvideTextStyle(bodyStyle) {
            Column(Modifier.fillMaxSize().then(plate)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        CrystalOverline(
                            when {
                                takes.recording -> "● Recording  ${formatTakeTime(takes.recordedMs)}"
                                takes.replaying != null -> "▶ ${takes.replaying}"
                                liveBackdrop -> "Live overlay"
                                else -> "Geode"
                            },
                            color = if (takes.recording) MaterialTheme.colorScheme.error else accentTextColor(),
                        )
                        GlowTitle("Visuals")
                    }
                    IconButton(onClick = {
                        if (takes.recording) viewModel.stopRecording() else viewModel.startRecording()
                    }) {
                        Icon(
                            if (takes.recording) Icons.Filled.StopCircle else Icons.Filled.FiberManualRecord,
                            if (takes.recording) "Stop recording this take" else "Record a take",
                            tint =
                                if (takes.recording) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    LocalContentColor.current
                                },
                        )
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
                    CrystalButton(compact = true, filled = false, onClick = onOpenNowPlaying) { Text("View live") }
                }
                CrystalTabs(titles = tabs, selected = tab, onSelect = { tab = it })
                when (tab) {
                    0 -> PresetsTreeTab(viewModel, visualizerView)
                    1 -> StylesTab(viewModel, visualizerView, onOpenTextures = { tab = 3 })
                    2 -> CustomizePanel(viewModel, visualizerView)
                    3 -> TexturesHubTab(viewModel, visualizerView)
                    4 -> TakesTab(viewModel)
                }
            }
        }
    }
}

internal fun vizPlaylistIndexOf(
    playlist: List<VizPlaylistEntry>,
    presetName: String,
): Int = playlist.indexOfFirst { it.presetName == presetName }

internal fun presetReplaceTarget(
    rawName: String,
    presets: List<Preset>,
): String? {
    val name = rawName.replace(" · ", " - ").trim().ifEmpty { "Preset" }
    return presets.firstOrNull { !BuiltInPresets.isBuiltIn(it.name) && it.name == name }?.name
}

internal fun builtInPresetSceneFamily(activeSceneId: String): String =
    when {
        VisualStyleCatalog.isHyperspace(activeSceneId) -> SceneIds.HYPERSPACE
        VisualStyleCatalog.isCymatics(activeSceneId) -> SceneIds.CYMATICS
        else -> activeSceneId
    }

internal fun builtInPresetMatchesScene(
    presetSceneId: String,
    activeSceneId: String,
): Boolean = presetSceneId == activeSceneId || presetSceneId == builtInPresetSceneFamily(activeSceneId)

@Composable
private fun PresetsTreeTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val viz by viewModel.vizState.collectAsStateWithLifecycle()
    val presetFolders by viewModel.presetFolders.collectAsStateWithLifecycle()
    val folders = presetFolders.folders
    var newFolder by remember { mutableStateOf("") }
    var saveName by remember { mutableStateOf("") }
    var saveFolder by rememberSaveable { mutableStateOf("") }
    var renamingFolder by remember { mutableStateOf<String?>(null) }
    var folderRenameText by remember { mutableStateOf("") }
    var movingPreset by remember { mutableStateOf<String?>(null) }
    var deletingPreset by remember { mutableStateOf<String?>(null) }
    var replacingPreset by remember { mutableStateOf<String?>(null) }
    val userPresets = viz.presets.filterNot { BuiltInPresets.isBuiltIn(it.name) }.distinctBy { it.name }
    val byFolder = userPresets.groupBy { presetFolders.folderOf(it.name) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var importNote by remember { mutableStateOf<String?>(null) }
    val presetFilePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.importPresetFile(uri) { name ->
                    importNote = name?.let { "Imported \"$it\"." } ?: "That file is not a Geode preset."
                }
            }
        }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                CrystalButton(compact = true, filled = false, onClick = {
                    val pasted = clipboardText(context)
                    importNote =
                        when {
                            pasted.isNullOrBlank() -> "The clipboard is empty."
                            else ->
                                viewModel.importPresetLink(pasted)?.let { "Imported \"$it\"." }
                                    ?: "That clipboard text is not a Geode preset link."
                        }
                }) { Text("Paste a shared preset") }
                CrystalButton(compact = true, filled = false, onClick = {
                    presetFilePicker.launch(arrayOf("*/*"))
                }) { Text("Open a preset file") }
            }
            importNote?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newFolder,
                    onValueChange = { newFolder = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("New folder name") },
                    singleLine = true,
                )
                CrystalButton(onClick = {
                    if (newFolder.isNotBlank()) {
                        viewModel.addPresetFolder(newFolder.trim())
                        newFolder = ""
                    }
                }) { Text("Add") }
            }
        }
        (listOf("") + folders).forEach { folder ->
            val inFolder = byFolder[folder].orEmpty()
            if (folder.isNotEmpty() || inFolder.isNotEmpty()) {
                item(key = "hdr_$folder") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (folder.isEmpty()) "Presets" else "📁 $folder",
                            style = MaterialTheme.typography.titleMedium,
                            color = accentTextColor(),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (folder.isNotEmpty()) {
                            IconButton(onClick = {
                                renamingFolder = folder
                                folderRenameText = folder
                            }) { StoneIconArt(StoneIcon.EDIT, "Rename this folder") }
                        }
                    }
                }
            }
            items(inFolder, key = { "p_${it.name}" }) { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { applyPresetLive(viewModel, visualizerView, p) }) {
                        StoneIconArt(StoneIcon.PLAY, "Apply", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { sharePreset(context, viewModel, p.name) }) {
                        StoneIconArt(StoneIcon.SHARE, "Share this preset")
                    }
                    val playlistIndex = vizPlaylistIndexOf(viz.vizPlaylist, p.name)
                    val inPlaylist = playlistIndex >= 0
                    IconButton(
                        onClick = {
                            if (inPlaylist) {
                                viewModel.removeVizPlaylistAt(playlistIndex)
                            } else {
                                viewModel.addToVizPlaylist(
                                    VizPlaylistEntry(sceneId = p.sceneId, presetName = p.name, label = p.name),
                                )
                            }
                        },
                    ) {
                        StoneIconArt(
                            StoneIcon.FAVORITE,
                            if (inPlaylist) "Remove from visual playlist" else "Add to visual playlist",
                            tint = if (inPlaylist) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = { movingPreset = p.name }) {
                        StoneIconArt(StoneIcon.FOLDER, "Move to another folder")
                    }
                    IconButton(onClick = { deletingPreset = p.name }) {
                        StoneIconArt(StoneIcon.DELETE, "Remove", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            Text(
                "Built-in",
                style = MaterialTheme.typography.titleMedium,
                color = accentTextColor(),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(
            viz.presets.filter { BuiltInPresets.isBuiltIn(it.name) && builtInPresetMatchesScene(it.sceneId, viz.sceneId) },
            key = { "b_${it.name}" },
        ) { p ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { applyPresetLive(viewModel, visualizerView, p) }) {
                    StoneIconArt(StoneIcon.PLAY, "Apply", tint = MaterialTheme.colorScheme.primary)
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
                CrystalButton(onClick = {
                    if (saveName.isNotBlank()) {
                        val existing = presetReplaceTarget(saveName, viz.presets)
                        if (existing != null) {
                            replacingPreset = existing
                        } else {
                            viewModel.savePreset(
                                saveName.trim(),
                                visualizerView.visualizerRenderer.customShaderFor(viewModel.vizState.value.sceneId),
                                saveFolder,
                            )
                            saveName = ""
                        }
                    }
                }) { Text("Save") }
            }
            if (folders.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    (listOf("") + folders).forEach { f ->
                        CrystalButton(compact = true, filled = saveFolder == f, onClick = { saveFolder = f }) {
                            Text(f.ifEmpty { "root" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    renamingFolder?.let { old ->
        val proposed = folderRenameText.trim()
        val collides = !proposed.equals(old, ignoreCase = true) && folders.any { it.equals(proposed, ignoreCase = true) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renamingFolder = null },
            title = { Text("Rename folder") },
            text = {
                Column {
                    OutlinedTextField(
                        value = folderRenameText,
                        onValueChange = { folderRenameText = it },
                        singleLine = true,
                    )
                    if (collides) {
                        Text(
                            "There is already a folder called \"$proposed\".",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                CrystalButton(enabled = proposed.isNotBlank() && !collides, onClick = {
                    viewModel.renamePresetFolder(old, proposed)
                    if (saveFolder == old) saveFolder = proposed
                    renamingFolder = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renamingFolder = null }) { Text("Cancel") } },
        )
    }
    movingPreset?.let { name ->
        val current = presetFolders.folderOf(name)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { movingPreset = null },
            title = { Text("Move \"$name\"") },
            text = {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    (listOf("") + folders).forEach { f ->
                        CrystalButton(compact = true, filled = f == current, onClick = {
                            viewModel.movePresetToFolder(name, f)
                            movingPreset = null
                        }) { Text(f.ifEmpty { "root" }, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingPreset = null }) { Text("Close") } },
        )
    }
    replacingPreset?.let { name ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { replacingPreset = null },
            title = { Text("Replace \"$name\"?") },
            text = {
                Text(
                    "A preset with this name already exists. Saving replaces its look " +
                        "for good — there is no undo. Share it first if you might want it back.",
                )
            },
            confirmButton = {
                CrystalButton(onClick = {
                    viewModel.savePreset(
                        saveName.trim(),
                        visualizerView.visualizerRenderer.customShaderFor(viewModel.vizState.value.sceneId),
                        saveFolder,
                    )
                    saveName = ""
                    replacingPreset = null
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { replacingPreset = null }) { Text("Cancel") } },
        )
    }
    deletingPreset?.let { name ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deletingPreset = null },
            title = { Text("Delete \"$name\"?") },
            text = {
                Text(
                    "Removes this preset and its file for good — there is no undo. " +
                        "Share it first if you might want it back.",
                )
            },
            confirmButton = {
                CrystalButton(onClick = {
                    viewModel.deletePreset(name)
                    deletingPreset = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingPreset = null }) { Text("Cancel") }
            },
        )
    }
}

private fun clipboardText(context: android.content.Context): String? =
    runCatching {
        context
            .getSystemService(android.content.ClipboardManager::class.java)
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
    }.getOrNull()

private fun sharePreset(
    context: android.content.Context,
    viewModel: PlayerViewModel,
    name: String,
) {
    val link = viewModel.presetShareLink(name)
    val send =
        if (link != null) {
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Geode preset: $name")
                putExtra(android.content.Intent.EXTRA_TEXT, link)
            }
        } else {
            val file = viewModel.presetFile(name) ?: return
            val uri =
                androidx.core.content.FileProvider
                    .getUriForFile(context, context.packageName + ".presets", file)
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    runCatching { context.startActivity(android.content.Intent.createChooser(send, "Share preset")) }
}

private fun applyPresetLive(
    viewModel: PlayerViewModel,
    @Suppress("UNUSED_PARAMETER") visualizerView: VisualizerView,
    p: Preset,
) {
    viewModel.applyPreset(p)
}

@Composable
private fun StylesTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onOpenTextures: () -> Unit,
) {
    var sub by rememberSaveable { mutableStateOf(0) }
    val viz by viewModel.vizState.collectAsStateWithLifecycle()
    val pickScene: (String) -> Unit = { viewModel.selectScene(it) }
    Column(Modifier.fillMaxSize()) {
        suggestedSceneToOffer(viz.suggestedSceneId, viz.sceneId)?.let { suggested ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Suggested for this track",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CrystalButton(compact = true, filled = false, onClick = { pickScene(suggested) }) {
                    Text(sceneDisplayLabel(suggested), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        CrystalTabs(
            titles = listOf("Silk", "Life", "Mycelium", "Acid", "Shaders", "Fluid", "Cymatics", "Hyperspace", "Beam", "MilkDrop"),
            selected = sub,
            onSelect = { sub = it },
        )
        when (sub) {
            0 -> SceneList(VisualStyleCatalog.silkIds, viz.sceneId, pickScene)
            1 -> SceneList(VisualStyleCatalog.lifeIds, viz.sceneId, pickScene)
            2 -> SceneList(VisualStyleCatalog.mycoIds, viz.sceneId, pickScene)
            3 -> SceneList(VisualStyleCatalog.acidIds, viz.sceneId, pickScene)
            4 -> SceneList(SceneCapabilities.SHADER_SCENES.keys.toList(), viz.sceneId, pickScene)
            5 -> SceneList(listOf(SceneIds.FLUID, SceneIds.CURLFLOW, SceneIds.WATER), viz.sceneId, pickScene)
            6 -> SceneList(VisualStyleCatalog.cymaticsIds, viz.sceneId, pickScene)
            7 -> SceneList(VisualStyleCatalog.hyperspaceIds, viz.sceneId, pickScene)
            8 -> SceneList(listOf(SceneIds.BEAM), viz.sceneId, pickScene)
            9 -> MilkDropTab(viewModel, visualizerView, onOpenTextures)
        }
    }
}

internal fun suggestedSceneToOffer(
    suggestedSceneId: String?,
    activeSceneId: String,
): String? = suggestedSceneId?.takeIf { it != activeSceneId }

internal fun sceneDisplayLabel(id: String): String {
    val catalogued = VisualStyleCatalog.label(id)
    if (catalogued != id) return catalogued
    return id.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}

@Composable
private fun SceneList(
    ids: List<String>,
    current: String,
    onPick: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(ids) { id ->
            val sel = id == current
            Row(
                Modifier.fillMaxWidth().clickable { onPick(id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp), contentAlignment = Alignment.Center) {
                    if (sel) CrystalGem(MaterialTheme.colorScheme.primary, size = 6.dp)
                }
                Text(
                    sceneDisplayLabel(id),
                    Modifier.padding(start = 10.dp),
                    color = if (sel) accentTextColor() else LocalContentColor.current,
                )
            }
        }
    }
}

@Composable
private fun MilkDropTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onOpenTextures: () -> Unit,
) {
    var refresh by remember { mutableStateOf(0) }
    val viz by viewModel.vizState.collectAsStateWithLifecycle()
    val milkFiles = remember(refresh) { viewModel.userMilkPresets() }
    val loaded by viewModel.activeMilkPath.collectAsStateWithLifecycle()
    var packReport by remember { mutableStateOf<dev.geode.data.MilkPackImporter.Report?>(null) }
    var singleMissesTexture by remember { mutableStateOf(false) }
    val milkFolderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                viewModel.importMilkFolderAsync(uri) { report ->
                    packReport = report
                    refresh++
                }
            }
        }
    val milkPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                viewModel.importMilkPresetAsync(uri) { path ->
                    if (path != null) {
                        singleMissesTexture =
                            dev.geode.data.MilkPackImporter.missesATexture(
                                java.io.File(path),
                                java.io.File(java.io.File(path).parentFile, "textures"),
                            )
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
            CrystalButton(onClick = { milkPicker.launch(arrayOf("*/*")) }) { Text("Load .milk file") }
            CrystalButton(filled = false, onClick = { milkFolderPicker.launch(null) }) { Text("Import folder…") }
            CrystalButton(filled = false, onClick = onOpenTextures) { Text("Textures…") }
        }
        packReport?.let { r ->
            Text(
                buildString {
                    append("Imported ${r.presets} presets and ${r.textures} textures")
                    if (r.skipped > 0) append(", ${r.skipped} skipped (already present or unreadable)")
                    append('.')
                    if (r.presetsMissingTextures > 0) {
                        append(
                            " ${r.presetsMissingTextures} reference textures you don't have - " +
                                "import those images too or they render without them.",
                        )
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (r.presetsMissingTextures > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        if (viz.sceneId == SceneIds.MILKDROP) {
            viz.shaderError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            if (singleMissesTexture) {
                Text(
                    "This preset references textures you have not imported - it will render " +
                        "without them. Textures… imports the images it wants.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Text("Your .milk presets", style = MaterialTheme.typography.titleMedium, color = accentTextColor())
        if (milkFiles.isEmpty()) {
            Text("None yet — load a .milk file or save one from the milkdrop scene.", style = MaterialTheme.typography.bodySmall)
        }
        milkFiles.forEach { f ->
            val active = f.absolutePath == loaded
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectMilk(viewModel, visualizerView, f.absolutePath)
                        refresh++
                    }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp), contentAlignment = Alignment.Center) {
                    if (active) CrystalGem(MaterialTheme.colorScheme.primary, size = 6.dp)
                }
                Text(
                    f.nameWithoutExtension,
                    Modifier.padding(start = 10.dp),
                    color = if (active) accentTextColor() else LocalContentColor.current,
                )
            }
        }
    }
}

private fun selectMilk(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    path: String,
) {
    viewModel.selectScene(SceneIds.MILKDROP)
    visualizerView.visualizerRenderer.loadMilkPreset(path)
}

@Composable
internal fun CustomizePanel(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val viz by viewModel.vizState.collectAsStateWithLifecycle()
    var sub by rememberSaveable { mutableStateOf(0) }
    val isShader = SceneCapabilities.hasShaderLook(viz.sceneId)
    val isCymatics = SceneCapabilities.isCymatics(viz.sceneId)
    val isHyperspace = SceneCapabilities.isHyperspace(viz.sceneId)
    val tabs: List<CustomizeTab?> =
        CustomizeTab.entries.filter {
            when (it) {
                CustomizeTab.CYMATICS -> isCymatics
                CustomizeTab.HYPERSPACE -> isHyperspace
                else -> true
            }
        } + if (isShader) listOf(null) else emptyList()
    val titles = tabs.map { it?.title ?: "GLSL" }
    var shownTitle by rememberSaveable { mutableStateOf(titles.getOrNull(sub)) }
    LaunchedEffect(titles) {
        if (titles.getOrNull(sub) != shownTitle) {
            sub = titles.indexOf(shownTitle).coerceAtLeast(0)
            shownTitle = titles[sub]
        }
    }
    Column(Modifier.fillMaxSize()) {
        CrystalTabs(
            titles = titles,
            selected = sub,
            onSelect = {
                sub = it
                shownTitle = titles[it]
            },
        )
        CustomizeToolbar(viewModel, viz.params, tabs.getOrNull(sub))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val locked by viewModel.lockedParams.collectAsStateWithLifecycle()
            androidx.compose.runtime.CompositionLocalProvider(
                LocalParamLocks provides (locked to viewModel::toggleParamLock),
            ) {
                val p = viz.params
                val onChange: (dev.geode.render.scene.SceneParams) -> Unit = { viewModel.setSceneParams(it) }
                val lfos by viewModel.lfos.collectAsStateWithLifecycle()
                when (tabs.getOrNull(sub)) {
                    CustomizeTab.MOTION -> MotionTab(p, onChange)
                    CustomizeTab.SHAPE ->
                        ShapeTab(
                            p,
                            onChange,
                            isShaderLookScene = isShader,
                            isPointSpriteScene = SceneCapabilities.usesPointSprites(viz.sceneId),
                            particleLayerOff = SceneCapabilities.isFluid(viz.sceneId) && !p.fluidParticlesEnabled,
                            isBeamScene = SceneCapabilities.isBeam(viz.sceneId),
                        )
                    CustomizeTab.BEHAVIOR ->
                        BehaviorTab(
                            p,
                            onChange,
                            transitionId = viz.transitionId,
                            transitionDurationSec = viz.transitionDurationSec,
                            onTransitionId = viewModel::setTransitionId,
                            onTransitionDuration = viewModel::setTransitionDuration,
                            attack = viz.attack,
                            decay = viz.decay,
                            onReactivityChange = viewModel::setReactivity,
                            intelligenceMode = viz.intelligenceMode,
                            onIntelligenceModeChange = viewModel::setIntelligenceMode,
                        )
                    CustomizeTab.COLOR -> {
                        val artNote by viewModel.artPaletteNote.collectAsStateWithLifecycle()
                        ColorTab(
                            p,
                            onChange,
                            isShaderLookScene = isShader,
                            onTakeArtworkPalette = viewModel::applyArtworkPalette,
                            artworkNote = artNote,
                        )
                    }
                    CustomizeTab.FX -> {
                        val adsrs by viewModel.adsrs.collectAsStateWithLifecycle()
                        FxTab(
                            p,
                            onChange,
                            lfos = lfos,
                            onLfoChange = viewModel::setLfo,
                            adsr = adsrs,
                            onAdsrChange = viewModel::setAdsr,
                        )
                    }
                    CustomizeTab.FLUID ->
                        FluidTab(
                            p,
                            onChange,
                            isFluidScene = SceneCapabilities.isFluid(viz.sceneId),
                            isJourneyScene = SceneCapabilities.hasJourney(viz.sceneId),
                            isWaterScene = SceneCapabilities.isWater(viz.sceneId),
                            isEmitterScene = SceneCapabilities.hasEmitters(viz.sceneId),
                            isParticleLayerScene = SceneCapabilities.hasParticleLayer(viz.sceneId),
                            injectionError = if (SceneCapabilities.isFluid(viz.sceneId)) viz.shaderError else null,
                            onApplyInjectionShaders = { force, dye ->
                                visualizerView.visualizerRenderer.submitFluidInjectionShaders(force, dye)
                            },
                        )
                    CustomizeTab.CYMATICS -> CymaticsTab(p, activeSceneId = viz.sceneId, onChange = onChange)
                    CustomizeTab.HYPERSPACE -> HyperspaceTab(p, activeSceneId = viz.sceneId, onChange = onChange)
                    null -> GlslHubTab(viewModel, visualizerView)
                }
            }
        }
    }
}

@Composable
private fun CustomizeToolbar(
    viewModel: PlayerViewModel,
    params: dev.geode.render.scene.SceneParams,
    tab: CustomizeTab?,
) {
    var confirmReset by remember { mutableStateOf(false) }
    val changed = remember(params) { CustomizeSummary.changedCount(params) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CrystalButton(
            compact = true,
            enabled = tab != null,
            onClick = { tab?.let(viewModel::randomizeParams) },
        ) {
            Icon(Icons.Filled.Casino, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (tab == null) "Randomize" else "Randomize ${tab.title}")
        }
        CrystalButton(compact = true, filled = false, enabled = changed > 0, onClick = { confirmReset = true }) {
            Text("Reset")
        }
        Text(
            if (changed == 0) "defaults" else "$changed changed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmReset) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset customizations?") },
            text = {
                Text(
                    "Puts all $changed changed controls back to their defaults, across every tab. " +
                        "Saved presets are not touched — reapply one to get its look back.",
                )
            },
            confirmButton = {
                CrystalButton(onClick = {
                    viewModel.resetSceneParams()
                    confirmReset = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            },
        )
    }
}

internal fun takeRenameError(
    from: String,
    proposed: String,
    existingNames: List<String>,
): String? =
    when {
        proposed.isEmpty() -> "A take needs a name."
        !proposed.equals(from, ignoreCase = true) &&
            existingNames.any { it.equals(proposed, ignoreCase = true) } ->
            "There is already a take called \"$proposed\"."
        else -> null
    }

private fun formatTakeTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun TakesTab(viewModel: PlayerViewModel) {
    val takes by viewModel.takeState.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                if (takes.recording) {
                    "Recording — ${takes.recordedEvents} keyframes, ${formatTakeTime(takes.recordedMs)}. " +
                        "Go and perform; press the stop button in the header when you are done."
                } else {
                    "A take stores what the visuals were doing, moment by moment — every slider, " +
                        "colour and style change, as you made them. It replays over the live canvas " +
                        "and can be re-rendered at any quality later. Press ● in the header to start."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        if (takes.replaying != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text(
                        "Replaying ${takes.replaying} — ${formatTakeTime(takes.replayMs)} / " +
                            formatTakeTime(takes.replayEndMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = accentTextColor(),
                    )
                    LinearProgressIndicator(
                        progress = {
                            if (takes.replayEndMs > 0) {
                                (takes.replayMs.toFloat() / takes.replayEndMs).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(2.dp).padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        items(takes.takes, key = { "take_${it.name}" }) { take ->
            val playing = takes.replaying == take.name
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(take.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatTakeTime(take.durationMs)} · ${take.eventCount} keyframes · " +
                            "${take.sizeBytes / 1024} KB" +
                            if (takes.exportTake == take.name) " · exports this" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    if (playing) viewModel.stopReplay() else viewModel.playTake(take.name)
                }) {
                    if (playing) {
                        Icon(Icons.Filled.Stop, "Stop replay", tint = MaterialTheme.colorScheme.primary)
                    } else {
                        StoneIconArt(StoneIcon.PLAY, "Replay this take", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = {
                    viewModel.setExportTake(if (takes.exportTake == take.name) null else take.name)
                }) {
                    StoneIconArt(
                        StoneIcon.FAVORITE,
                        "Render this take on the next export",
                        tint =
                            if (takes.exportTake == take.name) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                    )
                }
                IconButton(onClick = {
                    renaming = take.name
                    renameText = take.name
                }) { StoneIconArt(StoneIcon.EDIT, "Rename") }
                IconButton(onClick = { deleting = take.name }) {
                    StoneIconArt(StoneIcon.DELETE, "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (takes.takes.isEmpty() && !takes.recording) {
            item {
                Text("No takes recorded yet.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    renaming?.let { old ->
        val proposed = renameText.trim()
        val renameError = takeRenameError(old, proposed, takes.takes.map { it.name })
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename take") },
            text = {
                Column {
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
                    renameError?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                CrystalButton(enabled = renameError == null, onClick = {
                    viewModel.renameTake(old, proposed)
                    renaming = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { name ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete take \"$name\"?") },
            text = {
                Text("Deletes this recorded performance for good — there is no undo.")
            },
            confirmButton = {
                CrystalButton(onClick = {
                    viewModel.deleteTake(name)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TexturesHubTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val textures by viewModel.textures.collectAsStateWithLifecycle()
    var deletingTexture by remember { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                viewModel.importTextures(uris) { visualizerView.visualizerRenderer.reloadCurrentMilkPreset() }
            }
        }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CrystalButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Import images") }
        textures.forEach { tex ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tex.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                CrystalButton(compact = true, filled = false, onClick = {
                    viewModel.useTexture(tex.name) { path -> selectMilk(viewModel, visualizerView, path) }
                }) { Text("Use") }
                IconButton(onClick = { deletingTexture = tex.name }) {
                    StoneIconArt(StoneIcon.DELETE, "Delete this texture", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (textures.isEmpty()) Text("No textures imported yet.", style = MaterialTheme.typography.bodySmall)
    }
    deletingTexture?.let { name ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deletingTexture = null },
            title = { Text("Delete texture \"$name\"?") },
            text = {
                Text(
                    "Removes this image for good — there is no undo. MilkDrop presets that " +
                        "reference it will show noise or black until it is imported again.",
                )
            },
            confirmButton = {
                CrystalButton(onClick = {
                    viewModel.removeTexture(name)
                    deletingTexture = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deletingTexture = null }) { Text("Cancel") }
            },
        )
    }
}

private const val MAX_SAVED_SHADER_DRAFT_CHARS = 8 * 1024

private val ShaderDraftSaver =
    Saver<String, String>(
        save = { draft -> draft.takeIf { it.length <= MAX_SAVED_SHADER_DRAFT_CHARS } },
        restore = { it },
    )

@Composable
private fun GlslHubTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val viz by viewModel.vizState.collectAsStateWithLifecycle()
    var source by rememberSaveable(viz.sceneId, stateSaver = ShaderDraftSaver) {
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
            CrystalButton(onClick = { viewModel.applyCustomShader(source) }) { Text("Apply shader") }
            TextButton(onClick = {
                source = visualizerView.visualizerRenderer.customShaderFor(viz.sceneId) ?: ""
            }) { Text("Revert") }
        }
    }
}
