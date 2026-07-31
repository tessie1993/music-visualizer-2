package dev.musicviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The playback queue as an editable list: tap to jump, arrows to reorder,
 * X to drop a track, and a way to keep the whole thing as a playlist.
 *
 * The queue lived only inside the player until now — [PlayerViewModel.queueTitles]
 * and [PlayerViewModel.playQueueIndex] existed with nothing rendering them, so a
 * queue assembled with "play next" could be inspected only by listening to it.
 *
 * Reordering is arrow buttons rather than drag-and-drop on purpose: the rows
 * sit in a scrolling list inside an overlay, where a long-press drag competes
 * with both the list's scroll and the overlay's dismiss gesture.
 */
@Composable
fun QueueSheet(
    viewModel: PlayerViewModel,
    onClose: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    // Re-read the titles whenever the queue's shape or position changes; the
    // player is the source of truth and the snapshot is what reports it.
    val titles =
        remember(state.queueSize, state.queueIndex, state.title) {
            viewModel.queueTitles()
        }
    var naming by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    CrystalOverline("MusicViz")
                    GlowTitle("Queue")
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close queue") }
            }

            if (titles.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing queued yet.\nPlay a track, or use “Play next” from the library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CrystalButton(onClick = { naming = true }, compact = true, filled = false) {
                    Text("Save as playlist")
                }
                CrystalButton(
                    onClick = {
                        viewModel.clearQueue()
                        onClose()
                    },
                    compact = true,
                    filled = false,
                ) { Text("Clear") }
            }

            LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
                itemsIndexed(titles) { index, title ->
                    QueueRow(
                        title = title,
                        playing = index == state.queueIndex,
                        canMoveUp = index > 0,
                        canMoveDown = index < titles.lastIndex,
                        onPlay = { viewModel.playQueueIndex(index) },
                        onUp = { viewModel.moveQueueItem(index, index - 1) },
                        onDown = { viewModel.moveQueueItem(index, index + 1) },
                        onRemove = { viewModel.removeQueueItem(index) },
                    )
                }
            }
        }
    }

    if (naming) {
        SaveQueueDialog(
            onDismiss = { naming = false },
            onSave = { name ->
                // Returns false on a blank or duplicate name; keep the dialog
                // open in that case so the typed name is not silently dropped.
                if (viewModel.saveQueueAsPlaylist(name)) naming = false
            },
        )
    }
}

@Composable
private fun QueueRow(
    title: String,
    playing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            null,
            Modifier.size(18.dp),
            tint =
                if (playing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
        Text(
            title,
            Modifier.weight(1f).padding(horizontal = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (playing) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onUp, enabled = canMoveUp) {
            Icon(Icons.Filled.KeyboardArrowUp, "Move up", Modifier.size(18.dp))
        }
        IconButton(onClick = onDown, enabled = canMoveDown) {
            Icon(Icons.Filled.KeyboardArrowDown, "Move down", Modifier.size(18.dp))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, "Remove from queue", Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SaveQueueDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save queue as playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Playlist name") },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
