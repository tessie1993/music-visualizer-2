package dev.musicviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.musicviz.analysis.AudioFeatures
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * Home: what you were listening to, what you keep coming back to, and one tap
 * back into the visuals.
 *
 * The previous version listed titles as bare chips - the same information the
 * Library tab shows, with less of it. This one is built around the two things
 * only Home can answer: "put the thing I was doing back on screen" (the hero
 * card, which is live - it carries the actual spectrum of what is playing) and
 * "what has my listening actually been" (the week strip, off real playing time
 * rather than a count of taps). Everything else is a shelf of artwork, because
 * a wall of sleeves is scannable in a way a wall of text is not.
 */
@Composable
fun HomeScreen(
    viewModel: PlayerViewModel,
    onOpenSearch: () -> Unit,
    onExpand: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenVisuals: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val viz by viewModel.vizState.collectAsState()
    val mic by viewModel.micState.collectAsState()
    val external by viewModel.externalAudio.collectAsState()
    val tick by viewModel.historyTick.collectAsState()
    val deviceTracks by viewModel.deviceTracks.collectAsState()
    val sleepRemainingMs by viewModel.sleepTimerRemainingMs.collectAsState()
    // The device index is what fills in artists and the "recently added"
    // shelf; Home is often the first screen touched after a cold start.
    LaunchedEffect(Unit) { viewModel.refreshDeviceTracks() }

    // Shelves are re-derived when history changes or the index arrives, not on
    // every frame of playback.
    val recent = remember(tick, deviceTracks) { viewModel.homeRecent() }
    val most = remember(tick, deviceTracks) { viewModel.homeMostPlayed() }
    val fresh = remember(deviceTracks) { viewModel.homeRecentlyAdded() }
    val loved = remember(tick, deviceTracks) { viewModel.homeFavourites() }
    // Listening totals move while a track plays, so they get their own slow
    // clock rather than riding the 500 ms UI tick.
    var statsTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            statsTick++
        }
    }
    val stats = remember(tick, statsTick) { viewModel.listeningStats() }
    val hasAnything = recent.isNotEmpty() || fresh.isNotEmpty() || state.hasMedia

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    CrystalOverline("MusicViz")
                    GlowTitle(greeting())
                }
                IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, "Search") }
            }
        }

        item {
            NowPlayingHero(
                viewModel = viewModel,
                state = state,
                styleLabel = viz.sceneId.replaceFirstChar { it.uppercase() },
                micActive = mic.active,
                external = external,
                onExpand = onExpand,
                onOpenLibrary = onOpenLibrary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        item {
            QuickActions(
                viewModel = viewModel,
                micActive = mic.active,
                external = external,
                sleepRunning = sleepRemainingMs != null,
                canShuffle = recent.isNotEmpty() || fresh.isNotEmpty(),
                onOpenVisuals = onOpenVisuals,
            )
        }

        if (stats.totalListenedMs > 0) {
            item { WeekStrip(stats, Modifier.padding(horizontal = 16.dp)) }
        }

        trackShelf("Jump back in", recent, viewModel)
        trackShelf("Favourites", loved, viewModel)
        trackShelf("On repeat", most, viewModel, showCount = true)
        trackShelf("Recently added", fresh, viewModel)

        if (viz.presets.isNotEmpty()) {
            item { ShelfHeading("Your visuals") }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                ) {
                    items(viz.presets.take(16), key = { it.name }) { preset ->
                        PresetTile(preset.name, preset.sceneId, selected = preset.sceneId == viz.sceneId) {
                            viewModel.applyPreset(preset)
                            onExpand()
                        }
                    }
                }
            }
        }

        if (!hasAnything) {
            item {
                Column(
                    Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .crystalPanel(
                            0.35f,
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.primary,
                            corner = 20.dp,
                        ).padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CrystalOverline("Nothing here yet")
                    Text(
                        "Open your music and play something — this screen fills in with what you " +
                            "played, what you keep going back to, and how long you actually listened. " +
                            "No music to hand? Live input puts the room on screen instead.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CrystalButton(onClick = onOpenLibrary) { Text("Open library") }
                        CrystalButton(filled = false, onClick = onOpenVisuals) { Text("Browse visuals") }
                    }
                }
            }
        }
    }
}

