package dev.musicviz.ui

import android.net.Uri
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.musicviz.analysis.SearchMatcher
import dev.musicviz.render.VisualizerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Crash reports are for copy-pasting into a bug report; 64 KB is plenty. */
private const val CRASH_REPORT_MAX_BYTES = 64 * 1024

/**
 * Navigation-v2 app shell: bottom nav (Player / Library / Visuals / Studio /
 * Settings)
 * with a mini-player docked above it on every tab except the Player tab
 * (which IS the player), and the fullscreen visualizer (Now Playing) as an
 * overlay expanded from the mini-player.
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
    // Second screen. The canvas is MOVED to the external display rather than
    // duplicated, so the screens below must not try to host it at the same
    // time - a View has one parent.
    val externalDisplay = rememberExternalDisplay()
    val onSecondScreen = gui.secondScreen && externalDisplay != null
    if (onSecondScreen) {
        SecondScreenCanvas(externalDisplay, visualizerView)
    }

    // Crash-report read happens off the main thread — first composition is
    // on the startup path — and is capped so a runaway report can't balloon
    // memory. The dialog simply appears a beat later.
    var crashText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crashText =
            withContext(Dispatchers.IO) {
                val file = java.io.File(context.filesDir, "crash-latest.txt")
                if (file.exists()) {
                    file.inputStream().use { stream ->
                        val buf = ByteArray(CRASH_REPORT_MAX_BYTES)
                        var read = 0
                        while (read < buf.size) {
                            val n = stream.read(buf, read, buf.size - read)
                            if (n < 0) break
                            read += n
                        }
                        String(buf, 0, read, Charsets.UTF_8)
                    }
                } else {
                    null
                }
            }
    }
    VisualizerEngineBindings(viewModel, visualizerView)
    // System back: other tabs return to the Player tab (dest 0) before the
    // app exits. Composed FIRST so handlers composed later (library drill-in,
    // search overlay, expanded visualizer) take priority - Compose gives the
    // back event to the last-composed enabled handler, unwinding overlays in
    // the right order: visualizer > search > drill-in > tab > exit.
    androidx.activity.compose.BackHandler(enabled = dest != 0) { dest = 0 }
    CrystalMaterialTheme(
        appTheme = effectiveTheme,
        gui = gui,
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
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
            )
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Nebula + star-field backdrop behind every shell tab; the
            // Scaffold goes transparent so the glow shows through the glass.
            // Transparent has no `contentColorFor` role, so the writing colour
            // it derives is whatever LocalContentColor holds - Material's own
            // default for that is BLACK, and [CrystalMaterialTheme] is what
            // provides the theme's onBackground in its place. Setting it here
            // as well would be a second copy of the same decision.
            CrystalBackground(Modifier.fillMaxSize(), reducedMotion = gui.reducedMotion)
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    // hasMedia guard: an empty statusBarsPadding box would
                    // still reserve inset height with nothing playing.
                    // dest 0 guard: the Player tab IS the player, so the
                    // mini-player only docks on the other tabs.
                    if (gui.playerPosition == PlayerPosition.TOP && state.hasMedia && dest != 0) {
                        Box(Modifier.statusBarsPadding()) { miniPlayer() }
                    }
                },
                bottomBar = {
                    Column {
                        if (gui.playerPosition == PlayerPosition.BOTTOM && dest != 0) miniPlayer()
                        // Luminous accent hairline along the top edge of the
                        // nav glass, per the mockups' "stroked" bottom nav.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .luminousHairline(MaterialTheme.colorScheme.primary),
                        )
                        CrystalNavBar(
                            items =
                                listOf(
                                    CrystalNavItem("Player", Icons.Filled.PlayCircle),
                                    CrystalNavItem("Library", Icons.Filled.LibraryMusic),
                                    CrystalNavItem("Visuals", Icons.Filled.MusicNote),
                                    CrystalNavItem("Studio", Icons.Filled.Movie),
                                    CrystalNavItem("Settings", Icons.Filled.Settings),
                                ),
                            selected = dest,
                            onSelect = { dest = it },
                            opacity = gui.barOpacity,
                        )
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad)) {
                    when (dest) {
                        0 ->
                            PlayerScreen(
                                viewModel,
                                onOpenSearch = { searching = true },
                                onExpand = { expanded = true },
                                onOpenLibrary = { dest = 1 },
                            )
                        1 -> LibraryScreen(viewModel, onPersistUri, onOpenSearch = { searching = true })
                        2 ->
                            VisualsHub(
                                viewModel,
                                visualizerView,
                                onOpenNowPlaying = { expanded = true },
                                // The single GL view can't live in two parents:
                                // only host it here while Now Playing is closed
                                // and it has not been sent to a second screen.
                                liveBackdrop = gui.clearVisualsMenu && !expanded && !onSecondScreen,
                            )
                        3 -> StudioScreen(viewModel)
                        4 -> SettingsScreen(viewModel, visualizerView)
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
                        CrystalButton(onClick = {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("MusicViz crash", text))
                        }) { Text("Copy") }
                    },
                    dismissButton = {
                        CrystalButton(filled = false, onClick = {
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
                    externalDisplayName = if (onSecondScreen) externalDisplay?.name else null,
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
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    if (!hasMedia) return
    Column(
        Modifier
            .fillMaxWidth()
            .crystalPanel(
                barOpacity,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 0.dp,
                glowStrength = 0.6f,
                facets = 0.8f,
            )
            .clickable(onClick = onExpand),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 0.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.MusicNote,
                null,
                Modifier
                    .size(if (compact) 20.dp else 28.dp)
                    .softGlow(MaterialTheme.colorScheme.primary, 8.dp, if (isPlaying) 1f else 0.35f),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                title ?: "Now playing",
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            // Previous sits next to Next here too: with the queue now built
            // from the list a track was played from, both directions mean
            // something from the mini-player, not only in Now Playing.
            IconButton(onClick = onPrevious) { Icon(Icons.Filled.SkipPrevious, "Previous") }
            IconButton(onClick = onPlayPause) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause")
            }
            IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, "Next") }
        }
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        )
    }
}

/**
 * Settings as a nav destination: the header, then [AppSettingsTab]'s
 * category tabs (Look / Audio / Export / Folders / Behavior / About).
 *
 * The old second "Customize" tab is gone: scene parameters belong to the
 * Visuals hub, which already mounts the same [CustomizePanel] as its own
 * tab - one panel, one door, no copy that can drift.
 *
 * Export renders through the export dialog ([ExportHost]), opened from the
 * Export tab; its standing defaults live there too.
 */
