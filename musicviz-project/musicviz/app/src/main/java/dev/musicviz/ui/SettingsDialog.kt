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
 * Video export dialog: quality tier, frame rate, aspect ratio, render, and
 * share/upload (system share sheet, which includes Google Drive when
 * installed) for the finished file. All other settings live in the Settings
 * destination (AppShell.SettingsScreen).
 *
 * The choices start from the persisted [ExportDefaults] (edited in
 * Settings › Export) and every change is written back as the new default, so
 * the dialog always opens the way the last render was set up.
 */
@Composable
fun SettingsDialog(
    export: ExportUiState,
    hasMedia: Boolean,
    takes: List<String>,
    selectedTake: String?,
    onSelectTake: (String?) -> Unit,
    /** Detected tempo, so the dialog can say whether a bar trim is possible. */
    bpm: Float,
    onStart: (ExportAspect, Int, Boolean) -> Unit,
    onStartToDestination: (ExportAspect, Int, Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val exportPrefs = remember { ExportPrefsStore(context) }
    val defaults = remember { exportPrefs.load() }
    var quality by remember { mutableStateOf(defaults.quality) }
    var ratio by remember { mutableStateOf(defaults.ratio) }
    var fps by remember { mutableStateOf(defaults.fps) }
    // A stored loop-safe default only holds when this track has a tempo -
    // the same gate the chip below applies to a tap.
    var loopSafe by remember {
        mutableStateOf(defaults.loopSafe && dev.musicviz.analysis.BarTrim.barDurationUs(bpm) != null)
    }

    // Written back after every change below, so the next export - and the
    // Settings › Export tab - start from what was chosen here.
    fun persistDefaults() = exportPrefs.save(ExportDefaults(quality, fps, ratio, loopSafe))
    AlertDialog(
        onDismissRequest = onDismiss,
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
                            ExportQuality.entries.forEach { q ->
                                QualityChip(exportQualityLabel(q), quality == q) {
                                    quality = q
                                    persistDefaults()
                                }
                            }
                        }
                        Text("Frame rate", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            QualityChip("30 fps", fps == 30) {
                                fps = 30
                                persistDefaults()
                            }
                            QualityChip("60 fps", fps == 60) {
                                fps = 60
                                persistDefaults()
                            }
                        }
                        Text("Aspect ratio", style = MaterialTheme.typography.labelMedium)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ExportRatio.entries.forEach { r ->
                                QualityChip(r.label, ratio == r) {
                                    ratio = r
                                    persistDefaults()
                                }
                            }
                        }
                        val barUs = dev.musicviz.analysis.BarTrim.barDurationUs(bpm)
                        Text("Looping", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            QualityChip("Full length", !loopSafe) {
                                loopSafe = false
                                persistDefaults()
                            }
                            QualityChip("Loop-safe", loopSafe) {
                                loopSafe = barUs != null
                                persistDefaults()
                            }
                        }
                        Text(
                            if (barUs != null) {
                                "Loop-safe cuts on a bar boundary (${"%.0f".format(bpm)} BPM, " +
                                    "${"%.1f".format(barUs / 1_000_000f)} s per bar) so the last beat runs " +
                                    "into the first — what a short clip needs when a platform autoplays it " +
                                    "on repeat. Up to one bar is trimmed from the end."
                            } else {
                                "Loop-safe needs a detected tempo. Analyse the track (Now Playing › Auto) " +
                                    "and reopen this dialog."
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (takes.isNotEmpty()) {
                            Text("Performance", style = MaterialTheme.typography.labelMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                QualityChip("Live settings", selectedTake == null) { onSelectTake(null) }
                                takes.forEach { name ->
                                    QualityChip(name, selectedTake == name) { onSelectTake(name) }
                                }
                            }
                            if (selectedTake != null) {
                                Text(
                                    "Renders the take's parameter automation — every slider, colour and " +
                                        "effect moving as you performed it. A style SWITCH inside the take " +
                                        "is not reproduced: the render draws through the style selected " +
                                        "now, for its whole length.",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        if (quality == ExportQuality.UHD4K) {
                            Text(
                                "4K depends on your device's encoder; it falls back automatically if unsupported.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Button(
                            onClick = { onStart(ExportAspect.of(quality, ratio), fps, loopSafe) },
                            enabled = hasMedia,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Render ${quality.shortSide}p ${ratio.label} ${fps}fps")
                        }
                        OutlinedButton(
                            onClick = { onStartToDestination(ExportAspect.of(quality, ratio), fps, loopSafe) },
                            enabled = hasMedia,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Render to chosen folder…")
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
