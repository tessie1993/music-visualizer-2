package dev.geode.ui

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.geode.playback.MediaArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Album art for the Home screen and the player, decoded small and remembered.
 *
 * A list of sleeves is the one place this app reads a lot of files purely to
 * look at them, so three things are non-negotiable: decode off the main
 * thread, decode DOWN (a 3000 px sleeve at 96 dp is 99% waste), and never
 * decode the same track twice while scrolling. A miss is cached as a miss for
 * the same reason - a track with no embedded picture must not re-open the
 * retriever every time it scrolls back into view.
 */
object ArtworkCache {
    /** A cached "this track has no artwork", so misses cost nothing twice. */
    private val NONE = Any()

    // ~40 sleeves at ART_PX square; the LRU is sized in entries rather than
    // bytes because every entry here is the same bounded size by construction.
    private val cache = LruCache<String, Any>(40)

    /** Longest edge we decode to. Comfortably over the largest tile on Home. */
    private const val ART_PX = 384

    suspend fun load(
        context: Context,
        uri: String,
    ): ImageBitmap? {
        cache.get(uri)?.let { return if (it === NONE) null else it as ImageBitmap }
        val decoded = withContext(Dispatchers.IO) { decode(context, uri) }
        cache.put(uri, decoded ?: NONE)
        return decoded
    }

    /** Synchronous peek for callers that must not suspend (list pre-fill). */
    fun peek(uri: String): ImageBitmap? = cache.get(uri)?.takeIf { it !== NONE } as? ImageBitmap

    /**
     * Delegates to [MediaArtwork] so the sleeve in the app and the sleeve on
     * the lock screen are the same bytes decoded the same way. This used to
     * hold its own copy of the retriever-and-downsample logic, which meant a
     * fix to one was a fix to one.
     */
    private fun decode(
        context: Context,
        uri: String,
    ): ImageBitmap? = MediaArtwork.decodeEmbedded(context, uri, ART_PX)?.asImageBitmap()
}

/**
 * Frames pulled out of video files, for the Studio's clip list and its trim
 * filmstrip.
 *
 * Separate cache from [ArtworkCache] because the key is different: a sleeve is
 * identified by its track, a frame by a track AND a timestamp, and mixing the
 * two would make a filmstrip evict every album cover on the screen behind it.
 */
object VideoFrameCache {
    private val NONE = Any()

    // Enough for a clip list plus one open filmstrip.
    private val cache = LruCache<String, Any>(32)

    suspend fun frame(
        context: Context,
        uri: String,
        atMs: Long,
    ): ImageBitmap? {
        val key = "$uri@$atMs"
        cache.get(key)?.let { return if (it === NONE) null else it as ImageBitmap }
        val decoded = withContext(Dispatchers.IO) { extract(context, uri, atMs) }
        cache.put(key, decoded ?: NONE)
        return decoded
    }

    fun peek(
        uri: String,
        atMs: Long,
    ): ImageBitmap? = cache.get("$uri@$atMs")?.takeIf { it !== NONE } as? ImageBitmap

    private fun extract(
        context: Context,
        uri: String,
        atMs: Long,
    ): ImageBitmap? =
        runCatching {
            // try/finally rather than use(): MediaMetadataRetriever only became
            // AutoCloseable in API 29 and this app runs from 26.
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                // CLOSEST_SYNC rather than CLOSEST: a filmstrip wants six cheap
                // keyframes, not six exact frames each decoded from the
                // preceding GOP.
                retriever
                    .getFrameAtTime(
                        atMs * 1000L,
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )?.asImageBitmap()
            } finally {
                retriever.release()
            }
        }.getOrNull()
}

/** A frame from a video, with the same gradient stand-in when it has none. */
@Composable
fun VideoFrame(
    uri: String?,
    atMs: Long,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
) {
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    var frame by remember(uri, atMs) { mutableStateOf(uri?.let { VideoFrameCache.peek(it, atMs) }) }
    LaunchedEffect(uri, atMs) {
        if (uri != null && frame == null && !inspecting) frame = VideoFrameCache.frame(context, uri, atMs)
    }
    Box(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(corner))) {
        val bitmap = frame
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize().background(placeholderBrush(uri)))
        }
    }
}

/**
 * A track's sleeve, or - when it has none - a deterministic gradient standing
 * in for it.
 *
 * The stand-in is derived from the uri, so a track without artwork still
 * looks like ITSELF everywhere it appears: the same tile colour on Home, in
 * the queue and in the player. That is what makes a wall of art scannable
 * even when half of it is missing.
 */
@Composable
fun TrackArtwork(
    uri: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
) {
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    var art by remember(uri) { mutableStateOf(uri?.let(ArtworkCache::peek)) }
    LaunchedEffect(uri) {
        if (uri != null && art == null && !inspecting) art = ArtworkCache.load(context, uri)
    }
    Box(modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(corner))) {
        val bitmap = art
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize().background(placeholderBrush(uri))) {
                // The placeholder tile is dark by construction (see
                // placeholderBrush), so the glyph stays light — but tinted
                // toward the theme primary rather than theme-blind white.
                Icon(
                    Icons.Filled.MusicNote,
                    null,
                    Modifier.align(Alignment.Center),
                    tint = lerp(MaterialTheme.colorScheme.primary, Color.White, 0.75f).copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * The stand-in gradient for [uri]: two hues a sixth of the circle apart,
 * picked by hash so the same track always draws the same tile.
 */
fun placeholderBrush(uri: String?): Brush {
    val hash = (uri ?: "").hashCode()
    val hue = ((hash ushr 8) % 360 + 360) % 360
    return Brush.linearGradient(
        listOf(
            Color.hsv(hue.toFloat(), 0.55f, 0.42f),
            Color.hsv(((hue + 58) % 360).toFloat(), 0.62f, 0.22f),
        ),
    )
}