@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    var showExport by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            CrystalOverline("MusicViz")
            GlowTitle("Settings")
        }
        AppSettingsTab(viewModel, exportOpen = showExport, onOpenExport = { showExport = true })
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
    val gui by viewModel.guiPrefs.collectAsState()
    val library by viewModel.library.collectAsState()
    val viz by viewModel.vizState.collectAsState()
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

    // Search floats over a full content screen: it gets its own opaque
    // nebula backdrop so results always read, and the field itself is cut
    // in the shard silhouette.
    Box(Modifier.fillMaxSize()) {
        CrystalBackground(Modifier.fillMaxSize(), reducedMotion = gui.reducedMotion)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search tracks, playlists & presets") },
                    singleLine = true,
                    shape = crystalShardShape(14.dp, 5.dp),
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
                        item { CrystalOverline("Tracks (${trackResults.size})", Modifier.padding(top = 8.dp)) }
                        items(trackResults, key = { "t:${it.uri}" }) { t ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // The result list is the queue, so Next
                                        // walks the search hits.
                                        viewModel.playFrom(
                                            trackResults.map { r -> QueueTrack(r.uri, r.title, r.subtitle) },
                                            t.uri,
                                        )
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
                        item { CrystalOverline("Playlists (${playlistResults.size})", Modifier.padding(top = 8.dp)) }
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
                        item { CrystalOverline("Presets (${presetResults.size})", Modifier.padding(top = 8.dp)) }
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
