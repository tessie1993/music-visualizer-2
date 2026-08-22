package dev.geode.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import dev.geode.R
import dev.geode.render.VisualizerView
import dev.geode.render.scene.TouchTransform
import dev.geode.ui.theme.StoneIcon
import dev.geode.ui.theme.StoneIconArt

@Composable
fun VisualizerScreen(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    externalDisplayName: String? = null,
    onCollapse: () -> Unit,
    onOpenVisuals: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val autoMode by viewModel.autoMode.collectAsStateWithLifecycle()
    val gui by viewModel.guiPrefs.collectAsStateWithLifecycle()
    val waveform by viewModel.waveform.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()
    val abLoop by viewModel.abLoop.collectAsStateWithLifecycle()
    val external by viewModel.externalAudio.collectAsStateWithLifecycle()
    val currentUri = remember(state.title, state.artist) { viewModel.currentTrackUri() }
    val isFavourite = currentUri != null && currentUri in favourites
    var panel by remember { mutableStateOf(PlayerPanel.TRANSPORT) }
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
            .pointerInput(gui.touchSmear, gui.touchSmearStrength, gui.touchTransform) {
                if (!gui.touchSmear && !gui.touchTransform) return@pointerInput
                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                detectTransformGestures { centroid, pan, gestureZoom, gestureRotate ->
                    if (gui.touchTransform && TouchTransform.isTransform(gestureZoom, gestureRotate)) {
                        viewModel.nudgeTransform(gestureZoom, gestureRotate)
                    } else if (gui.touchSmear) {
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
            CrystalBackground(Modifier.fillMaxSize(), reducedMotion = gui.reducedMotion)
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CrystalOverline(stringResource(R.string.second_screen_showing_on))
                Text(
                    externalDisplayName,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    stringResource(R.string.second_screen_explainer),
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
                    Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.action_collapse))
                }
                Column(Modifier.weight(1f, fill = false)) {
                    val foreign = external.active
                    val foreignTrack = external.nowPlaying?.takeIf { it.title.isNotBlank() }
                    val appName = stringResource(R.string.app_name)
                    Text(
                        when {
                            foreign -> foreignTrack?.title ?: stringResource(R.string.source_other_apps)
                            else -> state.title?.ifBlank { appName } ?: appName
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
                                ).joinToString(" · ")
                                    .ifBlank { stringResource(R.string.subtitle_captured_from_another_app) }
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
                        StoneIconArt(
                            StoneIcon.FAVORITE,
                            stringResource(
                                if (isFavourite) {
                                    R.string.action_favourite_remove
                                } else {
                                    R.string.action_favourite_add
                                },
                            ),
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
                            StoneIconArt(
                                StoneIcon.SHUFFLE,
                                stringResource(R.string.action_shuffle),
                                tint =
                                    if (state.shuffle) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        }
                        IconButton(onClick = viewModel::previous, enabled = state.hasMedia) {
                            StoneIconArt(StoneIcon.PREVIOUS, stringResource(R.string.action_previous))
                        }
                        CrystalPlayButton(
                            icon = if (state.isPlaying) StoneIcon.PAUSE else StoneIcon.PLAY,
                            contentDescription =
                                stringResource(if (state.isPlaying) R.string.action_pause else R.string.action_play),
                            onClick = viewModel::togglePlayPause,
                            enabled = state.hasMedia,
                        )
                        IconButton(onClick = viewModel::next, enabled = state.hasMedia) {
                            StoneIconArt(StoneIcon.NEXT, stringResource(R.string.action_next))
                        }
                        IconButton(onClick = viewModel::cycleRepeatMode) {
                            StoneIconArt(
                                StoneIcon.REPEAT,
                                stringResource(R.string.action_repeat),
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
                            StoneIconArt(StoneIcon.SETTINGS, null)
                            Text("  " + stringResource(R.string.nav_visuals))
                        }
                        TextButton(onClick = viewModel::cycleAutoMode) {
                            StoneIconArt(StoneIcon.QUEUE, null)
                            Text(
                                "  " +
                                    stringResource(
                                        when (autoMode) {
                                            1 -> R.string.auto_random
                                            2 -> R.string.auto_smart
                                            3 -> R.string.auto_sections
                                            else -> R.string.auto_off
                                        },
                                    ),
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
                                label = stringResource(p.label),
                                selected = panel == p,
                                badge = if (p == PlayerPanel.QUEUE && queue.tracks.size > 1) queue.tracks.size else 0,
                                onClick = { panel = if (panel == p) PlayerPanel.TRANSPORT else p },
                            )
                        }
                        Box(Modifier.weight(1f))
                        TextButton(onClick = viewModel::cycleAbLoop, enabled = state.hasMedia) {
                            Text(
                                when {
                                    abLoop == null -> stringResource(R.string.ab_loop_idle)
                                    abLoop?.endMs == null -> stringResource(R.string.ab_loop_set_b)
                                    else -> stringResource(R.string.ab_loop_looping)
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

enum class PlayerPanel(
    @StringRes val label: Int,
) {
    TRANSPORT(R.string.panel_now),
    LYRICS(R.string.panel_lyrics),
    QUEUE(R.string.queue),
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

private const val FRAME_DT = 1f / 60f

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
