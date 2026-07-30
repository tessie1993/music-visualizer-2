package dev.musicviz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import dev.musicviz.render.VisualizerView

/**
 * Now Playing: the fullscreen visualizer canvas with the app shell's design
 * language - one Material3 card of transport controls, a collapse chip, and
 * a shortcut into the Visuals hub. All visual configuration lives in the hub
 * (AppShell tab), so this screen is deliberately minimal; the old
 * TopPlayBar/QuickBar navigation is gone. Tap the canvas to hide or show
 * the controls.
 */
@Composable
fun VisualizerScreen(
    viewModel: PlayerViewModel,
    visualizerView: VisualizerView,
    onCollapse: () -> Unit,
    onOpenVisuals: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val autoMode by viewModel.autoMode.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }

    BackHandler { onCollapse() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { controlsVisible = !controlsVisible })
            }.pointerInput(Unit) {
                // Finger smear: drags stir the fluid velocity field, so the
                // visuals can be mixed around by hand. Runs alongside the tap
                // detector - a real tap never exceeds touch slop, so both
                // gestures coexist.
                detectDragGestures { change, _ ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val h = size.height.toFloat().coerceAtLeast(1f)
                    visualizerView.visualizerRenderer.pointerSmear(
                        change.previousPosition.x / w,
                        change.previousPosition.y / h,
                        change.position.x / w,
                        change.position.y / h,
                    )
                    change.consume()
                }
            },
    ) {
        AndroidView(factory = { visualizerView }, modifier = Modifier.fillMaxSize())

        if (controlsVisible) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(onClick = onCollapse, modifier = Modifier.pressGlow()) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Collapse")
                }
                Column {
                    Text(
                        state.title?.ifBlank { "MusicViz" } ?: "MusicViz",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                    )
                    state.artist?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                }
            }

            Card(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 16.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                    ),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(formatTime(state.positionMs), style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value =
                                if (state.durationMs > 0) {
                                    (state.positionMs / state.durationMs.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    0f
                                },
                            onValueChange = viewModel::seekTo,
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatTime(state.durationMs), style = MaterialTheme.typography.labelSmall)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        IconButton(onClick = viewModel::toggleShuffle, modifier = Modifier.pressGlow()) {
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
                        IconButton(onClick = viewModel::previous, enabled = state.hasMedia, modifier = Modifier.pressGlow()) {
                            Icon(Icons.Filled.SkipPrevious, "Previous")
                        }
                        FilledTonalIconButton(
                            onClick = viewModel::togglePlayPause,
                            enabled = state.hasMedia,
                            modifier = Modifier.pressGlow(),
                        ) {
                            Icon(
                                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (state.isPlaying) "Pause" else "Play",
                            )
                        }
                        IconButton(onClick = viewModel::next, enabled = state.hasMedia, modifier = Modifier.pressGlow()) {
                            Icon(Icons.Filled.SkipNext, "Next")
                        }
                        IconButton(onClick = viewModel::cycleRepeatMode, modifier = Modifier.pressGlow()) {
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
                        TextButton(onClick = onOpenVisuals, modifier = Modifier.pressGlow()) {
                            Icon(Icons.Filled.Tune, null)
                            Text("  Visuals")
                        }
                        TextButton(onClick = viewModel::cycleAutoMode, modifier = Modifier.pressGlow()) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                            Text(
                                when (autoMode) {
                                    1 -> "  Auto: random"
                                    2 -> "  Auto: smart"
                                    else -> "  Auto: off"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
