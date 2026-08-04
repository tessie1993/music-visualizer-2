package dev.musicviz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FOLDERS: every place the app reads from or writes to - the preset mirror
 * folder, the music library folders, the analysis cache, and a plain answer
 * to "where do exports go".
 */
@Composable
internal fun FolderSettingsTab(viewModel: PlayerViewModel) {
    val gui by viewModel.guiPrefs.collectAsState()
    SettingsTabColumn {
        item { SettingsGroup("Preset folder") { PresetFolderGroup(viewModel, gui) } }
        item { SettingsGroup("Music folders") { MusicFoldersEditor(viewModel) } }
        item { SettingsGroup("Analysis cache") { AnalysisCacheGroup() } }
        item {
            SettingsGroup("Export destination") {
                Text(
                    "Exports land in your Videos library (Movies/MusicViz), or in a folder you pick " +
                        "at render time — there is no standing export folder to configure. Defaults " +
                        "for quality and size live in the Export tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** SAF tree the user's saved presets are mirrored into, plus its clear. */
@Composable
private fun PresetFolderGroup(
    viewModel: PlayerViewModel,
    gui: GuiPrefs,
) {
    val ctx = LocalContext.current
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                viewModel.setGuiPrefs(gui.copy(presetMirrorUri = uri.toString()))
            }
        }
    Column {
        Text(
            if (gui.presetMirrorUri != null) {
                "Preset folder: chosen — saves are mirrored there"
            } else {
                "Preset folder: internal only"
            },
            style = MaterialTheme.typography.labelMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CrystalButton(filled = false, onClick = { folderPicker.launch(null) }) { Text("Choose preset folder") }
            if (gui.presetMirrorUri != null) {
                TextButton(onClick = { viewModel.setGuiPrefs(gui.copy(presetMirrorUri = null)) }) { Text("Clear") }
            }
        }
        Text(
            "Presets always save inside the app; a chosen folder gets a mirror copy so your own " +
                "sorting is visible in any file manager.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The music library's folder list - the same roots Library › Folders manages
 * ([PlayerViewModel.mediaRoots] is one state flow, so the two screens can
 * never disagree). Browsing the tracks inside each folder stays in Library;
 * this is the management half: the list, add, remove and rescan.
 */
@Composable
internal fun MusicFoldersEditor(viewModel: PlayerViewModel) {
    val roots by viewModel.mediaRoots.collectAsState()
    val scanning by viewModel.libraryScanning.collectAsState()
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) viewModel.importFolder(uri)
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (roots.isEmpty()) {
            Text(
                "No folders yet. Device music appears in the Library on its own; folders you add " +
                    "here are scanned into the import library as well.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        roots.sorted().forEach { root ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    java.net.URLDecoder
                        .decode(root.substringAfterLast("%3A").substringAfterLast("/"), "UTF-8")
                        .ifBlank { root },
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                IconButton(onClick = { viewModel.removeMediaRoot(root) }) {
                    Icon(Icons.Filled.Close, "Remove folder")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CrystalButton(compact = true, filled = false, onClick = { folderPicker.launch(null) }) { Text("Add folder") }
            CrystalButton(
                compact = true,
                filled = false,
                onClick = viewModel::rescanMediaRoots,
                enabled = roots.isNotEmpty() && !scanning,
            ) {
                Text(if (scanning) "Scanning…" else "Rescan")
            }
        }
        Text(
            "The same list as Library › Folders. Rescanning re-walks every folder; tracks already " +
                "imported keep their analysis.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Cached per-track analysis (beats, key, sections): size readout + clear. */
@Composable
private fun AnalysisCacheGroup() {
    val context = LocalContext.current
    var cacheInfo by remember { mutableStateOf("…") }
    var cacheBump by remember { mutableIntStateOf(0) }
    LaunchedEffect(cacheBump) {
        cacheInfo =
            withContext(Dispatchers.IO) {
                val app = context.applicationContext
                val n =
                    dev.musicviz.analysis.AnalysisCache
                        .entryCount(app)
                val mb =
                    dev.musicviz.analysis.AnalysisCache
                        .sizeBytes(app) / (1024f * 1024f)
                "%d tracks · %.1f MB".format(n, mb)
            }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Analysis cache: $cacheInfo", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            dev.musicviz.analysis.AnalysisCache
                .clear(context.applicationContext)
            cacheBump++
        }) { Text("Clear") }
    }
    Text(
        "Analysis results per track, so replaying never re-analyses. Clearing costs nothing but " +
            "the next analysis pass.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
