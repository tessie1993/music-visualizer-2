package dev.geode.export

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

/** One video the Studio can open. */
data class StudioClip(
    val uri: String,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** "1080×1920 · 0:24 · 18 MB", with the unknown parts left out. */
    fun summary(): String =
        buildList {
            if (width > 0 && height > 0) add("$width×$height")
            if (durationMs > 0) add("%d:%02d".format(durationMs / 60_000, (durationMs / 1000) % 60))
            if (sizeBytes > 0) add("%.0f MB".format(sizeBytes / (1024f * 1024f)))
        }.joinToString(" · ")
}

/**
 * Finds the videos this app has rendered.
 *
 * Scoped to `Movies/Geode`, the shelf both exporters write to. Two reasons
 * it is not "every video on the device": these are the ones the Studio exists
 * to finish, and listing the user's whole camera roll would need the media
 * permission, which nothing else in this app asks for. Anything else can still
 * be opened one file at a time through the system picker, which needs no
 * permission at all.
 */
object StudioClips {
    /** Blocking; call on Dispatchers.IO. */
    fun list(context: Context): List<StudioClip> {
        val out = mutableListOf<StudioClip>()
        val projection =
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
            )
        // RELATIVE_PATH only exists from Q; below it the folder is only
        // visible through the legacy DATA column.
        val selection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            } else {
                "${MediaStore.Video.Media.DATA} LIKE ?"
            }
        val args =
            arrayOf(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "Movies/Geode%" else "%/Movies/Geode/%",
            )
        runCatching {
            context.contentResolver
                .query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC",
                )?.use { c ->
                    val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val width = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                    val height = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                    while (c.moveToNext()) {
                        out +=
                            StudioClip(
                                uri =
                                    ContentUris
                                        .withAppendedId(
                                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                            c.getLong(id),
                                        ).toString(),
                                name = c.getString(name) ?: "Clip",
                                durationMs = c.getLong(duration),
                                sizeBytes = c.getLong(size),
                                width = c.getInt(width),
                                height = c.getInt(height),
                            )
                    }
                }
        }
        return out
    }

    /**
     * Describes a video the user picked through the system picker, which
     * MediaStore will not answer questions about.
     */
    fun describe(
        context: Context,
        uri: android.net.Uri,
    ): StudioClip {
        var durationMs = 0L
        var width = 0
        var height = 0
        runCatching {
            // try/finally rather than use(): MediaMetadataRetriever only became
            // AutoCloseable in API 29 and this app runs from 26.
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                durationMs =
                    retriever
                        .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                width =
                    retriever
                        .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        ?.toIntOrNull() ?: 0
                height =
                    retriever
                        .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        ?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }
        }
        val name =
            runCatching {
                androidx.documentfile.provider.DocumentFile
                    .fromSingleUri(context, uri)
                    ?.name
            }.getOrNull() ?: uri.lastPathSegment ?: "Clip"
        return StudioClip(uri.toString(), name, durationMs, 0L, width, height)
    }
}
