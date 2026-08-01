package dev.musicviz.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.musicviz.export.ClipEdit
import dev.musicviz.export.ClipLook
import dev.musicviz.export.ExportQuality
import dev.musicviz.export.ExportRatio
import dev.musicviz.export.StudioClip
import kotlin.math.roundToInt

/**
 * The Export Studio: what happens to a render after it exists.
 *
 * The visualizer exporter makes a file and stops there, which leaves the two
 * jobs everyone actually has - cut the good bit out, and get it somewhere -
 * to a second app. This does both without leaving MusicViz.
 *
 * Built on Media3's Transformer rather than on the app's own exporter, because
 * the two answer different questions: one renders frames that do not exist
 * yet, this one re-encodes frames that do. Transformer also knows when NOT to
 * re-encode - a trim-only edit is a container rewrite, which is faster and
 * lossless.
 *
 * "Upload" is the system share sheet, deliberately. The app holds no network
 * permission and no API keys, and a share sheet reaches YouTube, Instagram,
 * TikTok, Drive and everything else the phone already knows how to post to,
 * with the account the user is already signed in to.
 */
@Composable
fun StudioScreen(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val studio by viewModel.studio.collectAsState()
    var editing by remember { mutableStateOf<StudioClip?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshStudioClips() }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.describeStudioClip(uri) { editing = it }
        }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            CrystalOverline("MusicViz")
            GlowTitle(if (editing == null) "Studio" else "Edit")
        }
        val clip = editing
        if (clip == null) {
            ClipLibrary(
                studio = studio,
                onOpen = { editing = it },
                onPick = { picker.launch(arrayOf("video/*")) },
                onShare = { context.shareVideo(it) },
            )
        } else {
            ClipEditor(
                viewModel = viewModel,
                clip = clip,
                studio = studio,
                onClose = {
                    viewModel.clearStudioResult()
                    editing = null
                },
            )
        }
    }
}

/** The clip list: what the app has rendered, newest first. */
@Composable
private fun ClipLibrary(
    studio: StudioUiState,
    onOpen: (StudioClip) -> Unit,
    onPick: () -> Unit,
    onShare: (Uri) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrystalButton(onClick = onPick) { Text("Open a video…") }
            }
        }
        if (studio.clips.isEmpty() && !studio.loading) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .crystalPanel(
                            0.32f,
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.primary,
                            corner = 20.dp,
                        ).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CrystalOverline("Nothing rendered yet")
                    Text(
                        "Render a visual from Settings › Export video and it lands here, ready to be " +
                            "cut, graded and sent somewhere. Any other video on the phone can be opened " +
                            "with the button above.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        items(studio.clips.size) { index ->
            val clip = studio.clips[index]
            Row(
                Modifier
                    .fillMaxWidth()
                    .crystalPanel(
                        0.28f,
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.primary,
                        corner = 18.dp,
                        glowStrength = 0.4f,
                    ).clickable { onOpen(clip) }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VideoFrame(clip.uri, atMs = clip.durationMs / 3, modifier = Modifier.width(96.dp).height(56.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        clip.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        clip.summary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onShare(Uri.parse(clip.uri)) }) { Text("Send") }
            }
        }
    }
}

/**
 * The editor: trim, grade, reframe, caption, render.
 *
 * The edit is one immutable value the whole screen reads and writes, which is
 * what makes "Reset" a single assignment and makes the exported result exactly
 * the thing on screen rather than a re-derivation of it.
 */