/** Time-of-day greeting; the one piece of Home that changes on its own. */
private fun greeting(): String =
    when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 0..4 -> "Still up"
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

/**
 * The hero card: artwork, transport, and the live spectrum of whatever is
 * actually feeding the analyzer - a track, or the room on live input.
 *
 * The spectrum is the point. It is the one thing on Home that could not be a
 * screenshot: it says "this app is listening right now" without a label
 * saying so, and it is the same data the visuals are drawn from.
 */
@Composable
private fun NowPlayingHero(
    viewModel: PlayerViewModel,
    state: PlayerUiState,
    styleLabel: String,
    micActive: Boolean,
    external: ExternalAudioState,
    onExpand: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uri = remember(state.title, state.artist) { viewModel.currentTrackUri() }
    // What the hero is actually about, most specific first: another app's
    // audio outranks our own paused track, because it is what is making
    // sound right now.
    val foreign = external.active
    val foreignTrack = external.nowPlaying?.takeIf { it.title.isNotBlank() }
    Column(
        modifier
            .fillMaxWidth()
            .crystalPanel(
                0.42f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 24.dp,
                glowStrength = if (state.isPlaying) 1.2f else 0.7f,
            ).clickable(enabled = state.hasMedia, onClick = onExpand)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackArtwork(if (foreign || micActive) null else uri, Modifier.size(76.dp), corner = 16.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                CrystalOverline(
                    when {
                        foreign -> external.nowPlaying?.appLabel ?: "Other apps"
                        micActive -> "Live input"
                        state.isPlaying -> "Now playing"
                        state.hasMedia -> "Paused"
                        else -> "Nothing playing"
                    },
                )
                Text(
                    when {
                        foreign -> foreignTrack?.title ?: "Whatever is playing"
                        micActive -> "The room"
                        state.hasMedia -> state.title ?: "Untitled"
                        else -> "Pick something to play"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        external.refusedByApp ->
                            "${external.refusingApp ?: "That app"} will not be captured — see Settings"
                        foreign -> foreignTrack?.artist?.ifBlank { null } ?: "Captured from another app"
                        micActive -> "Whatever the microphone hears"
                        state.hasMedia -> state.artist?.takeIf { it.isNotBlank() } ?: "Unknown artist"
                        else -> "Your library, your history, or the room"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (external.refusedByApp) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        LiveSpectrum(
            viewModel,
            live = state.isPlaying || micActive || foreign,
            modifier = Modifier.fillMaxWidth().height(40.dp),
        )

        if (state.hasMedia && !foreign && !micActive) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatClock(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                ) {
                    val progress =
                        if (state.durationMs > 0) {
                            (state.positionMs / state.durationMs.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(formatClock(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (foreign) {
                CrystalButton(filled = false, onClick = viewModel::stopExternalAudio) { Text("Stop capture") }
            } else if (state.hasMedia) {
                IconButton(onClick = viewModel::previous) { Icon(Icons.Filled.SkipPrevious, "Previous") }
                CrystalPlayButton(
                    icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    onClick = viewModel::togglePlayPause,
                )
                IconButton(onClick = viewModel::next) { Icon(Icons.Filled.SkipNext, "Next") }
            } else {
                CrystalButton(onClick = {
                    viewModel.resumeLastPlayed()
                    onOpenLibrary()
                }) { Text("Find something") }
            }
            Box(Modifier.weight(1f))
            Text(
                styleLabel,
                style = MaterialTheme.typography.labelMedium,
                color = accentTextColor(),
                maxLines = 1,
            )
        }
    }
}

/**
 * A 24-bar reduction of the analyzer's 64 bands, sampled on its own clock.
 *
 * Deliberately not collected as ordinary state: the analyzer emits at the FFT
 * hop rate, and recomposing Home on every hop would make scrolling stutter for
 * a decoration. 20 Hz is well past the point where the bars read as smooth.
 */
@Composable
private fun LiveSpectrum(
    viewModel: PlayerViewModel,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    val bars by produceState(initialValue = FloatArray(BARS), live) {
        val smoothed = FloatArray(BARS)
        while (true) {
            val features: AudioFeatures = viewModel.features.value
            val bands = features.bands
            for (i in 0 until BARS) {
                val target =
                    if (!live || bands.isEmpty()) {
                        0f
                    } else {
                        // Each bar averages its slice of the band array, so the
                        // shape survives a change in band count.
                        val from = i * bands.size / BARS
                        val to = ((i + 1) * bands.size / BARS).coerceAtLeast(from + 1)
                        var acc = 0f
                        for (b in from until minOf(to, bands.size)) acc += bands[b]
                        acc / (minOf(to, bands.size) - from)
                    }
                // Fast up, slow down: peaks stay legible between samples.
                smoothed[i] =
                    if (target > smoothed[i]) target else smoothed[i] + (target - smoothed[i]) * 0.35f
            }
            value = smoothed.copyOf()
            delay(50)
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    androidx.compose.foundation.Canvas(modifier) {
        val gap = size.width / (BARS * 6f)
        val barWidth = (size.width - gap * (BARS - 1)) / BARS
        val brush =
            Brush.verticalGradient(
                listOf(secondary.copy(alpha = 0.95f), primary.copy(alpha = 0.75f)),
            )
        for (i in 0 until BARS) {
            val v = bars.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            // A floor so the row reads as a control rather than vanishing in
            // silence; the bars still visibly rest when nothing is playing.
            val h = (size.height * (0.06f + 0.94f * v)).coerceAtLeast(2f)
            drawRoundRect(
                brush = brush,
                topLeft = androidx.compose.ui.geometry.Offset(i * (barWidth + gap), size.height - h),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

private const val BARS = 24

/** The four things worth one tap from Home. */
@Composable
private fun QuickActions(
    viewModel: PlayerViewModel,
    micActive: Boolean,
    external: ExternalAudioState,
    sleepRunning: Boolean,
    canShuffle: Boolean,
    onOpenVisuals: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val micPermission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { granted -> if (granted) viewModel.setMicEnabled(true) }
    // "Visualize whatever is playing" is worth one tap from Home, so the
    // consent plumbing lives here too rather than only in Settings.
    val projectionLauncher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .StartActivityForResult(),
        ) { result ->
            val data = result.data
            if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
                dev.musicviz.audio.PlaybackCaptureService
                    .start(context, result.resultCode, data)
            } else {
                viewModel.noteExternalAudioConsentDenied()
            }
        }
    val capturePermissions =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestMultiplePermissions(),
        ) { granted ->
            if (granted[android.Manifest.permission.RECORD_AUDIO] != false) {
                viewModel.noteExternalAudioConsentPending()
                projectionLauncher.launch(
                    context
                        .getSystemService(android.media.projection.MediaProjectionManager::class.java)
                        .createScreenCaptureIntent(),
                )
            } else {
                viewModel.noteExternalAudioConsentDenied()
            }
        }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        item {
            QuickAction(Icons.Filled.Shuffle, "Shuffle all", enabled = canShuffle) {
                viewModel.shuffleAllHistory()
            }
        }
        item {
            QuickAction(Icons.Filled.Mic, if (micActive) "Room: on" else "Live input", active = micActive) {
                if (micActive) {
                    viewModel.setMicEnabled(false)
                } else if (viewModel.hasMicPermission()) {
                    viewModel.setMicEnabled(true)
                } else {
                    micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                }
            }
        }
        if (external.supported) {
            item {
                QuickAction(
                    Icons.Filled.Cast,
                    if (external.active) "Capturing" else "Other apps",
                    active = external.active,
                ) {
                    if (external.active) {
                        viewModel.stopExternalAudio()
                    } else {
                        capturePermissions.launch(
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                arrayOf(
                                    android.Manifest.permission.RECORD_AUDIO,
                                    android.Manifest.permission.POST_NOTIFICATIONS,
                                )
                            } else {
                                arrayOf(android.Manifest.permission.RECORD_AUDIO)
                            },
                        )
                    }
                }
            }
        }
        item {
            QuickAction(Icons.Filled.GraphicEq, "Surprise me") {
                viewModel.randomStepNow()
                onOpenVisuals()
            }
        }
        item {
            QuickAction(
                Icons.Filled.Bedtime,
                if (sleepRunning) "Sleep: on" else "Sleep 30m",
                active = sleepRunning,
            ) {
                if (sleepRunning) viewModel.cancelSleepTimer() else viewModel.startSleepTimer(30)
            }
        }
        item {
            QuickAction(Icons.Filled.LibraryMusic, "Everything") {
                viewModel.playAll(
                    viewModel.deviceTracks.value.map { PlaybackQueue.queueTrack(it) },
                    shuffled = true,
                )
            }
        }
    }
}

@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val tint =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        }
    Column(
        Modifier
            .width(84.dp)
            .crystalPanel(
                if (active) 0.5f else 0.28f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 18.dp,
                glowStrength = if (active) 1.1f else 0.45f,
                prismatic = active,
            ).clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Seven days of real listening time.
 *
 * Bars are scaled to the week's own busiest day rather than to a fixed
 * ceiling, so the shape is readable whether the week held ten minutes or ten
 * hours - and an empty day is visibly empty rather than a rounding error.
 */
@Composable
private fun WeekStrip(
    stats: HistoryStore.Stats,
    modifier: Modifier = Modifier,
) {
    val peak = (stats.week.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Column(
        modifier
            .fillMaxWidth()
            .crystalPanel(
                0.3f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 20.dp,
                glowStrength = 0.5f,
            ).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CrystalOverline("This week")
                Text(
                    formatDuration(stats.weekListenedMs),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${stats.totalPlays} plays · ${stats.trackCount} tracks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                stats.topArtist?.let {
                    Text(
                        "Most hours: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentTextColor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(46.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            val today = Calendar.getInstance()
            stats.week.forEachIndexed { i, ms ->
                val daysBack = HistoryStore.WEEK_DAYS - 1 - i
                val cal = today.clone() as Calendar
                cal.add(Calendar.DAY_OF_YEAR, -daysBack)
                val isToday = daysBack == 0
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((4 + 28 * (ms.toFloat() / peak)).dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                            .background(
                                if (isToday) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.42f)
                                },
                            ),
                    )
                    Text(
                        DAY_INITIALS[(cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)],
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = if (isToday) accentTextColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val DAY_INITIALS = listOf("S", "M", "T", "W", "T", "F", "S")

@Composable
private fun ShelfHeading(text: String) {
    CrystalOverline(text, Modifier.padding(horizontal = 16.dp))
}

/**
 * One horizontal shelf of tracks. The shelf IS the queue a tap starts, so
 * Next walks what you were looking at rather than running out after one
 * track - the same rule the Library and search results follow.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.trackShelf(
    title: String,
    tracks: List<HomeTrack>,
    viewModel: PlayerViewModel,
    showCount: Boolean = false,
) {
    if (tracks.isEmpty()) return
    item(key = "h_$title") { ShelfHeading(title) }
    item(key = "s_$title") {
        val queue = tracks.map { QueueTrack(it.uri, it.title, it.artist) }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        ) {
            items(tracks, key = { it.uri }) { t ->
                TrackTile(t, showCount) { viewModel.playFrom(queue, t.uri) }
            }
        }
    }
}

@Composable
private fun TrackTile(
    track: HomeTrack,
    showCount: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(124.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            TrackArtwork(track.uri, Modifier.fillMaxWidth().aspectRatio(1f), corner = 16.dp)
            if (showCount && track.playCount > 1) {
                Text(
                    "${track.playCount}×",
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                )
            }
        }
        Text(
            track.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artist.ifBlank { "Unknown artist" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A saved look, standing in for itself with the same hash-derived gradient
 * artwork uses - so a preset shelf is as scannable as a track shelf without
 * rendering thirty live previews.
 */
@Composable
private fun PresetTile(
    name: String,
    sceneId: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(124.dp).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(placeholderBrush(name))
                .graphicsLayer { alpha = if (selected) 1f else 0.82f },
        ) {
            if (selected) {
                CrystalGem(
                    MaterialTheme.colorScheme.primary,
                    size = 8.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
            Text(
                sceneId,
                Modifier.align(Alignment.BottomStart).padding(8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** "4h 12m" / "38m" / "just started" - never a bare millisecond count. */
fun formatDuration(ms: Long): String {
    val minutes = (ms / 60_000.0).roundToInt()
    return when {
        minutes <= 0 -> "under a minute"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}
