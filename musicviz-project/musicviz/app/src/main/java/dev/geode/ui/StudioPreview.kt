package dev.geode.ui

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.geode.R
import dev.geode.export.ClipEdit
import dev.geode.export.ExportAspect
import dev.geode.export.StudioClip
import kotlinx.coroutines.delay

/**
 * Live playback of the edit in progress - the trim as a loop, the grade,
 * speed, rotation, reframe and caption exactly as they will render.
 *
 * Parity is structural, not promised: the trim comes from [ClipEdit.clipping]
 * and the picture from [ClipEdit.videoEffects], the same two calls
 * StudioExporter hands to Transformer. There is no second effect chain to
 * drift out of step.
 *
 * Always muted. The editor lives inside a music player whose session keeps
 * playing while clips are cut; a second audible stream fighting it for focus
 * would be worse than silence, and the decisions made here - where to cut,
 * how it looks - are visual. The export's audio is untouched by this.
 */
@UnstableApi
@Composable
internal fun ClipPreview(
    clip: StudioClip,
    edit: ClipEdit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var playing by remember(clip.uri) { mutableStateOf(true) }
    val player =
        remember(clip.uri) {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                playWhenReady = true
            }
        }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // One rebuild path for every change. setVideoEffects has to be in place
    // before prepare(), so trim and grade edits alike re-prepare the whole
    // pipeline; the delay turns a slider drag's stream of values into one
    // rebuild after the hand settles (a new LaunchedEffect cancels the old).
    LaunchedEffect(player, edit) {
        delay(REBUILD_SETTLE_MS)
        player.setVideoEffects(edit.videoEffects())
        player.setMediaItems(
            listOf(
                MediaItem
                    .Builder()
                    .setUri(clip.uri)
                    .setClippingConfiguration(edit.clipping())
                    .build(),
            ),
        )
        player.playbackParameters = PlaybackParameters(edit.speed)
        player.prepare()
    }
    LaunchedEffect(player, playing) {
        player.playWhenReady = playing
    }

    // The frame the render will have: the reframe's aspect when one is
    // chosen, the source clip's otherwise.
    val aspect =
        edit.ratio?.let { r ->
            ExportAspect.of(edit.quality, r).let { it.width.toFloat() / it.height }
        } ?: if (clip.width > 0 && clip.height > 0) {
            clip.width.toFloat() / clip.height
        } else {
            16f / 9f
        }
    val pauseHint = stringResource(R.string.studio_preview_toggle)
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.3f, 3.4f))
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(Color.Black)
            .clickable { playing = !playing },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { player.setVideoSurfaceView(it) }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (!playing) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = pauseHint,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.zIndex(1f).size(44.dp),
            )
        }
    }
}

private const val REBUILD_SETTLE_MS = 250L
