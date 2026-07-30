package dev.musicviz.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

private val COMMON_GENRES =
    listOf("Electronic", "Rock", "Pop", "Hip-Hop", "Jazz", "Classical", "Ambient", "Other")

/**
 * Per-track metadata editor, styled as the mockups' "EDIT TRACK INFO" glass
 * sheet: tracked-caps field labels over glass inputs, genre chips, and
 * Cancel / Save Changes pill buttons. Prefills from the stored app-side
 * override when one exists, else from the file's embedded tags, and saves
 * through [PlayerViewModel.saveTrackInfo] — edits live only in the app's own
 * store; audio files are never touched.
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

    Dialog(onDismissRequest = onDismiss) {
        val cs = MaterialTheme.colorScheme
        Column(
            Modifier
                .fillMaxWidth()
                .crystalPanel(0.97f, cs.surface, cs.primary, corner = 24.dp, glowStrength = 0.9f)
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CrystalOverline("Edit Track Info", Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close") }
            }
            CrystalTextField(value = title, onValueChange = { title = it }, label = "Title")
            CrystalTextField(value = artist, onValueChange = { artist = it }, label = "Artist")
            CrystalTextField(value = album, onValueChange = { album = it }, label = "Album")
            CrystalTextField(value = genre, onValueChange = { genre = it }, label = "Genre / Tags")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                COMMON_GENRES.forEach { g ->
                    CrystalChip(g, selected = genre.equals(g, ignoreCase = true), onClick = { genre = g })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrystalTextField(
                    value = year,
                    onValueChange = { v -> year = v.filter { it.isDigit() }.take(4) },
                    label = "Year",
                    modifier = Modifier.weight(1f),
                )
                CrystalTextField(
                    value = trackNo,
                    onValueChange = { v -> trackNo = v.filter { it.isDigit() }.take(3) },
                    label = "Track #",
                    modifier = Modifier.weight(1f),
                )
            }
            CrystalTextField(
                value = comment,
                onValueChange = { comment = it },
                label = "Notes",
                placeholder = "Optional notes…",
                singleLine = false,
                minLines = 3,
            )
            Text(
                "Edits are stored in the app only — audio files are not modified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CrystalButton("Cancel", kind = CrystalButtonKind.GHOST, onClick = onDismiss)
                Spacer(Modifier.weight(1f))
                CrystalButton("Save Changes", onClick = {
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
                })
            }
        }
    }
}
