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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import dev.musicviz.analysis.SearchMatcher
import dev.musicviz.render.VisualSafety
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
    // Second screen. The canvas is MOVED to the external display rather than
    // duplicated, so the screens below must not try to host it at the same
    // time - a View has one parent.
    val externalDisplay = rememberExternalDisplay()
    val onSecondScreen = gui.secondScreen && externalDisplay != null
    if (onSecondScreen && externalDisplay != null) {
        SecondScreenCanvas(externalDisplay, visualizerView)
    }

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
                onPrevious = viewModel::previous,
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
                        0 ->
                            HomeScreen(
                                viewModel,
                                onOpenSearch = { searching = true },
                                onExpand = { expanded = true },
                                onOpenLibrary = { dest = 1 },
                                onOpenVisuals = { dest = 2 },
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
                color = accentTextColor(),
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

/**
 * Settings as a nav destination, in two tabs.
 *
 * "Settings" holds the app-level preferences (appearance, playback, live
 * input, safety, analysis); "Customize" mounts the very same [CustomizePanel]
 * the Visuals hub shows. One panel, two doors - the same relationship Visuals
 * and Now Playing already have - so the controls a user reaches for while
 * they are in Settings are where they expect them, without a second copy that
 * could drift.
 *
 * Export lives in the export dialog.
 */
@Composable
fun SettingsScreen(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var showExport by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            CrystalOverline("MusicViz")
            GlowTitle("Settings")
        }
        CrystalTabs(titles = listOf("Settings", "Customize"), selected = tab, onSelect = { tab = it })
        when (tab) {
            0 -> AppSettingsTab(viewModel) { showExport = true }
            else -> CustomizePanel(viewModel, visualizerView)
        }
    }
    if (showExport) {
        ExportHost(viewModel, visualizerView) { showExport = false }
    }
}

/** The app-preferences half of [SettingsScreen]. */
@Composable
private fun AppSettingsTab(
    viewModel: PlayerViewModel,
    onOpenExport: () -> Unit,
) {
    val gui by viewModel.guiPrefs.collectAsState()
    val appTheme by viewModel.theme.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            SettingsSection("Live input & touch") { LiveInputSettings(viewModel) }
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
            // Its own section, above the creative controls, because it is the
            // one settings group a user may be looking for before they let the
            // app draw anything at all.
            SettingsSection("Visual safety") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Safe visuals", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = gui.safeVisuals,
                            onCheckedChange = { viewModel.setGuiPrefs(gui.copy(safeVisuals = it)) },
                        )
                    }
                    Text(
                        "Limits how fast and how strongly the whole screen can flash: caps the strobe and " +
                            "beat flash, holds brightness and contrast near neutral, turns hard scene cuts into " +
                            "crossfades, and slows any modulation aimed at brightness. Recommended if you or " +
                            "anyone watching is sensitive to flashing light.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (gui.safeVisuals) {
                    Column {
                        Text(
                            "Maximum flashes per second  ${"%.1f".format(gui.maxFlashHz)} Hz" +
                                if (gui.maxFlashHz <= VisualSafety.WCAG_FLASHES_PER_SECOND) "  (within guidance)" else "",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        CrystalSlider(
                            value = gui.maxFlashHz,
                            onValueChange = { viewModel.setGuiPrefs(gui.copy(maxFlashHz = it)) },
                            valueRange = 1f..VisualSafety.DEFAULT_STROBE_HZ,
                        )
                        Text(
                            "Published guidance (WCAG 2.3.1) puts the general limit at three per second; the " +
                                "risk is highest between about 15 and 20. Without this the strobe runs at 9.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Maximum flash strength  ${(gui.maxFlashDepth * 100).roundToInt()}%" +
                                if (gui.maxFlashDepth <= 0f) "  (no flashing at all)" else "",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        CrystalSlider(
                            value = gui.maxFlashDepth,
                            onValueChange = { viewModel.setGuiPrefs(gui.copy(maxFlashDepth = it)) },
                            valueRange = 0f..1f,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Allow invert and solarize",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Switch(
                                checked = gui.allowInversion,
                                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(allowInversion = it)) },
                            )
                        }
                        Text(
                            "These reverse the whole frame at once. Off is safer; on keeps them available if " +
                                "you turned Safe visuals on for the flash-rate limits alone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reduced motion", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = gui.reducedMotion,
                            onCheckedChange = { viewModel.setGuiPrefs(gui.copy(reducedMotion = it)) },
                        )
                    }
                    Text(
                        "Slows movement, shake, drift and rotation. Separate from Safe visuals: this one is " +
                            "about motion comfort rather than flashing, and either can be used on its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Both settings apply to exported video as well as the screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection("Visuals & Analysis") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Colour from the musical key", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = gui.keyColor, onCheckedChange = viewModel::setKeyColor)
                    }
                    Text(
                        "Sets Hue shift from the key the analyser found, around the circle of fifths — so a " +
                            "track keeps the same colour every time you play it, and two songs that sound " +
                            "related look related. It moves the ordinary Hue shift slider, so you can always " +
                            "disagree with it; switching this off gives your own value back.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            SettingsSection("Live wallpaper") {
                val ctx = LocalContext.current
                Text(
                    "Set the visualizer as your wallpaper. It uses the style and settings the app was " +
                        "last showing, reacts to whatever MusicViz is playing, and drifts gently on its " +
                        "own the rest of the time. It draws nothing while another app is in front, so it " +
                        "is not a background battery drain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CrystalButton(onClick = {
                    // The direct "change to THIS wallpaper" screen; some
                    // launchers do not implement it, so fall back to the
                    // system's live-wallpaper list rather than doing nothing.
                    val direct =
                        android.content.Intent(
                            android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER,
                        ).putExtra(
                            android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            android.content.ComponentName(
                                ctx,
                                dev.musicviz.wallpaper.VisualizerWallpaperService::class.java,
                            ),
                        )
                    val ok = runCatching { ctx.startActivity(direct) }.isSuccess
                    if (!ok) {
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_SET_WALLPAPER))
                        }
                    }
                }) { Text("Set as live wallpaper") }
            }
        }
        item {
            SettingsSection("Export & About") {
                CrystalButton(onClick = onOpenExport) { Text("Export video…") }
                AboutSection()
            }
        }
    }
}

