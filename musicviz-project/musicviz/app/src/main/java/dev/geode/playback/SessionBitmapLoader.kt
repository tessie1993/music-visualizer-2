package dev.geode.playback

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * Supplies the media session its cover art.
 *
 * ## Why a loader and not artwork bytes on every item
 *
 * `MediaMetadata` can carry the picture itself via `setArtworkData`, and that
 * is the obvious way to do this. It is the wrong way here: this app queues up
 * to 1001 items at once, and attaching a decoded cover to each would mean
 * holding a thousand bitmaps to display one. Setting only `artworkUri` and
 * resolving it on demand keeps the queue cheap, and the session asks for
 * exactly the one sleeve it is about to draw.
 *
 * ## Why the default loader cannot do it
 *
 * Media3's `DataSourceBitmapLoader` fetches `artworkUri` and decodes the bytes
 * it gets back. Pointed at an audio file that yields the MP3, not a JPEG, so
 * every load fails and the notification keeps the generic icon — which is
 * exactly the state this app shipped in. Album art on Android lives *inside*
 * the media file, so reading it needs a metadata retriever, which is what
 * [MediaArtwork] does and what this hands to the session.
 *
 * Wrap in `CacheBitmapLoader` at the call site so a paused track's sleeve is
 * not re-decoded on every notification update.
 */
@UnstableApi
class SessionBitmapLoader(
    context: Context,
) : BitmapLoader {
    private val appContext = context.applicationContext

    /**
     * One thread, not a pool: art is loaded for the single item being
     * displayed, and a retriever open is I/O rather than compute, so
     * parallelism buys nothing and a pool would keep idle threads alive for
     * the life of the service.
     */
    private val io = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor { r -> Thread(r, "geode-art") })

    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        io.submit<Bitmap> {
            MediaArtwork.decodeBytes(data) ?: throw IllegalArgumentException("could not decode artwork bytes")
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        io.submit<Bitmap> {
            MediaArtwork.decodeEmbedded(appContext, uri.toString())
                ?: throw IllegalArgumentException("no embedded artwork in $uri")
        }

    /**
     * Prefers bytes when an item carries them, then the uri.
     *
     * Returning null means "this item has no artwork", which the session reads
     * as "draw the placeholder" — the right answer for a track with no cover,
     * and different from a failed future, which reads as an error.
     */
    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        metadata.artworkData?.let { return decodeBitmap(it) }
        metadata.artworkUri?.let { return loadBitmap(it) }
        return null
    }

    /** Stops the loader's thread; call when the session is released. */
    fun release() {
        io.shutdown()
    }
}
