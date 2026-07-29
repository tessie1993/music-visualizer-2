package dev.musicviz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.media3.common.Player
import dev.musicviz.analysis.AudioQualityInfo
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
    val audioQuality by viewModel.audioQuality.collectAsState()
    val gui by viewModel.guiPrefs.collectAsState()
    // Chrome over the live canvas follows the Settings bar-opacity slider,
    // clamped to >= 0.25 so the transport stays readable over bright visuals.
    val chromeAlpha = maxOf(gui.barOpacity, 0.25f)
    var controlsVisible by remember { mutableStateOf(true) }
    var qualityExpanded by remember { mutableStateOf(false) }

    BackHandler { onCollapse() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { controlsVisible = !controlsVisible })
            },
    ) {
        VisualizerCanvasHost(visualizerView, Modifier.fillMaxSize())

        if (controlsVisible) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .glassPanel(
                        chromeAlpha,
                        MaterialTheme.colorScheme.surface,
                        corner = 12.dp,
                        glow = MaterialTheme.colorScheme.primary,
                    ).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalIconButton(onClick = onCollapse) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Collapse")
                }
                Column {
                    Text(
                        state.title?.ifBlank { "MusicViz" } ?: "MusicViz",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    state.artist?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                    audioQuality?.let { q ->
                        AudioQualityRow(
                            quality = q,
                            expanded = qualityExpanded,
                            onToggle = { qualityExpanded = !qualityExpanded },
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
                        FilledTonalIconButton(
                            onClick = viewModel::togglePlayPause,
                            enabled = state.hasMedia,
                            modifier = Modifier.softGlow(MaterialTheme.colorScheme.primary, 14.dp),
                        ) {
                            Icon(
                                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (state.isPlaying) "Pause" else "Play",
                            )
                        }
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

/**
 * Audio-quality readout under the artist line: a colored badge (green for
 * lossless/bit-perfect, amber for lossy) plus the one-line format summary.
 * Tapping toggles an expanded detail card explaining the playback path.
 */
@Composable
private fun AudioQualityRow(
    quality: AudioQualityInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(Modifier.clickable(onClick = onToggle)) {
        Row(
            Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val badge =
                when {
                    quality.isBitPerfect -> "BIT-PERFECT"
                    quality.lossless -> "LOSSLESS"
                    else -> "LOSSY"
                }
            val badgeColor = if (quality.lossless) Color(0xFF2E7D32) else Color(0xFFB8860B)
            Text(
                badge,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                modifier =
                    Modifier
                        .background(badgeColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Text(
                quality.qualityLine(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
        if (expanded) {
            Card(
                modifier =
                    Modifier
                        .padding(top = 6.dp)
                        .widthIn(max = 320.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                    ),
            ) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(quality.label(), style = MaterialTheme.typography.labelMedium)
                    Text("Codec: ${quality.codec}", style = MaterialTheme.typography.labelSmall)
                    if (quality.container.isNotEmpty()) {
                        Text("Container: .${quality.container}", style = MaterialTheme.typography.labelSmall)
                    }
                    val src =
                        buildList {
                            if (quality.sourceSampleRateHz > 0) add(AudioQualityInfo.formatKhz(quality.sourceSampleRateHz))
                            if (quality.bitDepth > 0) add("${quality.bitDepth}-bit")
                            if (quality.sourceChannels > 0) add("${quality.sourceChannels}ch")
                        }
                    if (src.isNotEmpty()) {
                        Text("Source: ${src.joinToString(" · ")}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (quality.bitrateBps > 0) {
                        Text("Bitrate: ${quality.bitrateBps / 1000} kbps", style = MaterialTheme.typography.labelSmall)
                    }
                    if (quality.outputSampleRateHz > 0) {
                        // The tap forwards only 16-bit or float PCM, so the
                        // non-float case is always 16-bit here.
                        val out =
                            AudioQualityInfo.formatKhz(quality.outputSampleRateHz) +
                                (if (quality.outputChannels > 0) " · ${quality.outputChannels}ch" else "") +
                                (if (quality.outputFloat) " · float PCM" else " · 16-bit PCM")
                        Text("Output: $out", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        quality.explanation(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
