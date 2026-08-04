package dev.musicviz.ui

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
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
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
import dev.musicviz.render.scene.CustomizeTab
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.VisualStyleCatalog

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
    val tabs = listOf("Presets", "Styles", "Customize", "Textures", "Takes")
    val gui by viewModel.guiPrefs.collectAsState()
    val takes by viewModel.takeState.collectAsState()
    Box(Modifier.fillMaxSize()) {
        if (liveBackdrop) {
            VisualizerCanvasHost(visualizerView, Modifier.fillMaxSize())
            // A whisper of dim over the whole canvas - the reading plate below
            // does the legibility work, so this only takes the very brightest
            // frames off the top rather than greying the visuals down. The
            // scrim role rather than hardcoded black, so a theme could
            // legitimately re-tint it.
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
        // Semi-transparent plate under the menu: the visuals read through it,
        // the text reads on it. Inset so the live canvas frames the panel and
        // it is obvious the visuals are still running underneath. Its opacity
        // follows the Settings "Bar opacity" slider like the rest of the
        // chrome, scaled down because this one has to stay see-through.
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
                                else -> "MusicViz"
                            },
                            color = if (takes.recording) MaterialTheme.colorScheme.error else accentTextColor(),
                        )
                        GlowTitle("Visuals")
                    }
                    // Record sits in the header, not in the Takes tab: a
                    // performance is made on the Customize and Styles tabs, and
                    // a control you have to leave the thing you are performing
                    // on to reach is a control you do not use mid-set.
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
    // Which folder the rename dialog is editing, which preset the move dialog
    // is filing and which the delete dialog is confirming. All three are held
    // here rather than per row because a LazyColumn row that scrolls off
    // screen is disposed, and a dialog owned by one would vanish mid-edit.
    var renamingFolder by remember { mutableStateOf<String?>(null) }
    var folderRenameText by remember { mutableStateOf("") }
    var movingPreset by remember { mutableStateOf<String?>(null) }
    var deletingPreset by remember { mutableStateOf<String?>(null) }
    val userPresets = viz.presets.filterNot { BuiltInPresets.isBuiltIn(it.name) }.distinctBy { it.name }
    val byFolder = userPresets.groupBy { viewModel.presetFolderOf(it.name) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var importNote by remember { mutableStateOf<String?>(null) }
    // The file half of Share. A preset that carries a custom shader - or a
    // .milk, which every MilkDrop preset now does - is too long to travel as a
    // link, so it is shared as its .json; without this it arrived as a file
    // nothing could open.
    val presetFilePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importNote =
                    viewModel.importPresetFile(uri)?.let { "Imported \"$it\"." }
                        ?: "That file is not a MusicViz preset."
            }
        }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            // Share and import live at the top of the list, together: a preset
            // arrives as a message, and the thing you do on receiving one is
            // paste it, not go looking for a menu.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                CrystalButton(compact = true, filled = false, onClick = {
                    val pasted = clipboardText(context)
                    importNote =
                        when {
                            pasted.isNullOrBlank() -> "The clipboard is empty."
                            else ->
                                viewModel.importPresetLink(pasted)?.let { "Imported \"$it\"." }
                                    ?: "That clipboard text is not a MusicViz preset link."
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
                        folderRefresh++
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
                        // Root is not a directory on disk - it is the absence of
                        // one - so only a real folder gets the pencil; on
                        // "Presets" the dialog would have nothing to rename.
                        if (folder.isNotEmpty()) {
                            IconButton(onClick = {
                                renamingFolder = folder
                                folderRenameText = folder
                            }) { Icon(Icons.Filled.Edit, "Rename this folder") }
                        }
                    }
                }
            }
            items(inFolder, key = { "p_${it.name}" }) { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { applyPresetLive(viewModel, visualizerView, p) }) {
                        Icon(Icons.Filled.PlayArrow, "Apply", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { sharePreset(context, viewModel, p.name) }) {
                        Icon(Icons.Filled.Share, "Share this preset")
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
                    IconButton(onClick = { movingPreset = p.name }) {
                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Move to another folder")
                    }
                    IconButton(onClick = { deletingPreset = p.name }) {
                        Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.error)
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
                CrystalButton(onClick = {
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
        // A rename onto a folder that already exists is refused rather than
        // merged: PresetStore.renameFolder is a directory rename, which the
        // filesystem fails silently once the destination holds presets, so the
        // user would be told nothing and see nothing move. Case-insensitive
        // because the folder is a directory name and this is a phone, where
        // "Chill" and "chill" are the same folder to everyone but the user.
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
                    // The Save-into chip row picks a folder by name, so a
                    // rename has to carry the selection over with it or the
                    // next Save quietly recreates the old folder.
                    if (saveFolder == old) saveFolder = proposed
                    renamingFolder = null
                    folderRefresh++
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renamingFolder = null }) { Text("Cancel") } },
        )
    }
    movingPreset?.let { name ->
        val current = viewModel.presetFolderOf(name)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { movingPreset = null },
            title = { Text("Move \"$name\"") },
            text = {
                // The same chip row Save uses to choose a destination, so
                // filing a preset looks the same whether it happens when it is
                // saved or afterwards. Root is offered as a destination too:
                // without it a preset dropped into the wrong folder could never
                // come back out again.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    (listOf("") + folders).forEach { f ->
                        CrystalButton(compact = true, filled = f == current, onClick = {
                            viewModel.movePresetToFolder(name, f)
                            movingPreset = null
                            // The moved preset keeps its name, so vizState comes
                            // back equal and the StateFlow conflates the update
                            // away; only this counter re-groups the list.
                            folderRefresh++
                        }) { Text(f.ifEmpty { "root" }, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingPreset = null }) { Text("Close") } },
        )
    }
    deletingPreset?.let { name ->
        // Delete is the one verb on a preset row with no way back - the .json
        // is removed from disk and there is no undo store - so it confirms
        // like Reset does instead of firing on a tap that landed beside Move.
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

/** Clipboard text, or null when the clipboard holds nothing readable. */
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

/**
 * Shares a preset as a link, falling back to its file when the link would be
 * too long to survive a chat app (a preset carrying a custom shader).
 */
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
                putExtra(android.content.Intent.EXTRA_SUBJECT, "MusicViz preset: $name")
                putExtra(android.content.Intent.EXTRA_TEXT, link)
            }
        } else {
            // Too long for a message: send the .json itself, which has no
            // length limit and is what a shader-carrying preset needs.
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
        CrystalTabs(
            // Cymatics and Hyperspace are each their own family, neither a
            // fluid style nor a shader one. Cymatics is the style whose
            // picture IS the sound (a Chladni plate) rather than a look driven
            // by it; Hyperspace is the only raymarched one - a room of 3D
            // fractals, each alive on its own clock, walking a five-act story;
            // Beam is the oscilloscope, whose trace is the waveform itself.
            titles = listOf("Particles", "Shaders", "Fluid", "Cymatics", "Hyperspace", "Beam", "MilkDrop"),
            selected = sub,
            onSelect = { sub = it },
        )
        when (sub) {
            0 -> SceneList(VisualizerRenderer.PARTICLE_SCENES, viz.sceneId, pickScene)
            1 -> SceneList(VisualizerRenderer.SHADER_SCENES.keys.toList(), viz.sceneId, pickScene)
            2 -> SceneList(listOf(SceneIds.FLUID, SceneIds.CURLFLOW, SceneIds.WATER), viz.sceneId, pickScene)
            3 -> SceneList(VisualStyleCatalog.cymaticsIds, viz.sceneId, pickScene)
            4 -> SceneList(VisualStyleCatalog.hyperspaceIds, viz.sceneId, pickScene)
            5 -> SceneList(listOf(SceneIds.BEAM), viz.sceneId, pickScene)
            6 -> MilkDropTab(viewModel, visualizerView, onOpenTextures)
        }
    }
}