/**
 * "Live input & touch": drive the visuals from the microphone with nothing
 * playing, and push them around with a finger.
 *
 * The two belong together because they are the same idea - the visualizer
 * responding to the room it is in rather than to a file - and because the
 * second is what you reach for the moment the first is on and there is no
 * transport to touch.
 *
 * The permission is requested at the moment the switch is used, never at
 * launch, and a denial is reported in place rather than leaving a switch that
 * silently springs back.
 */
@Composable
private fun LiveInputSettings(viewModel: PlayerViewModel) {
    val gui by viewModel.guiPrefs.collectAsState()
    val mic by viewModel.micState.collectAsState()
    var denied by remember { mutableStateOf(false) }
    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            denied = !granted
            if (granted) viewModel.setMicEnabled(true)
        }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("React to the microphone", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = mic.active,
                onCheckedChange = { want ->
                    denied = false
                    if (!want) {
                        viewModel.setMicEnabled(false)
                    } else if (viewModel.hasMicPermission()) {
                        viewModel.setMicEnabled(true)
                    } else {
                        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        }
        Text(
            "Plays nothing and drives the visuals from what the phone hears — a room, an " +
                "instrument, a speaker across the street. Playback pauses while it is on, because " +
                "the analyzer has one input and a track plus the room would just blur together. " +
                "Audio is analysed live and never recorded, saved or sent anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            denied || mic.failure == dev.musicviz.audio.MicCapture.Failure.PERMISSION ->
                Text(
                    "Microphone access is off for MusicViz. Turn it on in Android Settings › Apps › " +
                        "MusicViz › Permissions to use live input.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            mic.failure == dev.musicviz.audio.MicCapture.Failure.UNAVAILABLE ->
                Text(
                    "The microphone could not be opened — another app may be using it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
        }
    }
    Column {
        Text("Tune for what the phone is hearing", style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dev.musicviz.analysis.LiveInputProfile.entries.toList()) { profile ->
                CrystalButton(
                    compact = true,
                    filled = false,
                    onClick = { viewModel.applyLiveInputProfile(profile) },
                ) { Text(profile.label) }
            }
        }
        Text(
            dev.musicviz.analysis.LiveInputProfile.entries.joinToString("  ·  ") { "${it.label}: ${it.summary}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Each sets the beat threshold, the reactivity envelope and the band balance together — " +
                "they are one decision, and they live on three different screens. Every value stays " +
                "an ordinary slider afterwards.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Smear the visuals with a finger", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.touchSmear,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(touchSmear = it)) },
            )
        }
        Text(
            "Drag on the fullscreen visualizer to push the image around: the drag raises the " +
                "surface ahead of your finger and dips it behind, and whatever is on screen bends " +
                "through it. On the Water style it stirs the pool itself and paints into it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (gui.touchSmear) {
            Text("Smear strength  ${(gui.touchSmearStrength * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            CrystalSlider(
                value = gui.touchSmearStrength,
                onValueChange = { viewModel.setGuiPrefs(gui.copy(touchSmearStrength = it)) },
                valueRange = 0.2f..2f,
            )
        }
    }
    Column {
        val external = rememberExternalDisplay()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use a connected display", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.secondScreen,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(secondScreen = it)) },
            )
        }
        Text(
            if (external != null) {
                "Connected: ${external.name}. The visuals play there and the phone becomes the control " +
                    "surface — the canvas moves rather than being mirrored, so the big screen shows " +
                    "exactly what the app renders."
            } else {
                "Nothing connected. Plug in HDMI or start casting, and the visuals move to that screen " +
                    "while the phone keeps the controls."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pinch and twist the canvas", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.touchTransform,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(touchTransform = it)) },
            )
        }
        Text(
            "Two fingers on the fullscreen visualizer: pinch moves the Zoom slider, twist moves " +
                "Rotation. They are the same controls the Customize panel shows, so a gesture is " +
                "saved into presets and takes — and undone by dragging the slider back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
