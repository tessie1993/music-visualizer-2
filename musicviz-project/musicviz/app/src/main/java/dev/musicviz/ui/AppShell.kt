package dev.musicviz.ui

import android.net.Uri
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.musicviz.render.VisualizerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Navigation-v2 app shell: bottom nav (Home / Library / Visuals / Settings)
 * with a persistent mini-player docked above it, and the fullscreen
 * visualizer (Now Playing) as an overlay expanded from the mini-player.
 * The single VisualizerView is owned here so renderer state (custom shaders,
 * milk preset, params) survives collapse/expand; on re-expand the EGL restore
 * path rebuilds GL state.
 */
@Composable
fun AppRoot(
    viewModel: PlayerViewModel,
    onPersistUri: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val visualizerView = remember { VisualizerView(context) }
    val appTheme by viewModel.theme.collectAsState()
    var dest by rememberSaveable { mutableStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.uiState.collectAsState()

    var crashText by remember {
        mutableStateOf(
            java.io
                .File(context.filesDir, "crash-latest.txt")
                .takeIf { it.exists() }
                ?.readText(),
        )
    }
    VisualizerEngineBindings(viewModel, visualizerView)
    // System back: non-Home tabs return Home before the app exits. Composed
    // FIRST so handlers composed later (library drill-in, search overlay,
    // expanded visualizer) take priority - Compose gives the back event to
    // the last-composed enabled handler, unwinding overlays in the right
    // order: visualizer > search > drill-in > tab > exit.
    androidx.activity.compose.BackHandler(enabled = dest != 0) { dest = 0 }
    MaterialTheme(colorScheme = appTheme.colorScheme()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Scaffold(
                bottomBar = {
                    Column {
                        MiniPlayer(
                            title =
                                listOfNotNull(
                                    state.title,
                                    state.artist?.takeIf { it.isNotBlank() },
                                ).joinToString(" \u2014 ").ifBlank { null },
                            isPlaying = state.isPlaying,
                            hasMedia = state.hasMedia,
                            progress =
                                if (state.durationMs > 0) {
                                    state.positionMs / state.durationMs.toFloat()
                                } else {
                                    0f
                                },
                            onExpand = { expanded = true },
                            onPlayPause = viewModel::togglePlayPause,
                            onNext = viewModel::next,
                        )
                        NavigationBar {
                            NavigationBarItem(
                                selected = dest == 0,
                                onClick = { dest = 0 },
                                icon = { Icon(Icons.Filled.Home, "Home") },
                                label = { Text("Home") },
                            )
                            NavigationBarItem(
                                selected = dest == 1,
                                onClick = { dest = 1 },
                                icon = { Icon(Icons.Filled.LibraryMusic, "Library") },
                                label = { Text("Library") },
                            )
                            NavigationBarItem(
                                selected = dest == 2,
                                onClick = { dest = 2 },
                                icon = { Icon(Icons.Filled.MusicNote, "Visuals") },
                                label = { Text("Visuals") },
                            )
                            NavigationBarItem(
                                selected = dest == 3,
                                onClick = { dest = 3 },
                                icon = { Icon(Icons.Filled.Settings, "Settings") },
                                label = { Text("Settings") },
                            )
                        }
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad)) {
                    when (dest) {
                        0 -> HomeScreen(viewModel, onOpenSearch = { searching = true }, onExpand = { expanded = true })
                        1 -> LibraryScreen(viewModel, onPersistUri, onOpenSearch = { searching = true })
                        2 -> VisualsHub(viewModel, visualizerView, onOpenNowPlaying = { expanded = true })
                        3 -> SettingsScreen(viewModel, visualizerView)
                    }
                }
            }
            if (searching) {
                SearchScreen(viewModel, onClose = { searching = false })
            }
            crashText?.let { text ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Previous crash captured") },
                    text = {
                        Text(
                            "The last run crashed. Copy the report to share it, then dismiss.\n\n" +
                                text.take(600),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("MusicViz crash", text))
                        }) { Text("Copy") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = {
                            java.io.File(context.filesDir, "crash-latest.txt").delete()
                            crashText = null
                        }) { Text("Dismiss") }
                    },
                )
            }
            if (expanded) {
                VisualizerScreen(
                    viewModel = viewModel,
                    visualizerView = visualizerView,
                    onCollapse = { expanded = false },
                    onOpenVisuals = {
                        expanded = false
                        dest = 2
                    },
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    title: String?,
    isPlaying: Boolean,
    hasMedia: Boolean,
    progress: Float,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    if (!hasMedia) return
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onExpand),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.MusicNote, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                title ?: "Now playing",
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onPlayPause) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause")
            }
            IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, "Next") }
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
        )
    }
}

