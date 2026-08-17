package dev.geode.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.geode.R
import dev.geode.data.MusicPlaylist
import dev.geode.ui.theme.StoneIcon
import dev.geode.ui.theme.StoneIconArt
import kotlin.math.roundToInt

@Composable
fun LibraryScreen(
    viewModel: PlayerViewModel,
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
    val tabs =
        listOf(
            stringResource(R.string.library_tab_tracks),
            stringResource(R.string.library_tab_albums),
            stringResource(R.string.library_tab_artists),
            stringResource(R.string.library_tab_playlists),
            stringResource(R.string.library_tab_folders),
        )

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CrystalOverline(stringResource(R.string.app_name))
                GlowTitle(stringResource(R.string.nav_library))
            }
            IconButton(onClick = onOpenSearch) { StoneIconArt(StoneIcon.SEARCH, stringResource(R.string.action_search)) }
        }
        if (!granted) {
            // Android stops delivering the system dialog after two refusals, so
            // re-launching the request is the "dead button" failure this app
            // prides itself on fixing elsewhere: the user taps and nothing at
            // all happens, forever. shouldShowRequestPermissionRationale is
            // false both before the first ask and after a permanent denial, so
            // asking-once is what distinguishes them.
            val activity = LocalActivity.current
            var asked by rememberSaveable { mutableStateOf(false) }
            val canAskAgain =
                activity == null ||
                    !asked ||
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(
                        if (canAskAgain) {
                            R.string.library_permission_rationale
                        } else {
                            R.string.library_permission_denied_forever
                        },
                    ),
                )
                if (canAskAgain) {
                    CrystalButton(onClick = {
                        asked = true
                        permLauncher.launch(permission)
                    }) { Text(stringResource(R.string.library_permission_allow)) }
                } else {
                    val context = LocalContext.current
                    CrystalButton(
                        onClick = { context.openAppSettings() },
                    ) { Text(stringResource(R.string.library_permission_open_settings)) }
                }
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
        if (tracks.isEmpty()) item { Text(stringResource(R.string.library_no_music), Modifier.padding(16.dp)) }
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
    // Analysis is reported by the chip below, not as another subtitle field:
    // a well-tagged track fills that one line with artist/album/genre and
    // ellipsises the tail away, which is exactly where the BPM used to sit.
    val analyzed = stored?.takeIf { it.analyzed }
    val subtitle =
        subtitleOverride
            ?: listOf(
                stored?.artist?.ifBlank { null } ?: t.artist,
                stored?.album.orEmpty(),
                stored?.genre.orEmpty(),
                dev.geode.analysis.KeyDetector
                    .compact(stored?.key.orEmpty()),
            ).filter { it.isNotBlank() }.joinToString(" \u00b7 ")
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var addingToPlaylist by remember { mutableStateOf(false) }
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
        if (analyzed != null) AnalyzedBadge(analyzed.bpm, Modifier.padding(start = 8.dp))
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.action_play_next)) }, onClick = {
                viewModel.playNext(t.uri)
                menu = false
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_add_to_queue)) }, onClick = {
                viewModel.enqueue(t.uri)
                menu = false
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_add_to_playlist)) }, onClick = {
                addingToPlaylist = true
                menu = false
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_add_to_library_list)) }, onClick = {
                viewModel.importTracks(listOf(Uri.parse(t.uri)))
                menu = false
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_edit_track_info)) }, onClick = {
                editing = true
                menu = false
            })
        }
    }
    if (editing) {
        TrackInfoEditor(uri = t.uri, viewModel = viewModel, onDismiss = { editing = false })
    }
    if (addingToPlaylist) {
        AddToPlaylistDialog(uri = t.uri, viewModel = viewModel, onDismiss = { addingToPlaylist = false })
    }
}

/**
 * Where a track row's "Add to playlist" lands: the existing playlists by
 * name, plus a "New playlist…" branch into the shared naming dialog. Picking
 * an existing playlist appends through [MusicPlaylistStore.addTrack], which
 * skips a uri already present - adding a track twice is a no-op, not a
 * duplicate entry.
 */
