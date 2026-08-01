package dev.musicviz.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun LibraryScreen(
    viewModel: PlayerViewModel,
    onPersistUri: (Uri) -> Unit,
    onOpenSearch: () -> Unit,
) {
    val context = LocalContext.current
    val permission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var reloadKey by remember { mutableStateOf(0) }
    val tracks by viewModel.deviceTracks.collectAsState()
    // The MediaStore query lives in the ViewModel (Dispatchers.IO) so first
    // composition of this tab no longer blocks on the content resolver.
    LaunchedEffect(granted, reloadKey) { if (granted) viewModel.refreshDeviceTracks() }
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Tracks", "Albums", "Artists", "Playlists", "Folders")

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CrystalOverline("MusicViz")
                GlowTitle("Library")
            }
            IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, "Search") }
        }
        if (!granted) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Grant music access to browse everything on this device.")
                CrystalButton(onClick = { permLauncher.launch(permission) }) { Text("Allow music access") }
            }
            return
        }
        CrystalTabs(titles = tabs, selected = tab, onSelect = { tab = it })
        when (tab) {
            0 -> TrackList(tracks, viewModel)
            1 -> GroupList(tracks.groupBy { it.album }, viewModel)
            2 -> GroupList(tracks.groupBy { it.artist }, viewModel)
            3 -> PlaylistsTab(viewModel)
            4 -> FoldersTab(tracks.groupBy { it.folder }, viewModel)
        }
    }
}

@Composable
private fun TrackList(
    tracks: List<DeviceTrack>,
    viewModel: PlayerViewModel,
) {
    // The visible ordering IS the queue a tap opens, so Next walks the list
    // the user is looking at rather than running out after one track.
    val queue = remember(tracks) { tracks.map(PlaybackQueue::queueTrack) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(tracks, key = { it.uri }) { t -> TrackRow(t, viewModel, queue = queue) }
        if (tracks.isEmpty()) item { Text("No music found on device.", Modifier.padding(16.dp)) }
    }
}

@Composable
private fun TrackRow(
    t: DeviceTrack,
    viewModel: PlayerViewModel,
    subtitleOverride: String? = null,
    queue: List<QueueTrack> = emptyList(),
) {
    // Analysis results (key/BPM) and user-edited metadata overrides live in
    // the library store keyed by uri; join them onto the device row.
    val overrides by viewModel.trackOverrides.collectAsState()
    val stored = overrides[t.uri]
    val title = stored?.title?.ifBlank { null } ?: t.title
    val subtitle =
        subtitleOverride
            ?: listOf(
                stored?.artist?.ifBlank { null } ?: t.artist,
                stored?.album.orEmpty(),
                stored?.genre.orEmpty(),
                dev.musicviz.analysis.KeyDetector
                    .compact(stored?.key.orEmpty()),
                stored?.takeIf { it.analyzed && it.bpm > 0f }?.let { "${it.bpm.toInt()} BPM" } ?: "",
            ).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                if (queue.isEmpty()) viewModel.playTrack(t.uri) else viewModel.playFrom(queue, t.uri)
            }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Play next") }, onClick = {
                viewModel.playNext(t.uri)
                menu = false
            })
            DropdownMenuItem(text = { Text("Add to queue") }, onClick = {
                viewModel.enqueue(t.uri)
                menu = false
            })
            DropdownMenuItem(text = { Text("Add to library list") }, onClick = {
                viewModel.importTracks(listOf(Uri.parse(t.uri)))
                menu = false
            })
            DropdownMenuItem(text = { Text("Edit track info") }, onClick = {
                editing = true
                menu = false
            })
        }
    }
    if (editing) {
        TrackInfoEditor(uri = t.uri, viewModel = viewModel, onDismiss = { editing = false })
    }
}

/** Albums/Artists/Folders share this two-level drill-in list. */
@Composable
private fun GroupList(
    groups: Map<String, List<DeviceTrack>>,
    viewModel: PlayerViewModel,
) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val sel = open
    // System back mirrors the on-screen "‹ Back": pop the drill-in level
    // before falling through to the shell's tab/exit handling.
    androidx.activity.compose.BackHandler(enabled = sel != null) { open = null }
    if (sel != null && groups.containsKey(sel)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Back", Modifier.clickable { open = null }.padding(end = 12.dp), color = accentTextColor())
                Text(sel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val queue = remember(sel, groups) { groups.getValue(sel).map(PlaybackQueue::queueTrack) }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrystalButton(compact = true, onClick = { viewModel.playAll(queue) }) { Text("Play all") }
                CrystalButton(compact = true, filled = false, onClick = {
                    viewModel.playAll(queue, shuffled = true)
                }) { Text("Shuffle") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups.getValue(sel), key = { it.uri }) { t ->
                    TrackRow(t, viewModel, subtitleOverride = t.album, queue = queue)
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(groups.keys.sorted()) { g ->
                Row(
                    Modifier.fillMaxWidth().clickable { open = g }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(g.ifEmpty { "(no name)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${groups.getValue(g).size} tracks", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(viewModel: PlayerViewModel) {
    val library by viewModel.library.collectAsState()
    var expanded by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize()) {
        items(library.playlists) { pl ->
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = if (expanded == pl.name) null else pl.name }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(pl.name)
                        Text("${pl.trackUris.size} tracks", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        renaming = pl.name
                        renameText = pl.name
                    }) { Icon(Icons.Filled.Edit, "Rename") }
                    IconButton(onClick = { viewModel.playPlaylist(pl.name) }) {
                        Icon(Icons.Filled.PlayArrow, "Play")
                    }
                }
                if (expanded == pl.name) {
                    pl.trackUris.forEachIndexed { i, uri ->
                        val t = library.tracks.firstOrNull { it.uri == uri }
                        Row(
                            Modifier.fillMaxWidth().padding(start = 28.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                t?.title ?: "Track ${i + 1}",
                                Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            IconButton(
                                onClick = { viewModel.moveMusicPlaylistTrack(pl.name, i, i - 1) },
                                enabled = i > 0,
                            ) { Icon(Icons.Filled.KeyboardArrowUp, "Up") }
                            IconButton(
                                onClick = { viewModel.moveMusicPlaylistTrack(pl.name, i, i + 1) },
                                enabled = i < pl.trackUris.size - 1,
                            ) { Icon(Icons.Filled.KeyboardArrowDown, "Down") }
                        }
                    }
                }
            }
        }
        if (library.playlists.isEmpty()) {
            item { Text("No playlists yet — build a queue in Now Playing and save it.", Modifier.padding(16.dp)) }
        }
    }
    renaming?.let { old ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = {
                CrystalButton(onClick = {
                    viewModel.renameMusicPlaylist(old, renameText)
                    renaming = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FoldersTab(
    folders: Map<String, List<DeviceTrack>>,
    viewModel: PlayerViewModel,
) {
    val roots by viewModel.mediaRoots.collectAsState()
    val scanning by viewModel.libraryScanning.collectAsState()
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) viewModel.importFolder(uri)
        }
    Column {
        Text(
            "Library folders",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleSmall,
        )
        roots.sorted().forEach { root ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            "Device folders · Google Drive coming soon",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        GroupList(
            folders.entries
                .groupBy({ (k, _) -> k.substringAfterLast('/').ifEmpty { k } }, { it.value })
                .mapValues { (_, lists) -> lists.flatten() },
            viewModel,
        )
    }
}
