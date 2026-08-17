package dev.geode.ui

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.geode.R
import dev.geode.export.ClipEdit
import dev.geode.export.ClipLook
import dev.geode.export.ExportQuality
import dev.geode.export.ExportRatio
import dev.geode.export.StudioClip
import kotlin.math.roundToInt

/**
 * The Export Studio: what happens to a render after it exists.
 *
 * The visualizer exporter makes a file and stops there, which leaves the two
 * jobs everyone actually has - cut the good bit out, and get it somewhere -
 * to a second app. This does both without leaving Geode.
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

    val chooserTitle = stringResource(R.string.studio_share_chooser)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            CrystalOverline(stringResource(R.string.app_name))
            GlowTitle(
                stringResource(if (editing == null) R.string.nav_studio else R.string.studio_edit),
            )
        }
        val clip = editing
        if (clip == null) {
            ClipLibrary(
                studio = studio,
                onOpen = { editing = it },
                onPick = { picker.launch(arrayOf("video/*")) },
                onShare = { context.shareVideo(it, chooserTitle) },
                onRename = { clip, name, done -> viewModel.renameStudioClip(clip.uri, name, done) },
                onDelete = { clip, done -> viewModel.deleteStudioClip(clip.uri, done) },
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
    onRename: (StudioClip, String, (Boolean) -> Unit) -> Unit,
    onDelete: (StudioClip, (Boolean) -> Unit) -> Unit,
) {
    // Null when no dialog is up. Held by clip rather than by index so a refresh
    // that reorders the list cannot retarget a pending delete at another file.
    var renaming by remember { mutableStateOf<StudioClip?>(null) }
    var deleting by remember { mutableStateOf<StudioClip?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Read here rather than inside the result callbacks: those run off the
    // composition, where stringResource does not exist.
    val renameFailed = stringResource(R.string.studio_rename_failed)
    val deleteFailed = stringResource(R.string.studio_delete_failed)
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrystalButton(onClick = onPick) { Text(stringResource(R.string.studio_open_video)) }
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
                    CrystalOverline(stringResource(R.string.studio_empty_title))
                    Text(
                        stringResource(R.string.studio_empty_body),
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
                TextButton(onClick = { onShare(Uri.parse(clip.uri)) }) { Text(stringResource(R.string.studio_send)) }
                TextButton(onClick = { renaming = clip }) { Text(stringResource(R.string.action_rename)) }
                TextButton(onClick = { deleting = clip }) { Text(stringResource(R.string.action_delete)) }
            }
        }
        if (studio.clips.isNotEmpty()) {
            item {
                // Renders run to 300 MB a minute at 4K, so the total is the
                // number that tells someone whether to start clearing up.
                val bytes = studio.clips.sumOf { it.sizeBytes }
                Text(
                    pluralStringResource(R.plurals.clip_count, studio.clips.size, studio.clips.size) +
                        ", " +
                        stringResource(
                            R.string.studio_storage_used,
                            stringResource(R.string.studio_size_gb, bytes / (1024f * 1024f * 1024f)),
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    renaming?.let { clip ->
        var name by remember(clip.uri) { mutableStateOf(clip.name.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.studio_rename_title)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.studio_rename_field)) },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onRename(clip, name) { ok -> notice = if (ok) null else renameFailed }
                        renaming = null
                    },
                ) { Text(stringResource(R.string.action_rename)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    deleting?.let { clip ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.studio_delete_title)) },
            // Named, and stated as permanent: the file is gone from the device,
            // not from this list.
            text = { Text(stringResource(R.string.studio_delete_body, clip.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(clip) { ok ->
                        notice = if (ok) null else deleteFailed
                    }
                    deleting = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    notice?.let { message ->
        AlertDialog(
            onDismissRequest = { notice = null },
            title = { Text(stringResource(R.string.studio_notice_title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text(stringResource(R.string.action_ok)) } },
        )
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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun ClipEditor(
    viewModel: PlayerViewModel,
    clip: StudioClip,
    studio: StudioUiState,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.studio_share_chooser)
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
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_back)) }
            }
        }

        // ---- Preview --------------------------------------------------
        item {
            ClipPreview(clip, edit)
            Text(
                stringResource(R.string.studio_preview_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Trim -----------------------------------------------------
        item {
            StudioSection(stringResource(R.string.studio_section_cut)) {
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
                    stringResource(
                        R.string.studio_trim_summary,
                        clock(edit.startMs),
                        clock(outEnd),
                        clock(edit.trimmedMs(duration)),
                    ) +
                        if (edit.speed != 1f) {
                            stringResource(R.string.studio_trim_renders, clock(edit.outputMs(duration)))
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = accentTextColor(),
                )
            }
        }

        // ---- Look -----------------------------------------------------
        item {
            StudioSection(stringResource(R.string.studio_section_look)) {
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
                StudioSlider(stringResource(R.string.studio_brightness), edit.brightness, -0.5f..0.5f) { edit = edit.copy(brightness = it) }
                StudioSlider(stringResource(R.string.studio_contrast), edit.contrast, -0.6f..0.6f) { edit = edit.copy(contrast = it) }
                StudioSlider(
                    stringResource(R.string.studio_saturation),
                    edit.saturation,
                    -100f..100f,
                    unit = "%",
                ) { edit = edit.copy(saturation = it) }
                StudioSlider(
                    stringResource(R.string.studio_hue_shift),
                    edit.hueDegrees,
                    -180f..180f,
                    unit = "°",
                ) { edit = edit.copy(hueDegrees = it) }
                Text(
                    stringResource(R.string.studio_look_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Motion and frame ------------------------------------------
        item {
            StudioSection(stringResource(R.string.studio_section_frame)) {
                StudioSlider(stringResource(R.string.studio_speed), edit.speed, 0.25f..4f, unit = "×", decimals = 2) {
                    edit = edit.copy(speed = it)
                }
                StudioSlider(stringResource(R.string.studio_rotate), edit.rotationDegrees, -180f..180f, unit = "°") {
                    edit = edit.copy(rotationDegrees = it)
                }
                Text(stringResource(R.string.studio_reframe), style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StudioChip(stringResource(R.string.studio_as_shot), selected = edit.ratio == null) { edit = edit.copy(ratio = null) }
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
                        stringResource(R.string.studio_reframe_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---- Sound and caption -----------------------------------------
        item {
            StudioSection(stringResource(R.string.studio_section_sound)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.studio_mute), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = edit.mute, onCheckedChange = { edit = edit.copy(mute = it) })
                }
                OutlinedTextField(
                    value = edit.caption,
                    onValueChange = { edit = edit.copy(caption = it) },
                    label = { Text(stringResource(R.string.studio_caption)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.studio_caption_explainer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Render ------------------------------------------------------
        item {
            StudioSection(stringResource(R.string.studio_section_render)) {
                when {
                    studio.running -> {
                        LinearProgressIndicator(
                            progress = { studio.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.studio_rendering, (studio.progress * 100).roundToInt()),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        CrystalButton(
                            filled = false,
                            onClick = viewModel::cancelStudioExport,
                        ) { Text(stringResource(R.string.action_cancel)) }
                    }
                    studio.resultUri != null -> {
                        Text(stringResource(R.string.studio_saved), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CrystalButton(
                                onClick = { context.shareVideo(studio.resultUri!!, chooserTitle) },
                            ) { Text(stringResource(R.string.studio_send_ellipsis)) }
                            CrystalButton(
                                filled = false,
                                onClick = { context.viewVideo(studio.resultUri!!) },
                            ) { Text(stringResource(R.string.studio_play)) }
                            TextButton(onClick = viewModel::clearStudioResult) { Text(stringResource(R.string.studio_edit_again)) }
                        }
                        Text(
                            stringResource(R.string.studio_send_explainer),
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
                            ) { Text(stringResource(R.string.studio_render)) }
                            TextButton(onClick = { edit = ClipEdit() }) { Text(stringResource(R.string.studio_reset)) }
                        }
                        Text(
                            stringResource(
                                if (edit.isIdentity(duration)) {
                                    R.string.studio_nothing_changed
                                } else {
                                    R.string.studio_renders_new_file
                                },
                            ),
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
            stringResource(R.string.studio_slider_value, label, "%.${decimals}f".format(value), unit),
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
private fun android.content.Context.shareVideo(
    uri: Uri,
    chooserTitle: String,
) {
    val send =
        Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(Intent.createChooser(send, chooserTitle)) }
}

private fun android.content.Context.viewVideo(uri: Uri) {
    val view =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { startActivity(view) }
}
