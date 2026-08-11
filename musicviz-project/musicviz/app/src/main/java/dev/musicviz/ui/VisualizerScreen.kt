package dev.musicviz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.musicviz.render.VisualizerView
import dev.musicviz.render.scene.TouchTransform

/**
 * Now Playing: the fullscreen visualizer canvas with the app shell's design
 * language - one Material3 card of transport controls, a collapse chip, and
 * a shortcut into the Visuals hub. All visual configuration lives in the hub
 * (AppShell tab), so this screen is deliberately minimal. Tap the canvas to
 * hide or show the controls.
 */
@Composable
fun VisualizerScreen(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    /**
     * Name of the display the canvas has been sent to, or null when it is
     * here. Non-null means this screen must NOT host the view - it has one
     * parent, and that parent is currently the presentation.
     */
    externalDisplayName: String? = null,
    onCollapse: () -> Unit,
    onOpenVisuals: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val autoMode by viewModel.autoMode.collectAsState()
    val gui by viewModel.guiPrefs.collectAsState()
    val waveform by viewModel.waveform.collectAsState()
    val lyrics by viewModel.lyrics.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val favourites by viewModel.favourites.collectAsState()
    val abLoop by viewModel.abLoop.collectAsState()
    val external by viewModel.externalAudio.collectAsState()
    val currentUri = remember(state.title, state.artist) { viewModel.currentTrackUri() }
    val isFavourite = currentUri != null && currentUri in favourites
    // Which of the three faces of the player is showing. Deliberately not
    // saved across a collapse: reopening Now Playing should show the
    // transport, not whatever tab was left open an hour ago.
    var panel by remember { mutableStateOf(PlayerPanel.TRANSPORT) }
    // Chrome over the live canvas follows the Settings bar-opacity slider,
    // clamped to >= 0.25 so the transport stays readable over bright visuals.
    val chromeAlpha = maxOf(gui.barOpacity, 0.25f)
    var controlsVisible by remember { mutableStateOf(true) }

    BackHandler { onCollapse() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { controlsVisible = !controlsVisible })
            }
            // Canvas gestures. ONE detector for both, because two would fight
            // over the same pointers: a drag detector and a transform detector
            // stacked on one element each consume the changes the other is
            // waiting for, and which one wins depends on modifier order rather
            // than on what the fingers did. Taps still reach the detector above
            // either way - a tap moves nothing, so nothing here consumes it.
            //
            // Only this screen gets them: the clear-overlay Visuals menu puts
            // scrolling lists on the same canvas, and a drag there belongs to
            // the list.
            .pointerInput(gui.touchSmear, gui.touchSmearStrength, gui.touchTransform) {
                if (!gui.touchSmear && !gui.touchTransform) return@pointerInput
                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                detectTransformGestures { centroid, pan, gestureZoom, gestureRotate ->
                    if (gui.touchTransform && TouchTransform.isTransform(gestureZoom, gestureRotate)) {
                        // Two fingers: pinch is Zoom, twist is Rotation.
                        viewModel.nudgeTransform(gestureZoom, gestureRotate)
                    } else if (gui.touchSmear) {
                        // One finger (or two moving together): push the
                        // surface. Normalized to the view, y still DOWN as the
                        // UI reports it; the renderer converts to sim space on
                        // the GL thread.
                        visualizerView.visualizerRenderer.queueTouchStroke(
                            nx = centroid.x / w,
                            ny = centroid.y / h,
                            ndx = pan.x / w,
                            ndy = pan.y / h,
                            dt = FRAME_DT,
                            strength = gui.touchSmearStrength,
                        )
                    }
                }
            },
    ) {
        if (externalDisplayName == null) {
            VisualizerCanvasHost(visualizerView, Modifier.fillMaxSize())
        } else {
            // The canvas is on the other screen; this one is the control
            // surface. Say where it went rather than showing a black rectangle
            // that reads as a crash.
            CrystalBackground(Modifier.fillMaxSize(), reducedMotion = gui.reducedMotion)
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CrystalOverline("Showing on")
                Text(
                    externalDisplayName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "The visuals are on the connected display. Everything here still controls them — " +
                        "turn this off in Settings › Live input & touch to bring them back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (controlsVisible) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .crystalPanel(
                        chromeAlpha,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.primary,
                        corner = 12.dp,
                        glowStrength = 0.6f,
                        facets = 0.7f,
                    ).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(onClick = onCollapse) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Collapse")
                }
                Column(Modifier.weight(1f, fill = false)) {
                    // Another app's audio outranks our own metadata: it is what
                    // is making the sound on screen.
                    val foreign = external.active
                    val foreignTrack = external.nowPlaying?.takeIf { it.title.isNotBlank() }
                    Text(
                        when {
                            foreign -> foreignTrack?.title ?: "Other apps"
                            else -> state.title?.ifBlank { "MusicViz" } ?: "MusicViz"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle =
                        when {
                            foreign ->
                                listOfNotNull(
                                    foreignTrack?.artist?.takeIf { it.isNotBlank() },
                                    external.nowPlaying?.appLabel,
                                ).joinToString(" · ").ifBlank { "Captured from another app" }
                            else -> state.artist?.takeIf { it.isNotBlank() }.orEmpty()
                        }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (state.hasMedia && !external.active) {
                    IconButton(onClick = { viewModel.toggleFavourite() }) {
                        Icon(
                            if (isFavourite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            if (isFavourite) "Remove from favourites" else "Add to favourites",
                            tint =
                                if (isFavourite) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }

            // Lyrics and the queue take over the space between the title chip
            // and the transport, over the live canvas rather than instead of
            // it - the visuals are the reason to be on this screen.
            if (panel != PlayerPanel.TRANSPORT) {
                PlayerPanelSurface(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 88.dp, bottom = 210.dp)
                        .fillMaxSize(),
                ) {
                    when (panel) {
                        PlayerPanel.LYRICS ->
                            LyricsPanel(
                                lyrics = lyrics,
                                positionMs = state.positionMs,
                                onSeek = { viewModel.seekToMs(it) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        else ->
                            QueuePanel(
                                queue = queue,
                                favourites = favourites,
                                onPlayIndex = viewModel::playQueueIndex,
                                onMoveUp = { viewModel.moveQueueItem(it, it - 1) },
                                onRemove = viewModel::removeQueueItem,
                                modifier = Modifier.fillMaxSize(),
                            )
                    }
                }
            }

            // Readability scrim under the transport: darkens the lowest part
            // of bright visuals so a low-opacity glass card stays legible.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(200.dp)
                    .glassScrim(),
            )

            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 16.dp)
                        .crystalPanel(
                            chromeAlpha,
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.primary,
                            corner = 24.dp,
                            glowStrength = 0.8f,
                        ),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelSmall)
                        WaveformSeekBar(
                            waveform = waveform,
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            loopStartMs = abLoop?.startMs,
                            loopEndMs = abLoop?.endMs,
                            onSeek = viewModel::seekTo,
                            modifier = Modifier.weight(1f).height(40.dp),
                        )
                        Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        IconButton(onClick = viewModel::toggleShuffle) {
                            Icon(
                                Icons.Filled.Shuffle,
                                "Shuffle",
                                tint =
                                    if (state.shuffle) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                        IconButton(onClick = viewModel::previous, enabled = state.hasMedia) {
                            Icon(Icons.Filled.SkipPrevious, "Previous")
                        }
                        CrystalPlayButton(
                            icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                            onClick = viewModel::togglePlayPause,
                            enabled = state.hasMedia,
                        )
                        IconButton(onClick = viewModel::next, enabled = state.hasMedia) {
                            Icon(Icons.Filled.SkipNext, "Next")
                        }
                        IconButton(onClick = viewModel::cycleRepeatMode) {
                            Icon(
                                if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                                    Icons.Filled.RepeatOne
                                } else {
                                    Icons.Filled.Repeat
                                },
                                "Repeat",
                                tint =
                                    if (state.repeatMode != Player.REPEAT_MODE_OFF) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = onOpenVisuals) {
                            Icon(Icons.Filled.Tune, null)
                            Text("  Visuals")
                        }
                        TextButton(onClick = viewModel::cycleAutoMode) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                            Text(
                                when (autoMode) {
                                    1 -> "  Auto: random"
                                    2 -> "  Auto: smart"
                                    3 -> "  Auto: sections"
                                    else -> "  Auto: off"
                                },
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        PlayerPanel.entries.forEach { p ->
                            PanelChip(
                                label = p.label,
                                selected = panel == p,
                                badge = if (p == PlayerPanel.QUEUE && queue.tracks.size > 1) queue.tracks.size else 0,
                                onClick = { panel = if (panel == p) PlayerPanel.TRANSPORT else p },
                            )
                        }
                        Box(Modifier.weight(1f))
                        // A-B: one control, three states. The label says which
                        // one you are in rather than leaving it to a colour.
                        TextButton(onClick = viewModel::cycleAbLoop, enabled = state.hasMedia) {
                            Text(
                                when {
                                    abLoop == null -> "A–B"
                                    abLoop?.endMs == null -> "Set B"
                                    else -> "Looping"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color =
                                    if (abLoop != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The three faces of Now Playing, chosen by the chips under the transport. */
enum class PlayerPanel(
    val label: String,
) {
    TRANSPORT("Now"),
    LYRICS("Lyrics"),
    QUEUE("Queue"),
}

@Composable
private fun PanelChip(
    label: String,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .crystalPanel(
                if (selected) 0.5f else 0.2f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 14.dp,
                glowStrength = if (selected) 1f else 0.35f,
                prismatic = selected,
            ).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            if (badge > 0) "$label · $badge" else label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accentTextColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Timestep a drag frame is reported with. Compose delivers drag deltas per
 * pointer event rather than per unit of time, and the sim only needs a speed
 * scale, not a clock: a fixed nominal frame keeps a fast flick reading as fast
 * without threading a second time source through the gesture.
 */
private const val FRAME_DT = 1f / 60f

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