/**
 * Human label for a scene id on a style tile. Catalogued substyles carry
 * authored labels ("hyper_liquid_warp" is "Liquid Warp"); ids the catalog does
 * not know fall back to the id itself, title-cased with underscores opened up,
 * so a persistence identifier never reads as one on screen.
 */
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

/** Dedicated MilkDrop tab: Load .milk, user list, Next, Textures shortcut. */
@Composable
private fun MilkDropTab(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onOpenTextures: () -> Unit,
) {
    var refresh by remember { mutableStateOf(0) }
    val viz by viewModel.vizState.collectAsState()
    val milkFiles = remember(refresh) { viewModel.userMilkPresets() }
    // Which one is on screen. Collected rather than read once, so the marker
    // follows the engine even when a preset apply, a take replay or the random
    // mode changed the .milk from somewhere else entirely.
    val loaded by viewModel.activeMilkPath.collectAsState()
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
            CrystalButton(onClick = { milkPicker.launch(arrayOf("*/*")) }) { Text("Load .milk file") }
            CrystalButton(filled = false, onClick = onOpenTextures) { Text("Textures…") }
        }
        // A .milk that fails to parse (or a texture it references that is not
        // imported) reports through the same channel the GLSL editors use, and
        // used to land on a screen the user was not on: the engine just kept
        // showing the previous preset with no clue why the new one never
        // arrived. It belongs where the file was picked.
        if (viz.sceneId == SceneIds.MILKDROP) {
            viz.shaderError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
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

/**
 * Styles driven by FluidChoreography's spawn/catch journey progression.
 * Every control in that section has a reader on all three: `fluidSpawnPath`,
 * `fluidSpawnPoints`, `fluidSpawnProgress` and `fluidCatchPoints` feed
 * `choreography` (`WaterScene.kt:232-235`), `fluidCatchPull` the emitters
 * (`:247`) and `fluidCatchRadius` `WaterMath.catchWellRadius` (`:256`).
 * `fluidParticleLife` does NOT - WaterScene has no particle layer to age -
 * so that slider hangs off [isParticleLayerSceneId] instead.
 */
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

/** Only BeamScene reads the oscilloscope-trace params. */
internal fun isBeamSceneId(sceneId: String): Boolean = sceneId == SceneIds.BEAM

/**
 * Only CymaticsScene reads the Chladni-plate params, so the whole Cymatics
 * tab appears and disappears with that style - see `CustomizeTabs.CymaticsTab`
 * for why this one is gated as a tab rather than as a section.
 */
internal fun isCymaticsSceneId(sceneId: String): Boolean = VisualStyleCatalog.isCymatics(sceneId)

/**
 * Only HyperspaceScene reads the fractal-room params, so the whole Hyperspace
 * tab appears and disappears with that style - same rule as the Cymatics tab.
 */
internal fun isHyperspaceSceneId(sceneId: String): Boolean = VisualStyleCatalog.isHyperspace(sceneId)

/**
 * Styles that run the shared FluidParticles lifecycle layer, i.e. the ones
 * that read `fluidParticleDrag` and `fluidParticleLife` (set on consecutive
 * lines in both scenes). CURLFLOW *is* that layer (CurlFlowScene's
 * "particles.drag = params.fluidParticleDrag"), yet the drag slider used to
 * live in the FLUID-only Particles section AND behind `fluidParticlesEnabled`,
 * a param CurlFlow never reads - so a control the style genuinely consumes was
 * unreachable on it. WATER has no particle layer at all, which is why
 * "Particle life (s)" moved here from the WATER-inclusive Journey section.
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
 * uPal2Base, uPal2Range, declared by every scene fragment shader - and
 * the composite has no counterpart uniform for any of them. On every other
 * style those four sliders move nothing, so they are hidden there.
 */
internal fun isShaderLookSceneId(sceneId: String): Boolean = sceneId in VisualizerRenderer.SHADER_SCENES

/**
 * Styles that draw a particle sprite, i.e. the readers of BOTH `particleShape`
 * and `particleSize`. Two families, one look: the CPU styles in
 * [VisualizerRenderer.PARTICLE_SCENES] (`ParticleSceneBase.draw` uploads
 * `uShape` and `uSize`) and the GPU lifecycle layer the fluid styles run
 * (`FluidScene` folds `particleSize` into `pointScale` and passes
 * `particleShape` straight through to `FluidParticles.draw`, `CurlFlowScene`
 * likewise). The fluid half is exactly [isParticleLayerSceneId] - the same
 * FluidParticles layer that reads `fluidParticleDrag` - so this composes from
 * it rather than restating FLUID/CURLFLOW a third time; if the layer ever
 * gains or loses a style, everything moves together.
 *
 * Shape used to be a NARROWER gate than size, because the fluid layer had no
 * shape uniform and drew round dots only. Both families now shade through the
 * same `lib_particle_shade.glsl`, so the chip row is live wherever the slider is
 * and the two gates collapsed into this one.
 *
 * Note FLUID can switch its point layer off (`fluidParticlesEnabled`), which
 * makes these controls *temporarily* inert there. That is deliberately NOT
 * part of this predicate: gating is about what a style can read, and a control
 * the user can revive with one checkbox should not vanish from a different tab
 * with no visible cause. The Shape tab says so instead.
 */
internal fun isPointSpriteSceneId(sceneId: String): Boolean =
    sceneId in VisualizerRenderer.PARTICLE_SCENES || isParticleLayerSceneId(sceneId)

/**
 * The Customize panel: the scene-parameter tabs plus the tools that act on
 * them (Randomize unlocked, Reset). Mounted by the Visuals hub AND by the
 * Settings destination's Customize tab - one panel, two doors, so the two
 * can never drift apart.
 */
@Composable
internal fun CustomizePanel(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val viz by viewModel.vizState.collectAsState()
    var sub by rememberSaveable { mutableStateOf(0) }
    // Shader styles own the GLSL tab AND are the only readers of the
    // shader-only look params gated out of Shape/Color - one predicate, so
    // the two can never drift apart.
    val isShader = isShaderLookSceneId(viz.sceneId)
    val isCymatics = isCymaticsSceneId(viz.sceneId)
    val isHyperspace = isHyperspaceSceneId(viz.sceneId)
    // Tabs are dispatched by the CustomizeTab they carry, not by index: two of
    // them come and go with the active style, so positions do not identify a
    // panel. The parameter tabs come from the enum itself so the panel and the
    // randomizer can never disagree about what a tab is called or contains;
    // GLSL is appended as a null-tab entry because it edits shader source
    // rather than SceneParams.
    val tabs: List<CustomizeTab?> =
        CustomizeTab.entries.filter {
            when (it) {
                CustomizeTab.CYMATICS -> isCymatics
                CustomizeTab.HYPERSPACE -> isHyperspace
                else -> true
            }
        } + if (isShader) listOf(null) else emptyList()
    val titles = tabs.map { it?.title ?: "GLSL" }
    LaunchedEffect(tabs.size) { if (sub >= tabs.size) sub = 0 }
    Column(Modifier.fillMaxSize()) {
        CrystalTabs(titles = titles, selected = sub, onSelect = { sub = it })
        CustomizeToolbar(viewModel, viz.params, tabs.getOrNull(sub))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val locked by viewModel.lockedParams.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(
                LocalParamLocks provides (locked to viewModel::toggleParamLock),
            ) {
                val p = viz.params
                val onChange: (dev.musicviz.render.scene.SceneParams) -> Unit = { viewModel.setSceneParams(it) }
                val lfos by viewModel.lfos.collectAsState()
                when (tabs.getOrNull(sub)) {
                    CustomizeTab.MOTION -> MotionTab(p, onChange)
                    CustomizeTab.SHAPE ->
                        ShapeTab(
                            p,
                            onChange,
                            isShaderLookScene = isShader,
                            isPointSpriteScene = isPointSpriteSceneId(viz.sceneId),
                            particleLayerOff = isFluidSceneId(viz.sceneId) && !p.fluidParticlesEnabled,
                            isBeamScene = isBeamSceneId(viz.sceneId),
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
                        val artNote by viewModel.artPaletteNote.collectAsState()
                        ColorTab(
                            p,
                            onChange,
                            isShaderLookScene = isShader,
                            onTakeArtworkPalette = viewModel::applyArtworkPalette,
                            artworkNote = artNote,
                        )
                    }
                    CustomizeTab.FX -> {
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
                    CustomizeTab.FLUID ->
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
                    CustomizeTab.CYMATICS -> CymaticsTab(p, onChange)
                    CustomizeTab.HYPERSPACE -> HyperspaceTab(p, onChange)
                    // The GLSL tab: shader source, not scene parameters.
                    null -> GlslHubTab(viewModel, visualizerView)
                }
            }
        }
    }
}

/**
 * The tools that act on the parameters, above the controls they act on: roll
 * this tab's unlocked ones, and put everything back.
 *
 * Randomize is scoped to [tab] and says so on the button. It sits inside a
 * tab, so it acts on that tab: a roll that also moved the other tabs' sliders
 * threw away work the user had just done elsewhere, with no undo. On the GLSL
 * tab ([tab] null) there are no parameters to roll, so it is disabled rather
 * than silently rolling something off-screen.
 *
 * Reset is the deliberate whole-panel counterpart, and is confirmed rather
 * than immediate. It discards every slider in every tab at once, and the panel
 * it sits in exists for people who spend a long time moving those sliders; a
 * mis-tap next to Randomize would be expensive and there is no undo. The row
 * also reports how far the live look has drifted from the defaults, which is
 * the question "should I reset?" answered before it is asked.
 */
@Composable
private fun CustomizeToolbar(
    viewModel: PlayerViewModel,
    params: dev.musicviz.render.scene.SceneParams,
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
            // Decorative next to its own label: a description would make TalkBack name the action twice.
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

// ------------------------------------------------------------------ Takes

/** mm:ss for a take's clock. */
private fun formatTakeTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * Saved performance takes: what the visuals were DOING over time, rather than
 * the pixels that came out.
 *
 * Recording is started from the header (a set is performed on the Customize
 * and Styles tabs, so the control cannot live here); this tab is where takes
 * are replayed, renamed, chosen for export and deleted.
 */
@Composable
private fun TakesTab(viewModel: PlayerViewModel) {
    val takes by viewModel.takeState.collectAsState()
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    // Held at tab level, not per row, for the same LazyColumn-disposal reason
    // as the preset dialogs.
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
                    Icon(
                        if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        if (playing) "Stop replay" else "Replay this take",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = {
                    viewModel.setExportTake(if (takes.exportTake == take.name) null else take.name)
                }) {
                    Icon(
                        Icons.Filled.Favorite,
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
                }) { Icon(Icons.Filled.Edit, "Rename") }
                IconButton(onClick = { deleting = take.name }) {
                    Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
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
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename take") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            confirmButton = {
                CrystalButton(onClick = {
                    viewModel.renameTake(old, renameText.trim())
                    renaming = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { name ->
        // A take is a recorded performance: once its file is gone it cannot be
        // re-made the same way, so - like Reset and preset delete - it asks
        // first rather than acting on a tap that landed beside Rename.
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
        CrystalButton(onClick = { picker.launch(arrayOf("image/*")) }) { Text("Import images") }
        textures.forEach { tex ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tex.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                CrystalButton(compact = true, filled = false, onClick = {
                    viewModel.useTexture(tex.name) { path -> selectMilk(viewModel, visualizerView, path) }
                }) { Text("Use") }
            }
        }
        if (textures.isEmpty()) Text("No textures imported yet.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------------------------------------------------------- GLSL

/**
 * Saved instance state rides a Binder transaction capped around 1 MB for the
 * whole activity, so `rememberSaveable`-ing an arbitrarily large shader draft
 * risks TransactionTooLargeException on backgrounding. Drafts up to this many
 * chars are worth the budget; anything bigger [ShaderDraftSaver] drops from
 * saved state, and the editor re-seeds from the renderer's committed source.
 */
private const val MAX_SAVED_SHADER_DRAFT_CHARS = 8 * 1024

private val ShaderDraftSaver =
    Saver<String, String>(
        save = { draft -> draft.takeIf { it.length <= MAX_SAVED_SHADER_DRAFT_CHARS } },
        restore = { it },
    )

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
    // Saveable so an uncommitted draft survives rotation, but through
    // [ShaderDraftSaver] so a large source cannot blow the Binder budget.
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
