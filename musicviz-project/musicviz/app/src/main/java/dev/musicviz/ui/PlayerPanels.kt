package dev.musicviz.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
            Color.White.copy(alpha = 0.85f),
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
    LaunchedEffect(current) {
        if (current >= 0) {
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
                            Modifier.clickable { onSeek(line.timeMs) }
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
                color =
                    when {
                        active -> accentTextColor()
                        !lyrics.synced -> Color.White.copy(alpha = 0.85f)
                        index < current -> Color.White.copy(alpha = 0.35f)
                        else -> Color.White.copy(alpha = 0.65f)
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

/**
 * What is coming up, and the ability to change it.
 *
 * A queue you can only watch is a list; the point of showing it is being able
 * to pull a track forward or drop one you have gone off. Reordering is two
 * buttons rather than a drag: a drag inside a list that is itself inside a
 * full-screen gesture surface fights the canvas gestures for the same pointer,
 * and a button never picks the wrong one.
 */
@Composable
fun QueuePanel(
    queue: QueueUiState,
    favourites: Set<String>,
    onPlayIndex: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
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
    val listState = rememberLazyListState()
    LaunchedEffect(queue.index) {
        listState.animateScrollToItem(queue.index.coerceIn(0, queue.tracks.lastIndex))
    }
    LazyColumn(
        modifier,
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        itemsIndexed(queue.tracks, key = { i, t -> "$i:${t.uri}" }) { index, track ->
            val playing = index == queue.index
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPlayIndex(index) }
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArtwork(track.uri, Modifier.size(40.dp), corner = 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (playing) accentTextColor() else Color.White,
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
                    Icon(Icons.Filled.Close, "Remove from queue", Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Shared backdrop for the lyrics and queue panels over the live canvas. */
@Composable
fun PlayerPanelSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .glassPanel(0.55f, MaterialTheme.colorScheme.surface, corner = 20.dp, glow = MaterialTheme.colorScheme.primary),
    ) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}
