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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
 * Video export dialog: quality tier, frame rate, aspect ratio, render, and
 * share/upload (system share sheet, which includes Google Drive when
 * installed) for the finished file. All other settings live in the Settings
 * destination (AppShell.SettingsScreen).
 */
@Composable
fun SettingsDialog(
    export: ExportUiState,
    hasMedia: Boolean,
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
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("Export") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        CrystalButton(
                            "Render ${quality.shortSide}p ${ratio.label} ${fps}fps",
                            onClick = { onStart(ExportAspect.of(quality, ratio), fps) },
                            enabled = hasMedia,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CrystalButton(
                            "Render to chosen folder…",
                            onClick = { onStartToDestination(ExportAspect.of(quality, ratio), fps) },
                            enabled = hasMedia,
                            kind = CrystalButtonKind.SECONDARY,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
    CrystalChip(label, selected = selected, onClick = onClick)
}
