package dev.musicviz.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.material3.Switch
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
import dev.musicviz.analysis.SearchMatcher
import dev.musicviz.render.VisualizerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val gui by viewModel.guiPrefs.collectAsState()
    val systemDark = isSystemInDarkTheme()
    // Follow-system-dark: when the OS is in light mode, swap to the LIGHT
    // theme; otherwise keep the user's picked theme.
    val effectiveTheme = if (gui.followSystemDark && !systemDark) AppTheme.LIGHT else appTheme
    var dest by rememberSaveable { mutableStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    // rememberSaveable: rotation/config changes must not replay the intro;
    // only a fresh process start does.
    var bootDone by rememberSaveable { mutableStateOf(false) }
    val bootAnimEnabled =
        remember {
            context
                .getSharedPreferences("musicviz-prefs", android.content.Context.MODE_PRIVATE)
                .getBoolean("boot_anim", true)
        }
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
    MaterialTheme(
        colorScheme = effectiveTheme.colorScheme(gui.accentIntensity, gui.backgroundDim),
        shapes = gui.cornerStyle.shapes(),
    ) {
        val miniPlayer: @Composable () -> Unit = {
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
                compact = gui.compactPlayer,
                barOpacity = gui.barOpacity,
                onExpand = { expanded = true },
                onPlayPause = viewModel::togglePlayPause,
                onNext = viewModel::next,
            )
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Scaffold(
                topBar = {
                    // hasMedia guard: an empty statusBarsPadding box would
                    // still reserve inset height with nothing playing.
                    if (gui.playerPosition == PlayerPosition.TOP && state.hasMedia) {
                        Box(Modifier.statusBarsPadding()) { miniPlayer() }
                    }
                },
                bottomBar = {
                    Column {
                        if (gui.playerPosition == PlayerPosition.BOTTOM) miniPlayer()
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = gui.barOpacity),
                        ) {
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
            // Last overlay in the Box so it draws above everything else.
            if (bootAnimEnabled && !bootDone) {
                BootIntro(onDone = { bootDone = true })
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
    barOpacity: Float,
    compact: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    if (!hasMedia) return
    Column(
        Modifier
            .fillMaxWidth()
            .glassPanel(barOpacity, MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onExpand),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.MusicNote,
                null,
                Modifier.size(if (compact) 20.dp else 28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
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

/**
 * A collapsible settings group: header row with title and chevron toggles
 * the section body; expanded state is remembered per section.
 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                if (expanded) "Collapse" else "Expand",
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
        HorizontalDivider(Modifier.padding(top = 10.dp))
    }
}

/** Settings as a nav destination; export lives in the export dialog. */
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
        item {
            SettingsSection("Appearance") {
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
                Column {
                    Text("Bar opacity  ${(gui.barOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = gui.barOpacity,
                        onValueChange = { viewModel.setGuiPrefs(gui.copy(barOpacity = it)) },
                        valueRange = 0.2f..1f,
                    )
                }
                Column {
                    Text("Player position", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayerPosition.entries.forEach { pos ->
                            OutlinedButton(onClick = { viewModel.setGuiPrefs(gui.copy(playerPosition = pos)) }) {
                                Text((if (gui.playerPosition == pos) "● " else "") + pos.name.lowercase())
                            }
                        }
                    }
                }
                Column {
                    Text("Corner style", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CornerStyle.entries.forEach { c ->
                            OutlinedButton(onClick = { viewModel.setGuiPrefs(gui.copy(cornerStyle = c)) }) {
                                Text((if (gui.cornerStyle == c) "● " else "") + c.name.lowercase())
                            }
                        }
                    }
                }
                Text("Accent intensity  ${(gui.accentIntensity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = gui.accentIntensity,
                    onValueChange = { viewModel.setGuiPrefs(gui.copy(accentIntensity = it)) },
                    valueRange = 0.5f..1.5f,
                )
                Text("Background dim  ${(gui.backgroundDim * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = gui.backgroundDim,
                    onValueChange = { viewModel.setGuiPrefs(gui.copy(backgroundDim = it)) },
                    valueRange = 0f..0.6f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Follow system light/dark", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = gui.followSystemDark,
                        onCheckedChange = { viewModel.setGuiPrefs(gui.copy(followSystemDark = it)) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Compact mini-player", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = gui.compactPlayer,
                        onCheckedChange = { viewModel.setGuiPrefs(gui.copy(compactPlayer = it)) },
                    )
                }
                // Boot animation toggle (persisted directly; read by AppRoot at start).
                val bootCtx = LocalContext.current
                val bootPrefs = remember { bootCtx.getSharedPreferences("musicviz-prefs", android.content.Context.MODE_PRIVATE) }
                var bootAnim by remember { mutableStateOf(bootPrefs.getBoolean("boot_anim", true)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Boot animation", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = bootAnim, onCheckedChange = {
                        bootAnim = it
                        bootPrefs.edit().putBoolean("boot_anim", it).apply()
                    })
                }
            }
        }
        item {
            SettingsSection("Playback") {
                PlaybackSettingsSection(viewModel)
                EqualizerSettings(viewModel)
            }
        }
        item {
            SettingsSection("Library") {
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
                Column {
                    Text(
                        if (gui.presetMirrorUri != null) {
                            "Preset folder: chosen — saves are mirrored there"
                        } else {
                            "Preset folder: internal only"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { folderPicker.launch(null) }) { Text("Choose preset folder") }
                        if (gui.presetMirrorUri != null) {
                            TextButton(onClick = { viewModel.setGuiPrefs(gui.copy(presetMirrorUri = null)) }) { Text("Clear") }
                        }
                    }
                }
                Text(
                    "Music folders are managed in Library › Folders.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            SettingsSection("Visuals & Analysis") {
                Column {
                    Text("Preset morph: ${gui.morphBeats} beats (0 = snap)")
                    Slider(
                        value = gui.morphBeats.toFloat(),
                        onValueChange = { viewModel.setGuiPrefs(gui.copy(morphBeats = it.toInt())) },
                        valueRange = 0f..16f,
                        steps = 15,
                    )
                }
                Column {
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
            }
        }
        item {
            SettingsSection("Export & About") {
                Button(onClick = { showExport = true }) { Text("Export video…") }
                Column {
                    // TODO(coordinator): switch to BuildConfig.VERSION_NAME once
                    // buildFeatures.buildConfig is enabled — BuildConfig is not
                    // generated or referenced anywhere in the app today.
                    Text("MusicViz", style = MaterialTheme.typography.titleSmall)
                    Text("Version 0.13.0", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (showExport) {
        ExportHost(viewModel, visualizerView) { showExport = false }
    }
}

/** One merged track result row (device index or imported library). */
private data class SearchTrackRow(
    val uri: String,
    val title: String,
    val subtitle: String,
    val fields: List<String>,
    val fromDevice: Boolean,
)

@Composable
fun SearchScreen(
    viewModel: PlayerViewModel,
    onClose: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var debounced by rememberSaveable { mutableStateOf("") }
    val library by viewModel.library.collectAsState()
    val viz by viewModel.vizState.collectAsState()
    val gui by viewModel.guiPrefs.collectAsState()
    val deviceTracks by viewModel.deviceTracks.collectAsState()
    // The device index may be empty when search opens before the Library tab
    // has loaded it; refresh is a no-op without the audio permission.
    LaunchedEffect(Unit) { viewModel.refreshDeviceTracks() }
    // 250 ms debounce: filtering runs once per typing pause, not per
    // keystroke. Clearing the field takes effect immediately.
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(250)
        debounced = query
    }
    // Back closes the search overlay instead of exiting the app.
    androidx.activity.compose.BackHandler { onClose() }

    val terms = remember(debounced) { SearchMatcher.terms(debounced) }
    val trackResults =
        remember(terms, deviceTracks, library.tracks) {
            val candidates =
                deviceTracks.map { t ->
                    SearchTrackRow(
                        uri = t.uri,
                        title = t.title,
                        subtitle = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                        fields = listOf(t.title, t.artist, t.album, t.folder),
                        fromDevice = true,
                    )
                } +
                    library.tracks.map { t ->
                        SearchTrackRow(
                            uri = t.uri,
                            title = t.title,
                            subtitle = listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                            fields = listOf(t.title, t.artist, t.album, t.genre),
                            fromDevice = false,
                        )
                    }
            SearchMatcher.filterTracks(
                terms = terms,
                items = candidates,
                uriOf = { it.uri },
                fieldsOf = { it.fields },
                preferred = { it.fromDevice },
            )
        }
    val playlistResults =
        remember(terms, library.playlists) {
            library.playlists.filter { SearchMatcher.matches(terms, listOf(it.name)) }
        }
    val presetResults =
        remember(terms, viz.presets) {
            viz.presets.filter { SearchMatcher.matches(terms, listOf(it.name)) }
        }

    // Search floats over a full content screen, so it needs more glass than
    // the bars: clamp to >= 0.85 opacity or result text becomes unreadable
    // against whatever is behind the overlay.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = maxOf(gui.barOpacity, 0.85f)))) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search tracks, playlists & presets") },
                    singleLine = true,
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close search") }
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (terms.isEmpty()) {
                    item {
                        Text(
                            "Type to search your music",
                            Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    if (trackResults.isNotEmpty()) {
                        item { Text("Tracks (${trackResults.size})", style = MaterialTheme.typography.titleMedium) }
                        items(trackResults, key = { "t:${it.uri}" }) { t ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.playTrack(t.uri)
                                        onClose()
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (t.subtitle.isNotBlank()) {
                                        Text(
                                            t.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(onClick = { viewModel.enqueue(t.uri) }) {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue")
                                }
                            }
                        }
                    }
                    if (playlistResults.isNotEmpty()) {
                        item { Text("Playlists (${playlistResults.size})", style = MaterialTheme.typography.titleMedium) }
                        items(playlistResults) { pl ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.playPlaylist(pl.name)
                                        onClose()
                                    }.padding(vertical = 8.dp),
                            ) {
                                Text(pl.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${pl.trackUris.size} tracks",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (presetResults.isNotEmpty()) {
                        item { Text("Presets (${presetResults.size})", style = MaterialTheme.typography.titleMedium) }
                        items(presetResults) { p ->
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
                    if (trackResults.isEmpty() && playlistResults.isEmpty() && presetResults.isEmpty()) {
                        item {
                            Text(
                                "No results for “$debounced”",
                                Modifier.padding(vertical = 16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
