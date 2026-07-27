package dev.musicviz.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/** One row of the device music index. */
data class DeviceTrack(
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val folder: String,
    val durationMs: Long,
)

/** Queries the whole device music index via MediaStore. */
private fun queryDeviceTracks(context: Context): List<DeviceTrack> {
    val out = mutableListOf<DeviceTrack>()
    val proj =
        arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
        )
    runCatching {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            proj,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val ti = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val ar = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val al = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val du = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val da = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(id))
                val path = c.getString(da).orEmpty()
                out +=
                    DeviceTrack(
                        uri = uri.toString(),
                        title = c.getString(ti) ?: "Unknown",
                        artist = c.getString(ar) ?: "Unknown artist",
                        album = c.getString(al) ?: "Unknown album",
                        folder = path.substringBeforeLast('/', ""),
                        durationMs = c.getLong(du),
                    )
            }
        }
    }
    return out
}

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
    // Off the main thread: a full MediaStore scan inside remember{} ran
    // synchronously during composition - jank/ANR territory on devices with
    // thousands of tracks.
    val tracks by androidx.compose.runtime.produceState(initialValue = emptyList<DeviceTrack>(), granted, reloadKey) {
        value =
            if (granted) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { queryDeviceTracks(context) }
            } else {
                emptyList()
            }
    }
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Tracks", "Albums", "Artists", "Playlists", "Folders")

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSearch) { Icon(Icons.Filled.Search, "Search") }
        }
        if (!granted) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Grant music access to browse everything on this device.")
                Button(onClick = { permLauncher.launch(permission) }) { Text("Allow music access") }
            }
            return
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
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
    LazyColumn(Modifier.fillMaxSize()) {
        items(tracks, key = { it.uri }) { t -> TrackRow(t, viewModel) }
        if (tracks.isEmpty()) item { Text("No music found on device.", Modifier.padding(16.dp)) }
    }
}

@Composable
private fun TrackRow(
    t: DeviceTrack,
    viewModel: PlayerViewModel,
    subtitleOverride: String? = null,
) {
    // Analysis results (key/BPM) live in the analysis store keyed by uri;
    // join them onto the device row when this track has been analyzed.
    val lib by viewModel.library.collectAsState()
    val analysis = lib.tracks.firstOrNull { it.uri == t.uri }
    val subtitle =
        subtitleOverride
            ?: listOf(
                t.artist,
                dev.musicviz.analysis.KeyDetector.compact(analysis?.key.orEmpty()),
                analysis?.takeIf { it.analyzed && it.bpm > 0f }?.let { "${it.bpm.toInt()} BPM" } ?: "",
            ).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { viewModel.playTrack(t.uri) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        }
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
    if (sel != null && groups.containsKey(sel)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Back", Modifier.clickable { open = null }.padding(end = 12.dp), color = MaterialTheme.colorScheme.primary)
                Text(sel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.openStringsPublic(groups.getValue(sel).map { it.uri }) }) { Text("Play all") }
                Button(onClick = { viewModel.openStringsPublic(groups.getValue(sel).map { it.uri }.shuffled()) }) { Text("Shuffle") }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups.getValue(sel), key = { it.uri }) { t -> TrackRow(t, viewModel, subtitleOverride = t.album) }
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
                Button(onClick = {
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
    Column {
        Text(
            "Device folders · Google Drive coming soon",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        GroupList(folders.mapKeys { (k, _) -> k.substringAfterLast('/').ifEmpty { k } }, viewModel)
    }
}
