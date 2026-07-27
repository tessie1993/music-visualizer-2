package dev.musicviz.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.musicviz.export.ExportAspect
import dev.musicviz.export.ExportQuality
import dev.musicviz.export.ExportRatio

/**
 * Settings menu. Currently hosts the video export section: quality tier,
 * aspect ratio, render, and share/upload (system share sheet, which includes
 * Google Drive when installed) for the finished file.
 */
@Composable
fun SettingsDialog(
    export: ExportUiState,
    hasMedia: Boolean,
    currentTheme: AppTheme,
    onThemeChange: (AppTheme) -> Unit,
    guiPrefs: GuiPrefs,
    onGuiPrefsChange: (GuiPrefs) -> Unit,
    onPickPresetFolder: () -> Unit,
    onStart: (ExportAspect, Int) -> Unit,
    onStartToDestination: (ExportAspect, Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var quality by remember { mutableStateOf(ExportQuality.FHD1080) }
    var ratio by remember { mutableStateOf(ExportRatio.R16_9) }
    var fps by remember { mutableStateOf(60) }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Layout", style = MaterialTheme.typography.titleSmall)
                Text("Player position", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlayerPosition.entries.forEach { pos ->
                        QualityChip(pos.label, guiPrefs.playerPosition == pos) {
                            onGuiPrefsChange(guiPrefs.copy(playerPosition = pos))
                        }
                    }
                }
                Text("Corner style", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CornerStyle.entries.forEach { c ->
                        QualityChip(c.label, guiPrefs.cornerStyle == c) {
                            onGuiPrefsChange(guiPrefs.copy(cornerStyle = c))
                        }
                    }
                }
                Text("Bar opacity", style = MaterialTheme.typography.labelMedium)
                androidx.compose.material3.Slider(
                    value = guiPrefs.barOpacity,
                    onValueChange = { onGuiPrefsChange(guiPrefs.copy(barOpacity = it)) },
                    valueRange = 0.35f..1f,
                )
                Text("Analysis", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Beat threshold - raise if visuals flicker on busy tracks",
                    style = MaterialTheme.typography.labelMedium,
                )
                androidx.compose.material3.Slider(
                    value = guiPrefs.beatThresholdSigma,
                    onValueChange = { onGuiPrefsChange(guiPrefs.copy(beatThresholdSigma = it)) },
                    valueRange = 1.5f..4f,
                )
                Text("Paths", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (guiPrefs.presetMirrorUri != null) {
                        "Preset folder: chosen - saves are mirrored there"
                    } else {
                        "Preset folder: internal only"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onPickPresetFolder) { Text("Choose preset folder") }
                    if (guiPrefs.presetMirrorUri != null) {
                        TextButton(onClick = { onGuiPrefsChange(guiPrefs.copy(presetMirrorUri = null)) }) {
                            Text("Clear")
                        }
                    }
                }
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppTheme.entries.forEach { t ->
                        QualityChip(t.label, currentTheme == t) { onThemeChange(t) }
                    }
                }
                Text("Export video", style = MaterialTheme.typography.titleSmall)
                when {
                    export.running -> {
                        Text("Rendering offline...")
                        LinearProgressIndicator(
                            progress = { export.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    export.resultUri != null -> {
                        Text(
                            if (export.customDestination) {
                                "Saved to your chosen folder."
                            } else {
                                "Saved to your Videos library (Movies/MusicViz)."
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val share =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, export.resultUri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                context.startActivity(Intent.createChooser(share, "Upload / share to"))
                            }) {
                                Text("Upload to Drive / Share")
                            }
                        }
                    }
                    export.error != null -> {
                        Text("Failed: ${export.error}", color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        Text("Quality", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            QualityChip("720p", quality == ExportQuality.HD720) { quality = ExportQuality.HD720 }
                            QualityChip("1080p", quality == ExportQuality.FHD1080) { quality = ExportQuality.FHD1080 }
                            QualityChip("4K", quality == ExportQuality.UHD4K) { quality = ExportQuality.UHD4K }
                        }
                        Text("Frame rate", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            QualityChip("30 fps", fps == 30) { fps = 30 }
                            QualityChip("60 fps", fps == 60) { fps = 60 }
                        }
                        Text("Aspect ratio", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ExportRatio.entries.forEach { r ->
                                QualityChip(r.label, ratio == r) { ratio = r }
                            }
                        }
                        if (quality == ExportQuality.UHD4K) {
                            Text(
                                "4K depends on your device's encoder; it falls back automatically if unsupported.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Button(
                            onClick = { onStart(ExportAspect.of(quality, ratio), fps) },
                            enabled = hasMedia,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Render ${quality.shortSide}p ${ratio.label} ${fps}fps")
                        }
                        OutlinedButton(
                            onClick = { onStartToDestination(ExportAspect.of(quality, ratio), fps) },
                            enabled = hasMedia,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Render to chosen folder\u2026")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (export.running) {
                TextButton(onClick = onCancel) { Text("Cancel export") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun QualityChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
    )
}
