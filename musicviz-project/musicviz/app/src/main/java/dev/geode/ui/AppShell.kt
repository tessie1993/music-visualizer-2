package dev.geode.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.geode.R
import dev.geode.analysis.SearchMatcher
import dev.geode.data.BootAnimationStore
import dev.geode.data.GeodePrefsFiles
import dev.geode.render.VisualSafetyChoice
import dev.geode.render.VisualizerView
import dev.geode.ui.theme.StoneIcon
import dev.geode.ui.theme.StoneIconArt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val CRASH_REPORT_MAX_BYTES = 64 * 1024

@Composable
fun AppRoot(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val visualizerView = remember { VisualizerView(context) }
    val themePack by viewModel.theme.collectAsStateWithLifecycle()
    val gui by viewModel.guiPrefs.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val effectiveTheme =
        if (gui.followSystemDark && !systemDark) {
            dev.geode.ui.theme.ThemePackCatalog.all
                .firstOrNull { it.isLight } ?: themePack
        } else {
            themePack
        }
    var dest by rememberSaveable { mutableStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var bootDone by rememberSaveable { mutableStateOf(false) }
    val bootAnimEnabled = remember { BootAnimationStore(GeodePrefsFiles(context).general).load() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val externalDisplay = rememberExternalDisplay()
    val onSecondScreen = gui.secondScreen && externalDisplay != null
    if (onSecondScreen) {
        SecondScreenCanvas(externalDisplay, visualizerView)
    }

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
    androidx.activity.compose.BackHandler(enabled = dest != 0) { dest = 0 }
    CrystalMaterialTheme(
        pack = effectiveTheme,
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
            CrystalBackground(Modifier.fillMaxSize(), reducedMotion = gui.reducedMotion)
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    if (gui.playerPosition == PlayerPosition.TOP && state.hasMedia && dest != 0) {
                        Box(Modifier.statusBarsPadding()) { miniPlayer() }
                    }
                },
                bottomBar = {
                    Column {
                        if (gui.playerPosition == PlayerPosition.BOTTOM && dest != 0) miniPlayer()
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .luminousHairline(MaterialTheme.colorScheme.primary),
                        )
                        CrystalNavBar(
                            items =
                                listOf(
                                    CrystalNavItem(stringResource(R.string.nav_player), StoneIcon.PLAY),
                                    CrystalNavItem(stringResource(R.string.nav_library), StoneIcon.LIBRARY),
                                    CrystalNavItem(stringResource(R.string.nav_visuals), StoneIcon.VISUALIZER),
                                    CrystalNavItem(stringResource(R.string.nav_studio), StoneIcon.STUDIO),
                                    CrystalNavItem(stringResource(R.string.nav_settings), StoneIcon.SETTINGS),
                                ),
                            selected = dest,
                            onSelect = { dest = it },
                            opacity = gui.barOpacity,
                        )
                    }
                },
            ) { pad ->
                Box(Modifier.padding(pad)) {
                    PlaybackNoticeBanner(viewModel)
                    when (dest) {
                        0 ->
                            PlayerScreen(
                                viewModel,
                                onOpenSearch = { searching = true },
                                onExpand = { expanded = true },
                                onOpenLibrary = { dest = 1 },
                            )
                        1 -> LibraryScreen(viewModel, onOpenSearch = { searching = true })
                        2 ->
                            VisualsHub(
                                viewModel,
                                visualizerView,
                                onOpenNowPlaying = { expanded = true },
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
            val clipLabel = stringResource(R.string.crash_clip_label)
            crashText?.let { text ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {},
                    title = { Text(stringResource(R.string.crash_dialog_title)) },
                    text = {
                        Text(
                            stringResource(R.string.crash_dialog_body, text.take(600)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    confirmButton = {
                        CrystalButton(onClick = {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText(clipLabel, text))
                        }) { Text(stringResource(R.string.action_copy)) }
                    },
                    dismissButton = {
                        CrystalButton(filled = false, onClick = {
                            java.io.File(context.filesDir, "crash-latest.txt").delete()
                            crashText = null
                        }) { Text(stringResource(R.string.action_dismiss)) }
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
            if ((!bootAnimEnabled || bootDone) && gui.safetyChoice == VisualSafetyChoice.UNKNOWN) {
                SafetyConsent(
                    onChoose = { choice, limited ->
                        viewModel.setGuiPrefs(gui.copy(safetyChoice = choice, safeVisuals = limited))
                    },
                )
            }
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
                title ?: stringResource(R.string.mini_player_idle),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onPrevious) { StoneIconArt(StoneIcon.PREVIOUS, stringResource(R.string.action_previous)) }
            IconButton(onClick = onPlayPause) {
                StoneIconArt(
                    if (isPlaying) StoneIcon.PAUSE else StoneIcon.PLAY,
                    stringResource(R.string.action_play_pause),
                )
            }
            IconButton(onClick = onNext) { StoneIconArt(StoneIcon.NEXT, stringResource(R.string.action_next)) }
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
fun SettingsScreen(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
) {
    var showExport by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            CrystalOverline(stringResource(R.string.app_name))
            GlowTitle(stringResource(R.string.nav_settings))
        }
        AppSettingsTab(viewModel, exportOpen = showExport, onOpenExport = { showExport = true })
    }
    if (showExport) {
        ExportHost(viewModel, visualizerView) { showExport = false }
    }
}

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
    val gui by viewModel.guiPrefs.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val viz by viewModel.vizState.collectAsStateWithLifecycle()
    val deviceTracks by viewModel.deviceTracks.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshDeviceTracks() }
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(250)
        debounced = query
    }
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
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                    shape = crystalShardShape(14.dp, 5.dp),
                )
                IconButton(onClick = onClose) { StoneIconArt(StoneIcon.CLOSE, stringResource(R.string.search_close)) }
            }
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (terms.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.search_hint),
                            Modifier.padding(vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    if (trackResults.isNotEmpty()) {
                        item {
                            CrystalOverline(
                                stringResource(R.string.search_heading_tracks, trackResults.size),
                                Modifier.padding(top = 8.dp),
                            )
                        }
                        items(trackResults, key = { "t:${it.uri}" }) { t ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
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
                                    StoneIconArt(StoneIcon.QUEUE, stringResource(R.string.action_add_to_queue))
                                }
                            }
                        }
                    }
                    if (playlistResults.isNotEmpty()) {
                        item {
                            CrystalOverline(
                                stringResource(R.string.search_heading_playlists, playlistResults.size),
                                Modifier.padding(top = 8.dp),
                            )
                        }
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
                                    pluralStringResource(
                                        R.plurals.track_count,
                                        pl.trackUris.size,
                                        pl.trackUris.size,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (presetResults.isNotEmpty()) {
                        item {
                            CrystalOverline(
                                stringResource(R.string.search_heading_presets, presetResults.size),
                                Modifier.padding(top = 8.dp),
                            )
                        }
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
                                stringResource(R.string.search_no_results, debounced),
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

@Composable
private fun PlaybackNoticeBanner(viewModel: PlayerViewModel) {
    val notice by viewModel.playbackNotice.collectAsStateWithLifecycle()
    val message = notice ?: return
    val dismissDescription = stringResource(R.string.notice_dismiss_description)

    LaunchedEffect(message) {
        kotlinx.coroutines.delay(NOTICE_VISIBLE_MS)
        viewModel.clearPlaybackNotice()
    }

    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .clickable { viewModel.clearPlaybackNotice() }
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.notice_dismiss),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier =
                    Modifier
                        .clickable { viewModel.clearPlaybackNotice() }
                        .semantics { contentDescription = dismissDescription }
                        .padding(8.dp),
            )
        }
    }
}

private const val NOTICE_VISIBLE_MS = 8_000L
