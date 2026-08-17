package dev.geode.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Embedded cover art, decoded from a track's own tags.
 *
 * The single decode used by every artwork surface in the app. `ui/Artwork.kt`
 * wraps it for Compose and [SessionBitmapLoader] wraps it for the media
 * session, so the sleeve on the lock screen and the sleeve in the app are the
 * same bytes decoded the same way — and a fix to the decode is a fix
 * everywhere rather than in one of two copies.
 *
 * Returns a platform [Bitmap] deliberately: this layer has no business knowing
 * about Compose, and the session needs a platform bitmap anyway.
 */
object MediaArtwork {
    /**
     * Decodes the front cover embedded in [uri], downsampled so its longest
     * edge is at least [maxPx] but no more than twice it.
     *
     * Returns null when the file has no embedded picture, cannot be opened, or
     * is not media — all of which are ordinary, so none of them throw. A track
     * with no art is not an error condition; it is most of a home-ripped
     * library.
     */
    fun decodeEmbedded(
        context: Context,
        uri: String,
        maxPx: Int = DEFAULT_MAX_PX,
    ): Bitmap? =
        runCatching {
            // try/finally rather than use(): MediaMetadataRetriever only became
            // AutoCloseable in API 29 and this app runs from 26.
            val retriever = android.media.MediaMetadataRetriever()
            val bytes =
                try {
                    retriever.setDataSource(context, Uri.parse(uri))
                    retriever.embeddedPicture
                } finally {
                    retriever.release()
                } ?: return null
            decodeBytes(bytes, maxPx)
        }.getOrNull()

    /**
     * Decodes [bytes] downsampled toward [maxPx].
     *
     * Split out because the media session hands raw picture bytes straight to
     * its loader without a uri to re-open, so that path must not go back
     * through the retriever.
     */
    fun decodeBytes(
        bytes: ByteArray,
        maxPx: Int = DEFAULT_MAX_PX,
    ): Bitmap? =
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            var sample = 1
            while (longest / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()

    /**
     * Longest edge to decode to. Large enough for the biggest tile the app
     * draws and for a lock screen on a tall display, small enough that forty
     * of them are an ordinary cache rather than a memory problem.
     */
    const val DEFAULT_MAX_PX: Int = 384
}
