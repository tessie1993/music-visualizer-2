package dev.geode.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.geode.ui.theme.StoneIcon
import dev.geode.ui.theme.StoneIconArt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The seek bar, drawn as the track's own loudness.
 *
 * The offline analyzer already produces a per-frame RMS curve for the visuals,
 * so this is a reduction of numbers the app computed anyway - the intro, the
 * drop and the outro are where you can see them, and dragging lands on a
 * section you can recognise rather than on a percentage. Tracks that have not
 * been analysed fall back to a plain bar, because a seek bar that appears only
 * sometimes would be worse than one that is occasionally flat.
 *
 * Dragging updates a local position and only commits on release: seeking on
 * every pointer event would re-prepare the decoder dozens of times across one
 * drag, and the beat tracker resets on every seek.
 */
@Composable
fun WaveformSeekBar(
    waveform: FloatArray?,
    positionMs: Long,
    durationMs: Long,
    loopStartMs: Long?,
    loopEndMs: Long?,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    val played =
        if (dragFraction >= 0f) {
            dragFraction
        } else if (durationMs > 0) {
            (positionMs / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    val primary = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val loopTint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.28f)
    // onSurface rather than white: follows the theme (dark playhead on the
    // light stones) and any font colour override.
    val playhead = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
    Canvas(
        modifier
            .pointerInput(durationMs) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                }
            }.pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        if (dragFraction >= 0f) onSeek(dragFraction)
                        dragFraction = -1f
                    },
                    onDragCancel = { dragFraction = -1f },
                ) { change, dragAmount ->
                    change.consume()
                    dragFraction = (dragFraction + dragAmount / size.width.toFloat()).coerceIn(0f, 1f)
                }
            },
    ) {
        // The looped section, painted under everything so the bars stay
        // readable through it.
        if (loopStartMs != null && durationMs > 0) {
            val from = (loopStartMs / durationMs.toFloat()).coerceIn(0f, 1f) * size.width
            val to = ((loopEndMs ?: durationMs) / durationMs.toFloat()).coerceIn(0f, 1f) * size.width
            drawRect(loopTint, topLeft = Offset(from, 0f), size = Size((to - from).coerceAtLeast(2f), size.height))
        }
        if (waveform == null || waveform.isEmpty()) {
            val h = 3.dp.toPx()
            val y = size.height / 2f - h / 2f
            drawRoundRect(idle, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2f))
            drawRoundRect(primary, Offset(0f, y), Size(size.width * played, h), CornerRadius(h / 2f))
        } else {
            val n = waveform.size
            val slot = size.width / n
            val barWidth = (slot * 0.62f).coerceAtLeast(1f)
            val playedX = size.width * played
            for (i in 0 until n) {
                // Even a silent bucket gets a visible stub, so the bar reads as
                // a control across its whole width.
                val h = (size.height * (0.08f + 0.92f * waveform[i])).coerceAtLeast(2f)
                val x = i * slot + (slot - barWidth) / 2f
                drawRoundRect(
                    color = if (x + barWidth / 2f <= playedX) primary else idle,
                    topLeft = Offset(x, (size.height - h) / 2f),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
        // Playhead, so the exact position is readable during a drag over a
        // stretch of similar-looking bars.
        val x = (size.width * played).coerceIn(1f, size.width - 1f)
        drawRoundRect(
            playhead,
            topLeft = Offset(x - 1.dp.toPx(), 0f),
            size = Size(2.dp.toPx(), size.height),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
    }
}

/**
 * The words, following the music when they are timed.
 *
 * Auto-scroll keeps the current line a third of the way down rather than
 * centred: the eye reads ahead, and a line pinned to the middle means the next
 * one is always at the edge of the panel.
 */
@Composable
fun LyricsPanel(
    lyrics: Lyrics?,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lyrics == null) {
        Column(
            modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CrystalOverline("No lyrics")
            Text(
                "Drop an .lrc file next to the track — same name, .lrc extension — and it shows up " +
                    "here, timed and following the music. Words written into the file's own tags are " +
                    "read too. Nothing is fetched: the app has no network access.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val current = lyrics.indexAt(positionMs)
    val listState = rememberLazyListState()
    val follows = rememberFollowsPlayback(listState)
    LaunchedEffect(current, follows.value) {
        if (follows.value && current >= 0) {
            listState.animateScrollToItem(current.coerceAtLeast(0), scrollOffset = -SCROLL_LEAD_PX)
        }
    }
    LazyColumn(
        modifier,
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val active = index == current
            Text(
                line.text,
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (lyrics.synced) {
                            Modifier.clickable {
                                // Jumping to a line is an explicit "take me
                                // there": resume following immediately.
                                follows.value = true
                                onSeek(line.timeMs)
                            }
                        } else {
                            Modifier
                        },
                    ),
                style =
                    if (active) {
                        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                // onSurface rather than white so the words follow the theme
                // (dark text on the light stones) and any font colour
                // override; only the alpha ranks the lines.
                color =
                    when {
                        active -> accentTextColor()
                        !lyrics.synced -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        index < current -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    },
            )
        }
        item {
            Text(
                if (lyrics.synced) {
                    "Timed lyrics from ${lyrics.source}. Tap a line to jump there."
                } else {
                    "Untimed lyrics from ${lyrics.source}."
                },
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How far above centre the active lyric line rides, in pixels. */
private const val SCROLL_LEAD_PX = 160

/** How long after the user lets go of a following list before it resumes chasing playback. */
private const val FOLLOW_RESUME_DELAY_MS = 5_000L

/**
 * Whether a list that follows playback should be following right now.
 *
 * A follow scroll that fires while the user is reading back through the lyrics
 * or browsing the queue yanks the list out from under them, so following is
 * suspended the moment the user grabs the list and resumes after
 * [FOLLOW_RESUME_DELAY_MS] of idle. Invariant: programmatic scrolls must not
 * count as user scrolls — the auto-scroller would suspend itself — which is
 * why user intent is read from the list's drag interactions: only pointer
 * gestures emit those, `animateScrollToItem` never does.
 *
 * The returned state is writable so a tap that expresses "take me to the
 * playing item" can resume following immediately.
 */
@Composable
private fun rememberFollowsPlayback(listState: LazyListState): MutableState<Boolean> {
    val follows = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collectLatest { interaction ->
            if (interaction is DragInteraction.Start) {
                follows.value = false
            } else {
                // Drag ended or was cancelled: wait out the idle window before
                // resuming. collectLatest restarts the wait if the user grabs
                // the list again, and any fling decays well inside it.
                delay(FOLLOW_RESUME_DELAY_MS)
                follows.value = true
            }
        }
    }
    return follows
}

/**
 * What is coming up, and the ability to change it.
 *
 * A queue you can only watch is a list; the point of showing it is being able
 * to pull a track forward or drop one you have gone off. Reordering is two
 * buttons rather than a drag: a drag inside a list that is itself inside a
 * full-screen gesture surface fights the canvas gestures for the same pointer,
 * and a button never picks the wrong one.
 *
 * A queue worth arranging is worth keeping: "Save as playlist" in the header
 * writes the current order through the same create/add calls the Playlists
 * tab uses. That action needs the view model, not one more callback - the
 * caller hands this panel playback state only - so the defaulted parameter
 * resolves the activity-scoped [PlayerViewModel], the same instance every
 * screen already shares.
 */
@Composable
fun QueuePanel(
    queue: QueueUiState,
    favourites: Set<String>,
    onPlayIndex: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = viewModel(),
) {
    if (queue.tracks.isEmpty()) {
        Column(modifier.padding(24.dp)) {
            CrystalOverline("Queue")
            Text(
                "Nothing queued. Playing anything from a list — the library, a shelf on Home, a " +
                    "search result — queues that whole list.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val library by viewModel.library.collectAsState()
    var saving by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val follows = rememberFollowsPlayback(listState)
    LaunchedEffect(queue.index, follows.value) {
        if (follows.value) {
            listState.animateScrollToItem(queue.index.coerceIn(0, queue.tracks.lastIndex))
        }
    }
    val keys = remember(queue.tracks) { queueRowKeys(queue.tracks) }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CrystalOverline("Queue", Modifier.weight(1f))
            CrystalButton(compact = true, filled = false, onClick = { saving = true }) { Text("Save as playlist") }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            itemsIndexed(queue.tracks, key = { i, _ -> keys[i] }) { index, track ->
                val playing = index == queue.index
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            // Picking a track is an explicit "play from here":
                            // resume following immediately.
                            follows.value = true
                            onPlayIndex(index)
                        }.padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TrackArtwork(track.uri, Modifier.size(40.dp), corner = 8.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (playing) accentTextColor() else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                track.artist.takeIf { it.isNotBlank() },
                                "★".takeIf { track.uri in favourites },
                            ).joinToString("  ").ifBlank { "Unknown artist" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (index > 0) {
                        IconButton(onClick = { onMoveUp(index) }) {
                            Icon(Icons.Filled.KeyboardArrowUp, "Move up", Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = { onRemove(index) }) {
                        StoneIconArt(StoneIcon.CLOSE, "Remove from queue", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    if (saving) {
        PlaylistNameDialog(
            title = "Save queue as playlist",
            confirmLabel = "Save",
            taken = library.playlists.map { it.name }.toSet(),
            onName = { name ->
                viewModel.createMusicPlaylist(name)
                // In queue order; addTrackToPlaylist skips a uri the playlist
                // already holds, so a track queued twice is saved once.
                queue.tracks.forEach { viewModel.addTrackToPlaylist(name, it.uri) }
            },
            onDismiss = { saving = false },
        )
    }
}

/**
 * Stable identity for the queue's rows. The queue models an entry as bare
 * uri+metadata with no id of its own, and the same track can be enqueued
 * twice, so a row's key is its uri plus which occurrence of that uri it is.
 * The key used to mix the INDEX in, which names the slot rather than the
 * entry: removing row 0 renamed every row after it, so remembered item state
 * and removal animations belonged to positions, not tracks. Occurrence
 * counting keeps a key through removals and reorders of OTHER entries; only
 * literal duplicates of one track trade keys, and they are interchangeable.
 */
internal fun queueRowKeys(tracks: List<QueueTrack>): List<String> {
    val seen = HashMap<String, Int>()
    return tracks.map { t ->
        val n = seen[t.uri] ?: 0
        seen[t.uri] = n + 1
        if (n == 0) t.uri else "${t.uri}#$n"
    }
}

/**
 * Whether [name] may become a NEW playlist among [existing] names. Blank is
 * refused for the same reason [PlayerViewModel.createMusicPlaylist] refuses
 * it; a taken name has to be refused HERE because [MusicPlaylistStore.save]
 * is an overwrite - accepting it would silently replace that playlist's
 * tracks, where rename gets to refuse inside the store. Trimmed before
 * comparing, exactly as the create call trims before saving.
 */
internal fun playlistNameAccepted(
    name: String,
    existing: Collection<String>,
): Boolean = name.isNotBlank() && name.trim() !in existing

/**
 * The naming dialog behind every "make a playlist" affordance - the Playlists
 * tab, the queue panel's save, and the track menus' "New playlist…" - shaped
 * like the Playlists tab's rename dialog. Confirm stays disabled while the
 * name is unusable (see [playlistNameAccepted]) rather than failing after OK.
 */
@Composable
internal fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    taken: Set<String>,
    onName: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            CrystalButton(
                enabled = playlistNameAccepted(name, taken),
                onClick = {
                    onName(name.trim())
                    onDismiss()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shared backdrop for the lyrics and queue panels over the live canvas. */
@Composable
fun PlayerPanelSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .crystalPanel(
                0.55f,
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.primary,
                corner = 20.dp,
                glowStrength = 0.8f,
            ),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}
