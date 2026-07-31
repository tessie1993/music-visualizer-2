package dev.musicviz.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * An imported audio track plus any analysis and user-edited metadata we've
 * cached for it. [analyzed] distinguishes tracks whose BPM/duration are known
 * (shown in the library) from ones only just added. The tag-style fields
 * ([album], [genre], [year], [trackNo], [comment]) are app-side overrides:
 * they live only in this store — audio files are never modified.
 */
data class LibraryTrack(
    val uri: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0L,
    val bpm: Float = 0f,
    val key: String = "",
    val analyzed: Boolean = false,
    val album: String = "",
    val genre: String = "",
    val year: Int = 0,
    val trackNo: Int = 0,
    val comment: String = "",
)

/**
 * JSON-file persistence for the imported-track library in app-private
 * storage. One file holds the whole list; the set is small (user's own
 * imports) so full rewrites are fine.
 */
class TrackLibrary(
    context: Context,
) {
    private val file = File(context.filesDir, "library.json")

    fun list(): List<LibraryTrack> =
        runCatching {
            if (!file.exists()) return emptyList()
            parse(file.readText())
        }.getOrDefault(emptyList())

    /** Adds tracks not already present (dedup by uri); returns the new list. */
    fun addAll(tracks: List<LibraryTrack>): List<LibraryTrack> {
        val existing = list().associateBy { it.uri }.toMutableMap()
        for (t in tracks) if (!existing.containsKey(t.uri)) existing[t.uri] = t
        val merged = existing.values.sortedBy { it.title.lowercase() }
        write(merged)
        return merged
    }

    /** Records analysis results for a track, creating the entry if needed. */
    fun updateAnalysis(
        uri: String,
        title: String,
        durationMs: Long,
        bpm: Float,
        key: String = "",
    ): List<LibraryTrack> {
        val map = list().associateBy { it.uri }.toMutableMap()
        // copy() so user-edited metadata (album/genre/…) survives re-analysis.
        val base = map[uri] ?: LibraryTrack(uri = uri, title = title)
        map[uri] =
            base.copy(
                durationMs = if (durationMs > 0) durationMs else base.durationMs,
                bpm = bpm,
                key = key.ifBlank { base.key },
                analyzed = true,
            )
        val merged = map.values.sortedBy { it.title.lowercase() }
        write(merged)
        return merged
    }

    /** Overwrites title/artist for an existing entry (metadata refresh). */
    fun updateMetadata(
        uri: String,
        title: String,
        artist: String,
    ): List<LibraryTrack> {
        val prev = list().firstOrNull { it.uri == uri } ?: return list()
        return updateMetadata(uri, title, artist, prev.album, prev.genre, prev.year, prev.trackNo, prev.comment)
    }

    /**
     * Upserts user-editable track info. Creates the entry when absent so
     * device (MediaStore) tracks that were never imported can still carry
     * app-side metadata overrides.
     */
    fun updateMetadata(
        uri: String,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int,
        trackNo: Int,
        comment: String,
    ): List<LibraryTrack> {
        val merged = upsertInfo(list(), uri, title, artist, album, genre, year, trackNo, comment)
        write(merged)
        return merged
    }

    fun remove(uri: String): List<LibraryTrack> {
        val merged = list().filterNot { it.uri == uri }
        write(merged)
        return merged
    }

    private fun write(tracks: List<LibraryTrack>) {
        // AtomicWrite reports failure rather than throwing, so the runCatching
        // this replaced has nothing left to catch.
        AtomicWrite.text(file, serialize(tracks))
    }

    companion object {
        /** Schema version written by [serialize]. */
        private const val VERSION = 2

        /** Pure upsert behind [updateMetadata]; split out for headless tests. */
        internal fun upsertInfo(
            tracks: List<LibraryTrack>,
            uri: String,
            title: String,
            artist: String,
            album: String,
            genre: String,
            year: Int,
            trackNo: Int,
            comment: String,
        ): List<LibraryTrack> {
            val map = tracks.associateBy { it.uri }.toMutableMap()
            val base = map[uri] ?: LibraryTrack(uri = uri, title = title)
            map[uri] =
                base.copy(
                    title = title,
                    artist = artist,
                    album = album,
                    genre = genre,
                    year = year,
                    trackNo = trackNo,
                    comment = comment,
                )
            return map.values.sortedBy { it.title.lowercase() }
        }

        /**
         * Parses either on-disk format: version 1 was a raw JSON array of
         * tracks; version 2 wraps it as {"version":2,"tracks":[...]}.
         * Malformed input yields an empty library rather than a crash.
         */
        internal fun parse(json: String): List<LibraryTrack> =
            runCatching {
                val trimmed = json.trim()
                val arr = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).getJSONArray("tracks")
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())

        internal fun serialize(tracks: List<LibraryTrack>): String {
            val arr = JSONArray()
            tracks.forEach { arr.put(toJson(it)) }
            return JSONObject().put("version", VERSION).put("tracks", arr).toString()
        }

        private fun toJson(t: LibraryTrack): JSONObject =
            JSONObject()
                .put("uri", t.uri)
                .put("title", t.title)
                .put("artist", t.artist)
                .put("durationMs", t.durationMs)
                .put("bpm", t.bpm.toDouble())
                .put("key", t.key)
                .put("analyzed", t.analyzed)
                .put("album", t.album)
                .put("genre", t.genre)
                .put("year", t.year)
                .put("trackNo", t.trackNo)
                .put("comment", t.comment)

        private fun fromJson(o: JSONObject): LibraryTrack =
            LibraryTrack(
                uri = o.getString("uri"),
                title = o.optString("title", "Track"),
                artist = o.optString("artist", ""),
                durationMs = o.optLong("durationMs", 0L),
                bpm = o.optDouble("bpm", 0.0).toFloat(),
                key = o.optString("key", ""),
                analyzed = o.optBoolean("analyzed", false),
                album = o.optString("album", ""),
                genre = o.optString("genre", ""),
                year = o.optInt("year", 0),
                trackNo = o.optInt("trackNo", 0),
                comment = o.optString("comment", ""),
            )
    }
}
