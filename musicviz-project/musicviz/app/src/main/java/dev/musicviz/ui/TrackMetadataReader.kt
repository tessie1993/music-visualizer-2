package dev.musicviz.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/** Embedded-tag metadata read from a file; fields blank/zero when absent. */
internal data class FileMeta(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: Int = 0,
    val trackNo: Int = 0,
)

/**
 * Reads track metadata the way real media players do: embedded tags first
 * ([MediaMetadataRetriever]), then the provider's display name, and only then
 * the uri path — so content uris never surface as bare document numbers.
 *
 * [read] and [titleOf] hit disk; call them on `Dispatchers.IO`. [quick] is the
 * main-thread-safe fallback: a display-name query with no retriever I/O.
 */
internal class TrackMetadataReader(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** Full tag read for [uri]; never throws, falls back down the chain. */
    fun read(uri: Uri): FileMeta {
        var title: String? = null
        var artist: String? = null
        var album = ""
        var genre = ""
        var year = 0
        var trackNo = 0
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(appContext, uri)

                fun tag(key: Int): String? = r.extractMetadata(key)?.trim()?.ifBlank { null }
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: ""
                // Year tags arrive as "1997" or full dates; track numbers as "3" or "3/12".
                year =
                    tag(MediaMetadataRetriever.METADATA_KEY_YEAR)
                        ?.filter { it.isDigit() }
                        ?.take(4)
                        ?.toIntOrNull() ?: 0
                trackNo =
                    tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                        ?.substringBefore('/')
                        ?.trim()
                        ?.toIntOrNull() ?: 0
            } finally {
                runCatching { r.release() }
            }
        }
        if (title == null) title = displayName(uri)
        return FileMeta(
            title = title ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Track",
            artist = artist ?: "",
            album = album,
            genre = genre,
            year = year,
            trackNo = trackNo,
        )
    }

    /** Embedded/display title for [uri]. Hits disk. */
    fun titleOf(uri: Uri): String = read(uri).title

    /** Main-thread-safe title/artist: display name only (no retriever I/O). */
    fun quick(uri: Uri): Pair<String, String> = (displayName(uri) ?: "Track") to ""

    private fun displayName(uri: Uri): String? =
        runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.substringBeforeLast('.')

    /** A library row built from a uri plus its resolved tags. */
    fun libraryTrack(
        uriStr: String,
        m: FileMeta,
    ): LibraryTrack =
        LibraryTrack(
            uri = uriStr,
            title = m.title,
            artist = m.artist,
            album = m.album,
            genre = m.genre,
            year = m.year,
            trackNo = m.trackNo,
        )

    /** A library row for [uri], reading its tags first. Hits disk. */
    fun libraryTrack(uri: Uri): LibraryTrack = libraryTrack(uri.toString(), read(uri))
}
