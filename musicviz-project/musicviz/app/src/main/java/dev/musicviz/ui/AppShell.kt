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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.musicviz.analysis.FeatureExtractor
import dev.musicviz.render.VisualizerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
        colorScheme = effectiveTheme.colorScheme(gui.accentIntensity, gui.backgroundDim, gui.whiteFont),
        shapes = gui.cornerStyle.shapes(),
        typography = crystalTypography(),
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
            // Nebula + star-field backdrop behind every shell tab; the
            // Scaffold goes transparent so the glow shows through the glass.
            CrystalBackground(Modifier.fillMaxSize())
            Scaffold(
                containerColor = Color.Transparent,
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
                                    CrystalNavItem("Home", Icons.Filled.Home),
                                    CrystalNavItem("Library", Icons.Filled.LibraryMusic),
                                    CrystalNavItem("Visuals", Icons.Filled.MusicNote),
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
                        0 -> HomeScreen(viewModel, onOpenSearch = { searching = true }, onExpand = { expanded = true })
                        1 -> LibraryScreen(viewModel, onPersistUri, onOpenSearch = { searching = true })
                        2 ->
                            VisualsHub(
                                viewModel,
                                visualizerView,
                                onOpenNowPlaying = { expanded = true },
                                // The single GL view can't live in two parents:
                                // only host it here while Now Playing is closed.
                                liveBackdrop = gui.clearVisualsMenu && !expanded,
                            )
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
            .glassPanel(barOpacity, MaterialTheme.colorScheme.surfaceVariant, glow = MaterialTheme.colorScheme.primary)
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
                Column(Modifier.weight(1f)) {
                    CrystalOverline("MusicViz")
                    GlowTitle("Home")
                }
                IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, "Search") }
            }
        }
        if (state.hasMedia) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .crystalPanel(
                            0.4f,
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.primary,
                            corner = 20.dp,
                        ).clickable(onClick = onExpand)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        null,
                        Modifier.softGlow(MaterialTheme.colorScheme.primary, 12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.padding(start = 10.dp)) {
                        CrystalOverline("Resume")
                        Text(state.title ?: "Current queue", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrystalButton(onClick = viewModel::shuffleAllHistory) { Text("Shuffle all") }
            }
        }
        if (recent.isNotEmpty()) {
            item { CrystalOverline("Recently played", Modifier.padding(top = 6.dp)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent) { e -> HistoryChip(e.title) { viewModel.playTrack(e.uri) } }
                }
            }
        }
        if (most.isNotEmpty()) {
            item { CrystalOverline("Most played", Modifier.padding(top = 6.dp)) }
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
    Box(
        Modifier
            .crystalPanel(
                0.3f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 24.dp,
                glowStrength = 0.6f,
            ).clickable(onClick = onClick),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
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
            CrystalGem(MaterialTheme.colorScheme.primary, size = 6.dp, glow = expanded)
            Text(
                title.uppercase(),
                Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.2.sp),
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(1.dp)
                .luminousHairline(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        )
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
        item {
            Column {
                CrystalOverline("MusicViz")
                GlowTitle("Settings")
            }
        }
        item {
            SettingsSection("Appearance") {
                CrystalOverline("Theme", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AppTheme.entries.toList()) { t ->
                        val sel = t == appTheme
                        Box(
                            Modifier
                                .crystalPanel(
                                    if (sel) 0.55f else 0.25f,
                                    if (sel) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    MaterialTheme.colorScheme.primary,
                                    corner = 20.dp,
                                    glowStrength = if (sel) 1.2f else 0.4f,
                                    prismatic = sel,
                                    sheen = MaterialTheme.colorScheme.secondary,
                                ).clickable { viewModel.setTheme(t) },
                        ) {
                            Text(
                                t.label,
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (sel) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }
                    }
                }
                Column {
                    Text("Bar opacity  ${(gui.barOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    CrystalSlider(
                        value = gui.barOpacity,
                        onValueChange = { viewModel.setGuiPrefs(gui.copy(barOpacity = it)) },
                        valueRange = 0.2f..1f,
                    )
                }
                Column {
                    Text("Player position", style = MaterialTheme.typography.labelMedium)
                    CrystalSegmented(
                        options = PlayerPosition.entries.map { it.label },
                        selected = PlayerPosition.entries.indexOf(gui.playerPosition),
                        onSelect = { viewModel.setGuiPrefs(gui.copy(playerPosition = PlayerPosition.entries[it])) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column {
                    Text("Corner style", style = MaterialTheme.typography.labelMedium)
                    CrystalSegmented(
                        options = CornerStyle.entries.map { it.label },
                        selected = CornerStyle.entries.indexOf(gui.cornerStyle),
                        onSelect = { viewModel.setGuiPrefs(gui.copy(cornerStyle = CornerStyle.entries[it])) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text("Accent intensity  ${(gui.accentIntensity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                CrystalSlider(
                    value = gui.accentIntensity,
                    onValueChange = { viewModel.setGuiPrefs(gui.copy(accentIntensity = it)) },
                    valueRange = 0.5f..1.5f,
                )
                Text("Background dim  ${(gui.backgroundDim * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                CrystalSlider(
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
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("White font", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = gui.whiteFont,
                            onCheckedChange = { viewModel.setGuiPrefs(gui.copy(whiteFont = it)) },
                        )
                    }
                    Text(
                        "Forces labels and body text to pure white. No effect on light themes, where it would be unreadable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Compact mini-player", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = gui.compactPlayer,
                        onCheckedChange = { viewModel.setGuiPrefs(gui.copy(compactPlayer = it)) },
                    )
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Clear-overlay Visuals menu", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = gui.clearVisualsMenu,
                            onCheckedChange = { viewModel.setGuiPrefs(gui.copy(clearVisualsMenu = it)) },
                        )
                    }
                    Text(
                        "Text-only Visuals menu over the live visuals, so adjustments are visible as you make them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        CrystalButton(filled = false, onClick = { folderPicker.launch(null) }) { Text("Choose preset folder") }
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
                    CrystalSlider(
                        value = gui.morphBeats.toFloat(),
                        onValueChange = { viewModel.setGuiPrefs(gui.copy(morphBeats = it.toInt())) },
                        valueRange = 0f..16f,
                        steps = 15,
                    )
                }
                Column {
                    Text(
                        "Beat sensitivity  ${"%.1f".format(gui.beatThresholdSigma)}σ " +
                            "— drag right for LESS sensitive (fewer beat flashes)",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    // Range comes from the extractor so the slider can never
                    // saturate against a tighter clamp in AnalysisEngine.
                    CrystalSlider(
                        value = gui.beatThresholdSigma,
                        onValueChange = { viewModel.setGuiPrefs(gui.copy(beatThresholdSigma = it)) },
                        valueRange = FeatureExtractor.SIGMA_MIN..FeatureExtractor.SIGMA_MAX,
                    )
                    Text(
                        "Minimum gap between beats  ${gui.beatMinIntervalMs.roundToInt()} ms " +
                            "— never flash faster than ${(60_000f / gui.beatMinIntervalMs).roundToInt()} BPM",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    CrystalSlider(
                        value = gui.beatMinIntervalMs,
                        onValueChange = { viewModel.setGuiPrefs(gui.copy(beatMinIntervalMs = it)) },
                        valueRange = FeatureExtractor.INTERVAL_MS_MIN..FeatureExtractor.INTERVAL_MS_MAX,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CrystalButton(
                            filled = false,
                            onClick = {
                                viewModel.setGuiPrefs(
                                    gui.copy(
                                        beatThresholdSigma = FeatureExtractor.SLOW_SIGMA,
                                        beatMinIntervalMs = FeatureExtractor.SLOW_INTERVAL_MS,
                                    ),
                                )
                            },
                        ) { Text("Slow track") }
                        TextButton(
                            onClick = {
                                viewModel.setGuiPrefs(
                                    gui.copy(
                                        beatThresholdSigma = FeatureExtractor.SIGMA_DEFAULT,
                                        beatMinIntervalMs = FeatureExtractor.INTERVAL_MS_DEFAULT,
                                    ),
                                )
                            },
                        ) { Text("Default") }
                    }
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
                CrystalButton(onClick = { showExport = true }) { Text("Export video…") }
                AboutSection()
            }
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
    var debounced by rememberSaveable { mutableStateOf("") }
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

    val results =
        remember(debounced, deviceTracks, library.tracks, library.playlists, viz.presets) {
            SearchModel.search(
                query = debounced,
                deviceTracks = deviceTracks,
                libraryTracks = library.tracks,
                playlists = library.playlists,
                presets = viz.presets,
            )
        }
    val trackResults = results.tracks
    val playlistResults = results.playlists
    val presetResults = results.presets

    // Search floats over a full content screen: it gets its own opaque
    // nebula backdrop so results always read, and the field itself is cut
    // in the shard silhouette.
    Box(Modifier.fillMaxSize()) {
        CrystalBackground(Modifier.fillMaxSize())
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
                if (debounced.isBlank()) {
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
                    if (results.isEmpty) {
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
