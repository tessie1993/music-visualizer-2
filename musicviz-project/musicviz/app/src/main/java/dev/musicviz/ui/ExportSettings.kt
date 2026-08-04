package dev.musicviz.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.musicviz.export.ExportQuality
import dev.musicviz.export.ExportRatio

/**
 * EXPORT: the persisted render defaults ([ExportDefaults]) and the door to
 * the export dialog. The defaults set here are what the dialog opens with;
 * the dialog writes its own changes back, so the two can never disagree for
 * longer than one render.
 *
 * [exportOpen] is whether the dialog is currently up: the stored defaults
 * are re-read when it closes, so a change made inside the dialog shows here
 * immediately.
 */
@Composable
internal fun ExportSettingsTab(
    exportOpen: Boolean,
    onOpenExport: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { ExportPrefsStore(context) }
    var defaults by remember { mutableStateOf(store.load()) }
    LaunchedEffect(exportOpen) { if (!exportOpen) defaults = store.load() }
    val update: (ExportDefaults) -> Unit = {
        defaults = it
        store.save(it)
    }
    SettingsTabColumn {
        item {
            SettingsGroup("Defaults") {
                Column {
                    Text("Quality", style = MaterialTheme.typography.labelMedium)
                    CrystalSegmented(
                        options = ExportQuality.entries.map { exportQualityLabel(it) },
                        selected = ExportQuality.entries.indexOf(defaults.quality),
                        onSelect = { update(defaults.copy(quality = ExportQuality.entries[it])) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column {
                    Text("Frame rate", style = MaterialTheme.typography.labelMedium)
                    CrystalSegmented(
                        options = listOf("30 fps", "60 fps"),
                        selected = if (defaults.fps == 30) 0 else 1,
                        onSelect = { update(defaults.copy(fps = if (it == 0) 30 else 60)) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column {
                    Text("Aspect ratio", style = MaterialTheme.typography.labelMedium)
                    // Six ratios outgrow the width of a phone; the selector
                    // scrolls rather than squeezing its labels.
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState())) {
                        CrystalSegmented(
                            options = ExportRatio.entries.map { it.label },
                            selected = ExportRatio.entries.indexOf(defaults.ratio),
                            onSelect = { update(defaults.copy(ratio = ExportRatio.entries[it])) },
                        )
                    }
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Loop-safe by default", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = defaults.loopSafe,
                            onCheckedChange = { update(defaults.copy(loopSafe = it)) },
                        )
                    }
                    Text(
                        "Loop-safe trims the end of the render to a bar boundary so the clip repeats " +
                            "cleanly when a platform autoplays it. It needs a detected tempo, so a track " +
                            "that has not been analysed falls back to full length for that render.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SettingsGroup("Render") {
                CrystalButton(onClick = onOpenExport) { Text("Export video…") }
                Text(
                    "Starts from the defaults above; each render can change them for that one file. " +
                        "The destination — your Videos library or a folder you pick — is chosen at " +
                        "render time, per export.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
