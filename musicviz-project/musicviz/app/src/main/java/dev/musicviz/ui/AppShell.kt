package dev.musicviz.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.musicviz.analysis.FeatureExtractor
import dev.musicviz.analysis.SearchMatcher
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
                                    "Home" to Icons.Outlined.Home,
                                    "Library" to Icons.Outlined.LibraryMusic,
                                    "Visuals" to Icons.Outlined.GraphicEq,
                                    "Settings" to Icons.Outlined.Settings,
                                ),
                            selected = dest,
                            onSelect = { dest = it },
                            barOpacity = gui.barOpacity,
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
    val cs = MaterialTheme.colorScheme
    // Floating glass card per the mockups' mini player: crystal thumbnail,
    // one-line title, lit play control, luminous progress hairline.
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .crystalPanel(barOpacity, cs.surfaceVariant, cs.primary, corner = 18.dp, glowStrength = 0.7f)
            .clickable(onClick = onExpand),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (compact) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CrystalThumb(title ?: "MusicViz", size = if (compact) 26.dp else 36.dp, corner = 8.dp)
            Text(
                title ?: "Now playing",
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Play/Pause",
                    tint = cs.primary,
                )
            }
            IconButton(onClick = onNext) { Icon(Icons.Filled.SkipNext, "Next") }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(cs.onSurface.copy(alpha = 0.12f))) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            0f to cs.primary.copy(alpha = 0.4f),
                            1f to cs.primary,
                        ),
                    ),
            )
        }
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
                // "Resume" hero card per the mockups: circular lit play glyph,
                // overline + title, trailing chevron.
                val cs = MaterialTheme.colorScheme
                Row(
                    Modifier
                        .fillMaxWidth()
                        .crystalPanel(
                            0.4f,
                            cs.surfaceVariant,
                            cs.primary,
                            corner = 20.dp,
                        ).clickable(onClick = onExpand)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .softGlow(cs.primary, 10.dp)
                            .clip(CircleShape)
                            .background(cs.primary.copy(alpha = 0.25f))
                            .border(1.dp, cs.primary.copy(alpha = 0.8f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        CrystalOverline("Resume")
                        Text(state.title ?: "Current queue", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant)
                }
            }
        }
        item {
            CrystalButton(
                "Shuffle All",
                onClick = viewModel::shuffleAllHistory,
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.Shuffle,
                kind = CrystalButtonKind.SECONDARY,
            )
        }
        if (recent.isNotEmpty()) {
            item { CrystalOverline("Recently played", Modifier.padding(top = 6.dp)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recent) { e -> CrystalChip(e.title, onClick = { viewModel.playTrack(e.uri) }) }
                }
            }
        }
        if (most.isNotEmpty()) {
            item { CrystalOverline("Most played", Modifier.padding(top = 6.dp)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(most) { e -> CrystalChip("${e.title} (${e.playCount})", onClick = { viewModel.playTrack(e.uri) }) }
                }
            }
        }
        if (recent.isEmpty()) {
            item { Text("Play something from the Library to see history here.", style = MaterialTheme.typography.bodyMedium) }
        }
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
                title.uppercase(),
                Modifier.weight(1f),
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
                        CrystalChip(t.label, onClick = { viewModel.setTheme(t) }, selected = t == appTheme)
                    }
                }
                CrystalSliderRow(
                    "Bar opacity",
                    gui.barOpacity,
                    0.2f..1f,
                    onChange = { viewModel.setGuiPrefs(gui.copy(barOpacity = it)) },
                    valueText = "${(gui.barOpacity * 100).toInt()}%",
                )
                Column {
                    CrystalOverline("Player position", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    CrystalSegmented(
                        PlayerPosition.entries.map { it.label },
                        gui.playerPosition.ordinal,
                        onSelect = { viewModel.setGuiPrefs(gui.copy(playerPosition = PlayerPosition.entries[it])) },
                    )
                }
                Column {
                    CrystalOverline("Corner style", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    CrystalSegmented(
                        CornerStyle.entries.map { it.label },
                        gui.cornerStyle.ordinal,
                        onSelect = { viewModel.setGuiPrefs(gui.copy(cornerStyle = CornerStyle.entries[it])) },
                    )
                }
                CrystalSliderRow(
                    "Accent strength",
                    gui.accentIntensity,
                    0.5f..1.5f,
                    onChange = { viewModel.setGuiPrefs(gui.copy(accentIntensity = it)) },
                    valueText = "${(gui.accentIntensity * 100).toInt()}%",
                )
                CrystalSliderRow(
                    "Background dim",
                    gui.backgroundDim,
                    0f..0.6f,
                    onChange = { viewModel.setGuiPrefs(gui.copy(backgroundDim = it)) },
                    valueText = "${(gui.backgroundDim * 100).toInt()}%",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Follow system light/dark", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = gui.followSystemDark,
                        onCheckedChange = { viewModel.setGuiPrefs(gui.copy(followSystemDark = it)) },
                        colors = crystalSwitchColors(),
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
                        colors = crystalSwitchColors(),
                    )
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Clear-overlay Visuals menu", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = gui.clearVisualsMenu,
                            onCheckedChange = { viewModel.setGuiPrefs(gui.copy(clearVisualsMenu = it)) },
                            colors = crystalSwitchColors(),
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
                    }, colors = crystalSwitchColors())
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CrystalButton(
                            "Choose preset folder",
                            onClick = { folderPicker.launch(null) },
                            kind = CrystalButtonKind.SECONDARY,
                        )
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
                CrystalSliderRow(
                    "Preset morph (beats, 0 = snap)",
                    gui.morphBeats.toFloat(),
                    0f..16f,
                    onChange = { viewModel.setGuiPrefs(gui.copy(morphBeats = it.toInt())) },
                    valueText = "${gui.morphBeats}",
                    steps = 15,
                )
                // Range comes from the extractor so the slider can never
                // saturate against a tighter clamp in AnalysisEngine.
                CrystalSliderRow(
                    "Beat sensitivity — drag right for LESS sensitive",
                    gui.beatThresholdSigma,
                    FeatureExtractor.SIGMA_MIN..FeatureExtractor.SIGMA_MAX,
                    onChange = { viewModel.setGuiPrefs(gui.copy(beatThresholdSigma = it)) },
                    valueText = "%.1f\u03c3".format(gui.beatThresholdSigma),
                )
                CrystalSliderRow(
                    "Minimum gap between beats",
                    gui.beatMinIntervalMs,
                    FeatureExtractor.INTERVAL_MS_MIN..FeatureExtractor.INTERVAL_MS_MAX,
                    onChange = { viewModel.setGuiPrefs(gui.copy(beatMinIntervalMs = it)) },
                    valueText =
                        "${gui.beatMinIntervalMs.roundToInt()} ms \u00b7 " +
                            "max ${(60_000f / gui.beatMinIntervalMs).roundToInt()} BPM",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CrystalButton(
                        "Slow track",
                        onClick = {
                            viewModel.setGuiPrefs(
                                gui.copy(
                                    beatThresholdSigma = FeatureExtractor.SLOW_SIGMA,
                                    beatMinIntervalMs = FeatureExtractor.SLOW_INTERVAL_MS,
                                ),
                            )
                        },
                        kind = CrystalButtonKind.SECONDARY,
                    )
                    CrystalButton(
                        "Default",
                        onClick = {
                            viewModel.setGuiPrefs(
                                gui.copy(
                                    beatThresholdSigma = FeatureExtractor.SIGMA_DEFAULT,
                                    beatMinIntervalMs = FeatureExtractor.INTERVAL_MS_DEFAULT,
                                ),
                            )
                        },
                        kind = CrystalButtonKind.SECONDARY,
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
                CrystalButton("Export video…", onClick = { showExport = true })
                AboutSection()
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
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CrystalSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search tracks, artists, albums…",
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close search") }
            }
            Spacer(Modifier.height(10.dp))
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
                        item { CrystalOverline("Tracks (${trackResults.size})", Modifier.padding(vertical = 4.dp)) }
                        items(trackResults, key = { "t:${it.uri}" }) { t ->
                            CrystalListRow(
                                title = t.title,
                                subtitle = t.subtitle.takeIf { it.isNotBlank() },
                                onClick = {
                                    viewModel.playTrack(t.uri)
                                    onClose()
                                },
                                thumbSeed = t.title,
                            ) {
                                IconButton(onClick = { viewModel.enqueue(t.uri) }) {
                                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue")
                                }
                            }
                        }
                    }
                    if (playlistResults.isNotEmpty()) {
                        item { CrystalOverline("Playlists (${playlistResults.size})", Modifier.padding(vertical = 4.dp)) }
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
                        item { CrystalOverline("Presets (${presetResults.size})", Modifier.padding(vertical = 4.dp)) }
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