@Composable
private fun AddToPlaylistDialog(
    uri: String,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    val library by viewModel.library.collectAsState()
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.action_create),
            taken = library.playlists.map { it.name }.toSet(),
            onName = { name ->
                viewModel.createMusicPlaylist(name)
                viewModel.addTrackToPlaylist(name, uri)
            },
            onDismiss = onDismiss,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_add_to_playlist)) },
        text = {
            // Scrolls on its own: the dialog caps its height well before a
            // long-standing user's playlist collection runs out.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                library.playlists.forEach { pl ->
                    Text(
                        pl.name,
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.addTrackToPlaylist(pl.name, uri)
                                onDismiss()
                            }.padding(vertical = 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.playlist_new_branch),
                    Modifier.fillMaxWidth().clickable { naming = true }.padding(vertical = 10.dp),
                    color = accentTextColor(),
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * The "this track has been analysed" marker: a crystal chip carrying the gem
 * and, when analysis found one, the BPM.
 *
 * It has to be a chip rather than one more `·`-separated subtitle field, and
 * it has to live outside the weighted title column. As a subtitle field it
 * was the LAST field, on a single ellipsised line, so every well-tagged track
 * hid the one fact the user cannot read off the file itself — whether we have
 * analysed it. Here the row gives the chip its intrinsic width first and the
 * title truncates instead.
 */
@Composable
private fun AnalyzedBadge(
    bpm: Float,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = crystalShardShape(8.dp, 3.dp)
    val label = if (bpm > 0f) "${bpm.toInt()} BPM" else ""
    val spoken =
        if (label.isEmpty()) {
            stringResource(R.string.analysed_badge)
        } else {
            stringResource(R.string.analysed_badge_with_tempo, label)
        }
    Row(
        modifier
            .clip(shape)
            .background(cs.primary.copy(alpha = 0.16f))
            .border(1.dp, cs.primary.copy(alpha = 0.45f), shape)
            .padding(horizontal = 7.dp, vertical = 3.dp)
            // The gem is decoration a screen reader cannot see, so the whole
            // chip announces itself as one phrase.
            .semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrystalGem(cs.primary, size = 5.dp)
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = accentTextColor(), maxLines = 1)
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
    // System back mirrors the on-screen "‹ Back": pop the drill-in level
    // before falling through to the shell's tab/exit handling.
    androidx.activity.compose.BackHandler(enabled = sel != null) { open = null }
    if (sel != null && groups.containsKey(sel)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.library_back),
                    Modifier.clickable { open = null }.padding(end = 12.dp),
                    color = accentTextColor(),
                )
                Text(sel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val queue = remember(sel, groups) { groups.getValue(sel).map(PlaybackQueue::queueTrack) }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrystalButton(compact = true, onClick = { viewModel.playAll(queue) }) { Text(stringResource(R.string.library_play_all)) }
                CrystalButton(compact = true, filled = false, onClick = {
                    viewModel.playAll(queue, shuffled = true)
                }) { Text(stringResource(R.string.action_shuffle)) }
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
                        Text(
                            g.ifEmpty { stringResource(R.string.library_group_unnamed) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.track_count,
                                groups.getValue(g).size,
                                groups.getValue(g).size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
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
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    Column {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            CrystalButton(compact = true, filled = false, onClick = { creating = true }) { Text(stringResource(R.string.playlist_new)) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(library.playlists, key = { it.name }) { pl ->
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
                            Text(
                                pluralStringResource(
                                    R.plurals.track_count,
                                    pl.trackUris.size,
                                    pl.trackUris.size,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = {
                            renaming = pl.name
                            renameText = pl.name
                        }) { StoneIconArt(StoneIcon.EDIT, stringResource(R.string.action_rename)) }
                        IconButton(onClick = { viewModel.playPlaylist(pl.name) }) {
                            StoneIconArt(StoneIcon.PLAY, stringResource(R.string.action_play))
                        }
                        // Behind a confirm: this row's other taps are all
                        // recoverable, deleting a playlist is not.
                        IconButton(onClick = { deleting = pl.name }) {
                            StoneIconArt(StoneIcon.CLOSE, stringResource(R.string.playlist_delete_title))
                        }
                    }
                    if (expanded == pl.name) {
                        PlaylistTracks(pl, library.tracks, viewModel)
                    }
                }
            }
            if (library.playlists.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.playlist_none_yet),
                        Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
    if (creating) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.action_create),
            taken = library.playlists.map { it.name }.toSet(),
            onName = viewModel::createMusicPlaylist,
            onDismiss = { creating = false },
        )
    }
    deleting?.let { doomed ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.playlist_delete_title)) },
            text = { Text(stringResource(R.string.playlist_delete_body, doomed)) },
            confirmButton = {
                CrystalButton(onClick = {
                    viewModel.deleteMusicPlaylist(doomed)
                    deleting = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    renaming?.let { old ->
        // MusicPlaylistStore.rename refuses a blank or already-taken name by
        // returning false, and a dialog that closed on any confirm was a
        // rename that silently never happened. Gated here on the same
        // playlistNameAccepted check the create dialog uses, minus `old`
        // itself so renaming to the name already being edited is allowed.
        val proposed = renameText.trim()
        val otherNames = library.playlists.map { it.name }.filterNot { it == old }.toSet()
        val nameOk = playlistNameAccepted(proposed, otherNames)
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.playlist_rename_title)) },
            text = {
                Column {
                    OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
                    if (!nameOk) {
                        Text(
                            if (proposed.isEmpty()) {
                                stringResource(R.string.playlist_name_required)
                            } else {
                                stringResource(R.string.playlist_name_taken, proposed)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                CrystalButton(enabled = nameOk, onClick = {
                    viewModel.renameMusicPlaylist(old, proposed)
                    renaming = null
                }) { Text(stringResource(R.string.action_rename)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Where a row picked up at [from] is dropped after the finger has carried it
 * [offsetPx] down (negative is up) a list of [count] rows, each [rowHeightPx]
 * tall. Rows in this list are uniform, so the drop is just how many whole
 * rows the finger travelled: rounded, so a row changes places once it has
 * passed the halfway mark of its neighbour, and clamped, so dragging beyond
 * either end parks there rather than wrapping around.
 */
internal fun playlistDropIndex(
    from: Int,
    offsetPx: Float,
    rowHeightPx: Int,
    count: Int,
): Int {
    if (count <= 0) return from
    // Before the first layout pass we do not know the row height; refusing to
    // move is the only safe answer, dividing by it would not be.
    if (rowHeightPx <= 0) return from.coerceIn(0, count - 1)
    return (from + (offsetPx / rowHeightPx).roundToInt()).coerceIn(0, count - 1)
}

/**
 * How far the row at [index] slides, in whole rows, while a row picked up at
 * [from] hovers over [to]: everything the dragged row has passed shuffles one
 * place the other way to open the gap it will drop into. The dragged row
 * itself is 0 — it follows the finger, not this offset.
 */
internal fun playlistRowShift(
    index: Int,
    from: Int,
    to: Int,
): Int =
    when {
        index == from -> 0
        from < to && index in (from + 1)..to -> -1
        from > to && index in to until from -> 1
        else -> 0
    }

/**
 * The expanded playlist's tracks, reordered by long-pressing a row and
 * dragging it where it belongs. Sending track 50 to the top used to be 49
 * taps of the up arrow; [MusicPlaylistStore.move] has always accepted an
 * arbitrary from/to, so only the gesture was missing.
 *
 * No reorderable-list library is on the classpath and this does not warrant
 * adding one: the rows are a plain uniform-height Column, which reduces the
 * whole gesture to a drag distance in rows (see [playlistDropIndex]) plus an
 * animated offset on the rows it passes (see [playlistRowShift]). The drag
 * starts on a LONG press specifically so the enclosing LazyColumn keeps its
 * ordinary scroll, and the pick-up is anywhere on the row rather than on the
 * handle alone, which is far more forgiving on a row this short.
 *
 * The up/down buttons stay. They do not compete for the gesture — they only
 * claim their own taps — and they are the only way through this list for
 * anyone using a switch, a screen reader, or one hand on a moving train.
 *
 * A drag does not scroll the enclosing list, so a destination off-screen
 * needs the list scrolled to it first; the rows are the tracks of ONE
 * expanded playlist, so that is a screenful or two, not the whole library.
 */
@Composable
private fun PlaylistTracks(
    playlist: MusicPlaylist,
    tracks: List<LibraryTrack>,
    viewModel: PlayerViewModel,
) {
    val count = playlist.trackUris.size
    // Keyed on the name so collapsing one playlist and opening another cannot
    // leave a half-finished drag pointing at a row that is no longer there.
    var dragFrom by remember(playlist.name) { mutableIntStateOf(-1) }
    var dragOffset by remember(playlist.name) { mutableFloatStateOf(0f) }
    var rowHeight by remember(playlist.name) { mutableIntStateOf(0) }
    val dropIndex = playlistDropIndex(dragFrom, dragOffset, rowHeight, count)
    val liftTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    playlist.trackUris.forEachIndexed { i, uri ->
        val t = tracks.firstOrNull { it.uri == uri }
        val dragging = i == dragFrom
        val shift by animateFloatAsState(
            if (dragFrom < 0) 0f else (playlistRowShift(i, dragFrom, dropIndex) * rowHeight).toFloat(),
            label = "playlistRowShift",
        )
        Row(
            Modifier
                .fillMaxWidth()
                // The lifted row draws over its neighbours, and reads the
                // offsets inside the layer block so following the finger costs
                // a redraw rather than a recomposition of the whole list.
                .zIndex(if (dragging) 1f else 0f)
                .graphicsLayer { translationY = if (dragging) dragOffset else shift }
                .then(if (dragging) Modifier.background(liftTint) else Modifier)
                .onSizeChanged { rowHeight = it.height }
                .pointerInput(playlist.name, i, count) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragFrom = i
                            dragOffset = 0f
                        },
                        onDragEnd = {
                            // Recomputed from this row's own index: the state
                            // read here must not depend on which row recomposed.
                            val to = playlistDropIndex(i, dragOffset, rowHeight, count)
                            if (to != i) viewModel.moveMusicPlaylistTrack(playlist.name, i, to)
                            dragFrom = -1
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            dragFrom = -1
                            dragOffset = 0f
                        },
                    ) { change, drag ->
                        change.consume()
                        dragOffset += drag.y
                    }
                }.padding(start = 28.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Affordance only: the gesture is on the whole row, so announcing
            // this as a control would promise a target that is not special.
            Icon(
                Icons.Filled.DragHandle,
                null,
                Modifier.size(18.dp).padding(end = 2.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Text(
                t?.title ?: stringResource(R.string.playlist_untitled_track, i + 1),
                Modifier.weight(1f).padding(start = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(
                onClick = { viewModel.moveMusicPlaylistTrack(playlist.name, i, i - 1) },
                enabled = i > 0,
            ) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.action_up)) }
            IconButton(
                onClick = { viewModel.moveMusicPlaylistTrack(playlist.name, i, i + 1) },
                enabled = i < count - 1,
            ) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.action_down)) }
            IconButton(onClick = { viewModel.removeTrackFromPlaylist(playlist.name, uri) }) {
                StoneIconArt(StoneIcon.CLOSE, stringResource(R.string.action_remove_from_playlist), Modifier.size(18.dp))
            }
        }
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
            stringResource(R.string.folders_library),
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
                    StoneIconArt(StoneIcon.CLOSE, stringResource(R.string.folders_remove))
                }
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CrystalButton(
                compact = true,
                filled = false,
                onClick = { folderPicker.launch(null) },
            ) { Text(stringResource(R.string.folders_add)) }
            CrystalButton(
                compact = true,
                filled = false,
                onClick = viewModel::rescanMediaRoots,
                enabled = roots.isNotEmpty() && !scanning,
            ) {
                Text(stringResource(if (scanning) R.string.folders_scanning else R.string.folders_rescan))
            }
        }
        // Device folders is the whole story, not the first half of one: the
        // app holds no INTERNET permission (AndroidManifest.xml), so a cloud
        // source is not a feature that has not been built yet - it is one the
        // architecture rules out.
        Text(
            stringResource(R.string.folders_device),
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        GroupList(FolderTree.rows(folders), viewModel)
    }
}