@Composable
fun HomeScreen(
    viewModel: PlayerViewModel,
    onOpenSearch: () -> Unit,
    onExpand: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val tick by viewModel.historyTick.collectAsState()
    val recent = remember(tick) { viewModel.recentlyPlayed() }
    val most = remember(tick) { viewModel.mostPlayed() }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Home", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, "Search") }
            }
        }
        if (state.hasMedia) {
            item {
                Card(Modifier.fillMaxWidth().clickable(onClick = onExpand)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("Resume", style = MaterialTheme.typography.labelMedium)
                            Text(state.title ?: "Current queue", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::shuffleAllHistory) { Text("Shuffle all") }
            }
        }
        if (recent.isNotEmpty()) {
            item { Text("Recently played", style = MaterialTheme.typography.titleMedium) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent) { e -> HistoryChip(e.title) { viewModel.playTrack(e.uri) } }
                }
            }
        }
        if (most.isNotEmpty()) {
            item { Text("Most played", style = MaterialTheme.typography.titleMedium) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(most) { e -> HistoryChip("${e.title} (${e.playCount})") { viewModel.playTrack(e.uri) } }
                }
            }
        }
        if (recent.isEmpty()) {
            item { Text("Play something from the Library to see history here.", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun HistoryChip(
    label: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick) {
        Text(
            label,
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** Settings as a nav destination; export lives in the existing dialog. */
@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    val gui by viewModel.guiPrefs.collectAsState()
    val appTheme by viewModel.theme.collectAsState()
    var showExport by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Look", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AppTheme.entries.toList()) { t ->
                    val sel = t == appTheme
                    Card(onClick = { viewModel.setTheme(t) }) {
                        Text(
                            (if (sel) "● " else "") + t.label,
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            Text("Bar opacity  ${(gui.barOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(value = gui.barOpacity, onValueChange = { viewModel.setGuiPrefs(gui.copy(barOpacity = it)) }, valueRange = 0.2f..1f)
        }
        item { HorizontalDivider() }
        item { Text("Player", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerPosition.entries.forEach { pos ->
                    OutlinedButton(onClick = { viewModel.setGuiPrefs(gui.copy(playerPosition = pos)) }) {
                        Text((if (gui.playerPosition == pos) "● " else "") + pos.name.lowercase())
                    }
                }
            }
        }
        item {
            Text("Corner style", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CornerStyle.entries.forEach { c ->
                    OutlinedButton(onClick = { viewModel.setGuiPrefs(gui.copy(cornerStyle = c)) }) {
                        Text((if (gui.cornerStyle == c) "● " else "") + c.name.lowercase())
                    }
                }
            }
        }
        item { PlaybackSettingsSection(viewModel) }
        item { HorizontalDivider() }
        item { Text("Paths", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        item {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val folderPicker =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) {
                        ctx.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                        viewModel.setGuiPrefs(gui.copy(presetMirrorUri = uri.toString()))
                    }
                }
            Text(
                if (gui.presetMirrorUri != null) "Preset folder: chosen — saves are mirrored there" else "Preset folder: internal only",
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose preset folder") }
                if (gui.presetMirrorUri != null) {
                    TextButton(onClick = { viewModel.setGuiPrefs(gui.copy(presetMirrorUri = null)) }) { Text("Clear") }
                }
            }
        }
        item { HorizontalDivider() }
        item { Text("Analysis", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            var cacheInfo by remember { mutableStateOf("…") }
            var cacheBump by remember { mutableIntStateOf(0) }
            LaunchedEffect(cacheBump) {
                cacheInfo =
                    withContext(Dispatchers.IO) {
                        val app = context.applicationContext
                        val n =
                            dev.musicviz.analysis.AnalysisCache
                                .entryCount(app)
                        val mb =
                            dev.musicviz.analysis.AnalysisCache
                                .sizeBytes(app) / (1024f * 1024f)
                        "%d tracks · %.1f MB".format(n, mb)
                    }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Analysis cache: $cacheInfo", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    dev.musicviz.analysis.AnalysisCache
                        .clear(context.applicationContext)
                    cacheBump++
                }) { Text("Clear") }
            }
            Text("Preset morph: ${gui.morphBeats} beats (0 = snap)")
            Slider(
                value = gui.morphBeats.toFloat(),
                onValueChange = { viewModel.setGuiPrefs(gui.copy(morphBeats = it.toInt())) },
                valueRange = 0f..16f,
                steps = 15,
            )
            Text(
                "Beat threshold  ${"%.1f".format(gui.beatThresholdSigma)}σ (higher = fewer beat flashes)",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = gui.beatThresholdSigma,
                onValueChange = { viewModel.setGuiPrefs(gui.copy(beatThresholdSigma = it)) },
                valueRange = 1.5f..4f,
            )
        }
        item { HorizontalDivider() }
        item {
            Button(onClick = { showExport = true }) { Text("Export video…") }
        }
    }
    if (showExport) {
        ExportHost(viewModel, visualizerView) { showExport = false }
    }
}

@Composable
fun SearchScreen(
    viewModel: PlayerViewModel,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val library by viewModel.library.collectAsState()
    val viz by viewModel.vizState.collectAsState()
    // Back closes the search overlay instead of exiting the app.
    androidx.activity.compose.BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search tracks & presets") },
                    singleLine = true,
                )
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Close") }
            }
            val q = query.trim().lowercase()
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (q.isNotEmpty()) {
                    val tracks = library.tracks.filter { it.title.lowercase().contains(q) }.take(20)
                    if (tracks.isNotEmpty()) {
                        item { Text("Tracks", style = MaterialTheme.typography.titleMedium) }
                        items(tracks) { t ->
                            Text(
                                t.title,
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.playTrack(t.uri)
                                        onClose()
                                    }.padding(vertical = 8.dp),
                            )
                        }
                    }
                    val presets = viz.presets.filter { it.name.lowercase().contains(q) }.take(20)
                    if (presets.isNotEmpty()) {
                        item { Text("Visual presets", style = MaterialTheme.typography.titleMedium) }
                        items(presets) { p ->
                            Text(
                                p.name,
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.applyPreset(p)
                                        onClose()
                                    }.padding(vertical = 8.dp),
                            )
                        }
                    }
                    if (tracks.isEmpty() && presets.isEmpty()) {
                        item { Text("No results") }
                    }
                }
            }
        }
    }
}
