package dev.geode.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val COMMON_GENRES =
    listOf("Electronic", "Rock", "Pop", "Hip-Hop", "Jazz", "Classical", "Ambient", "Other")

/**
 * Per-track metadata editor. Prefills from the stored app-side override when
 * one exists, else from the file's embedded tags, and saves through
 * [PlayerViewModel.saveTrackInfo] — edits live only in the app's own store;
 * audio files are never touched.
 */
@Composable
fun TrackInfoEditor(
    uri: String,
    viewModel: PlayerViewModel,
    onDismiss: () -> Unit,
) {
    var loaded by remember(uri) { mutableStateOf<LibraryTrack?>(null) }
    LaunchedEffect(uri) { loaded = viewModel.trackInfoFor(uri) }
    // Hold the dialog until the prefill resolves (retriever runs on IO).
    val initial = loaded ?: return

    var title by remember(initial) { mutableStateOf(initial.title) }
    var artist by remember(initial) { mutableStateOf(initial.artist) }
    var album by remember(initial) { mutableStateOf(initial.album) }
    var genre by remember(initial) { mutableStateOf(initial.genre) }
    var year by remember(initial) { mutableStateOf(if (initial.year > 0) initial.year.toString() else "") }
    var trackNo by remember(initial) { mutableStateOf(if (initial.trackNo > 0) initial.trackNo.toString() else "") }
    var comment by remember(initial) { mutableStateOf(initial.comment) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track info") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text("Album") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text("Genre") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    COMMON_GENRES.forEach { g ->
                        FilterChip(
                            selected = genre.equals(g, ignoreCase = true),
                            onClick = { genre = g },
                            label = { Text(g) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = year,
                        onValueChange = { v -> year = v.filter { it.isDigit() }.take(4) },
                        label = { Text("Year") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = trackNo,
                        onValueChange = { v -> trackNo = v.filter { it.isDigit() }.take(3) },
                        label = { Text("Track #") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Comment / info") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Edits are stored in the app only — audio files are not modified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.saveTrackInfo(
                    uri = uri,
                    title = title.trim().ifBlank { initial.title },
                    artist = artist.trim(),
                    album = album.trim(),
                    genre = genre.trim(),
                    year = year.toIntOrNull() ?: 0,
                    trackNo = trackNo.toIntOrNull() ?: 0,
                    comment = comment.trim(),
                )
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