@Composable
private fun ClipEditor(
    viewModel: PlayerViewModel,
    clip: StudioClip,
    studio: StudioUiState,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var edit by remember(clip.uri) { mutableStateOf(ClipEdit()) }
    val duration = clip.durationMs.coerceAtLeast(1L)
    val outEnd = if (edit.endMs > 0) edit.endMs else duration

    androidx.activity.compose.BackHandler { onClose() }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(clip.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        clip.summary(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClose) { Text("Back") }
            }
        }

        // ---- Trim -----------------------------------------------------
        item {
            StudioSection("Cut") {
                // A filmstrip rather than a scrub bar: six keyframes across
                // the clip is enough to find the bit you meant, and it is what
                // the range handles are being dragged over.
                Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(FILMSTRIP_FRAMES) { i ->
                        VideoFrame(
                            clip.uri,
                            atMs = duration * i / FILMSTRIP_FRAMES,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            corner = 4.dp,
                        )
                    }
                }
                RangeSlider(
                    value = edit.startMs.toFloat()..outEnd.toFloat(),
                    onValueChange = { range ->
                        edit =
                            edit.copy(
                                startMs = range.start.toLong().coerceIn(0L, duration),
                                // Store the out-point as 0 when it is the end,
                                // so a clip whose duration is re-read later
                                // does not end up trimmed by a stale value.
                                endMs = if (range.endInclusive >= duration - 1) 0L else range.endInclusive.toLong(),
                            )
                    },
                    valueRange = 0f..duration.toFloat(),
                )
                Text(
                    "${clock(edit.startMs)} → ${clock(outEnd)}   ·   keeps ${clock(edit.trimmedMs(duration))}" +
                        if (edit.speed != 1f) ", renders ${clock(edit.outputMs(duration))}" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = accentTextColor(),
                )
            }
        }

        // ---- Look -----------------------------------------------------
        item {
            StudioSection("Look") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ClipLook.entries.forEach { look ->
                        StudioChip(look.label, selected = edit.look == look) {
                            edit = look.applyTo(edit).copy(look = look)
                        }
                    }
                }
                StudioSlider("Brightness", edit.brightness, -0.5f..0.5f) { edit = edit.copy(brightness = it) }
                StudioSlider("Contrast", edit.contrast, -0.6f..0.6f) { edit = edit.copy(contrast = it) }
                StudioSlider("Saturation", edit.saturation, -100f..100f, unit = "%") { edit = edit.copy(saturation = it) }
                StudioSlider("Hue shift", edit.hueDegrees, -180f..180f, unit = "°") { edit = edit.copy(hueDegrees = it) }
                Text(
                    "A look writes these four and then gets out of the way — every one stays an ordinary " +
                        "slider afterwards, the same way the live-input profiles work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Motion and frame ------------------------------------------
        item {
            StudioSection("Frame") {
                StudioSlider("Speed", edit.speed, 0.25f..4f, unit = "×", decimals = 2) {
                    edit = edit.copy(speed = it)
                }
                StudioSlider("Rotate", edit.rotationDegrees, -180f..180f, unit = "°") {
                    edit = edit.copy(rotationDegrees = it)
                }
                Text("Reframe", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StudioChip("As shot", selected = edit.ratio == null) { edit = edit.copy(ratio = null) }
                    ExportRatio.entries.forEach { r ->
                        StudioChip(r.label, selected = edit.ratio == r) { edit = edit.copy(ratio = r) }
                    }
                }
                if (edit.ratio != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportQuality.entries.forEach { q ->
                            StudioChip("${q.shortSide}p", selected = edit.quality == q) { edit = edit.copy(quality = q) }
                        }
                    }
                    Text(
                        "Reframing crops rather than pillarboxes — turning 16:9 into 9:16 means losing the " +
                            "sides, and black bars are the one result nobody wants from that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- Sound and caption -----------------------------------------
        item {
            StudioSection("Sound & caption") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Mute", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = edit.mute, onCheckedChange = { edit = edit.copy(mute = it) })
                }
                OutlinedTextField(
                    value = edit.caption,
                    onValueChange = { edit = edit.copy(caption = it) },
                    label = { Text("Burnt-in caption") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Drawn into the frame after everything else, so it is not graded or cropped with the " +
                        "picture. Leave it empty for none.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Render ------------------------------------------------------
        item {
            StudioSection("Render & send") {
                when {
                    studio.running -> {
                        LinearProgressIndicator(
                            progress = { studio.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Rendering… ${(studio.progress * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        CrystalButton(filled = false, onClick = viewModel::cancelStudioExport) { Text("Cancel") }
                    }
                    studio.resultUri != null -> {
                        Text("Saved to Movies/MusicViz.", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CrystalButton(onClick = { context.shareVideo(studio.resultUri!!) }) { Text("Send…") }
                            CrystalButton(filled = false, onClick = { context.viewVideo(studio.resultUri!!) }) { Text("Play") }
                            TextButton(onClick = viewModel::clearStudioResult) { Text("Edit again") }
                        }
                        Text(
                            "Send opens the phone's own share sheet — YouTube, Instagram, TikTok, Drive, " +
                                "a message, whatever is installed and signed in. MusicViz has no network " +
                                "access of its own and never uploads anything itself.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        studio.error?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CrystalButton(
                                enabled = edit.trimmedMs(duration) > 0,
                                onClick = { viewModel.startStudioExport(clip, edit) },
                            ) { Text("Render") }
                            TextButton(onClick = { edit = ClipEdit() }) { Text("Reset") }
                        }
                        Text(
                            if (edit.isIdentity(duration)) {
                                "Nothing is changed yet — rendering now would just copy the clip."
                            } else {
                                "Renders a NEW file. The original is never overwritten."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Keyframes shown across a clip in the trim strip. */
private const val FILMSTRIP_FRAMES = 6

@Composable
private fun StudioSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .crystalPanel(
                0.28f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 20.dp,
                glowStrength = 0.45f,
            ).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CrystalOverline(title)
        content()
    }
}

@Composable
private fun StudioSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    decimals: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            "$label  ${"%.${decimals}f".format(value)}$unit",
            style = MaterialTheme.typography.labelMedium,
        )
        CrystalSlider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun StudioChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .crystalPanel(
                if (selected) 0.5f else 0.2f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.primary,
                corner = 14.dp,
                glowStrength = if (selected) 1f else 0.3f,
                prismatic = selected,
            ).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) accentTextColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun clock(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)

/**
 * "Upload", as the platform actually does it.
 *
 * A chooser rather than a hard-coded target: the apps that can receive a video
 * are whatever the phone has installed, already signed in, with their own
 * upload flows and their own rules. Reimplementing three of those against APIs
 * that need OAuth and a network permission the app does not have would be
 * worse in every way.
 */
private fun android.content.Context.shareVideo(uri: Uri) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(Intent.createChooser(send, "Send video")) }
}

private fun android.content.Context.viewVideo(uri: Uri) {
    val view =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(view) }
}
