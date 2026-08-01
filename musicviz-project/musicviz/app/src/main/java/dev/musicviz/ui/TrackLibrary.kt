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
 *
 * [fileName] and [sizeBytes] are the file's identity across content
 * providers, captured at import time; see [TrackLibrary.identityKey] for why
 * the uri cannot serve that role. They are blank/zero for entries written
 * before schema v3 and for entries created by an analysis or metadata upsert
 * on a track that was never imported.
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
    val fileName: String = "",
    val sizeBytes: Long = 0L,
)

/**
 * JSON-file persistence for the imported-track library in app-private
 * storage. One file holds the whole list; the set is small (user's own
 * imports) so full rewrites are fine.
 *
 * Every mutator is a read-modify-write of that one file and they are driven
 * concurrently — imports and the metadata refresh from IO, playlist analysis
 * from Default, removal straight from the main thread — so each one runs its
 * whole read-modify-write under [lock] and publishes it with a rename rather
 * than a truncating overwrite. Without that, a read landing inside another
 * writer's truncation window parses as an empty library and the next write
 * makes the emptiness permanent, costing the user every import, every cached
 * BPM/key and every tag override.
 */
class TrackLibrary(
    context: Context,
) {
    private val file = File(context.filesDir, "library.json")

    fun list(): List<LibraryTrack> = synchronized(lock) { readLocked() ?: emptyList() }

    /**
     * Adds tracks not already present (dedup by [identityKey]); returns the
     * new list, or null when the store could not be read (see [mutate]).
     */
    fun addAll(tracks: List<LibraryTrack>): List<LibraryTrack>? = mutate { current -> mergeAdds(current, tracks) }

    /** Records analysis results for a track, creating the entry if needed. */
    fun updateAnalysis(
        uri: String,
        title: String,
        durationMs: Long,
        bpm: Float,
        key: String = "",
    ): List<LibraryTrack>? =
        mutate { current ->
            val map = current.associateBy { it.uri }.toMutableMap()
            // copy() so user-edited metadata (album/genre/…) survives re-analysis.
            val base = map[uri] ?: LibraryTrack(uri = uri, title = title)
            map[uri] =
                base.copy(
                    durationMs = if (durationMs > 0) durationMs else base.durationMs,
                    bpm = bpm,
                    key = key.ifBlank { base.key },
                    analyzed = true,
                )
            map.values.sortedBy { it.title.lowercase() }
        }

    /** Overwrites title/artist for an existing entry (metadata refresh). */
    fun updateMetadata(
        uri: String,
        title: String,
        artist: String,
    ): List<LibraryTrack>? =
        mutate { current ->
            // Returning `current` unchanged is what makes this a no-op write
            // for a uri we don't know; the other overload upserts instead.
            val prev = current.firstOrNull { it.uri == uri } ?: return@mutate current
            upsertInfo(current, uri, title, artist, prev.album, prev.genre, prev.year, prev.trackNo, prev.comment)
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
    ): List<LibraryTrack>? = mutate { upsertInfo(it, uri, title, artist, album, genre, year, trackNo, comment) }

    fun remove(uri: String): List<LibraryTrack>? = mutate { current -> current.filterNot { it.uri == uri } }

    /**
     * Runs one read-modify-write under [lock]. The critical section has to be
     * the whole sequence, not just the write: two mutators that only lock
     * their writes still both read the pre-change list and the later one
     * silently drops the earlier one's tracks.
     *
     * Returns the list that was written, or null when the store could not be
     * read at all — in that case nothing is written, because a fresh list
     * computed from an empty base would overwrite tracks we never saw.
     * Callers keep showing what they already have.
     */
    private fun mutate(block: (List<LibraryTrack>) -> List<LibraryTrack>): List<LibraryTrack>? {
        synchronized(lock) {
            val current = readLocked() ?: return null
            val merged = block(current)
            // A block that hands back its own input asked for no change, and
            // rewriting the file for it would only widen the crash window.
            if (merged !== current) writeLocked(merged)
            return merged
        }
    }

    /**
     * Reads the store, or null when the file exists but could not be read.
     *
     * Content that reads but does not parse is a different case and must not
     * simply come back as "no tracks": the next write would then persist that
     * emptiness over the user's whole collection, which is the same data loss
     * the locking above prevents. Such a file is moved aside instead, so the
     * bytes stay recoverable while the store starts fresh rather than failing
     * every import forever. Callers hold [lock].
     */
    private fun readLocked(): List<LibraryTrack>? {
        if (!file.exists()) return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        if (text.isBlank()) return emptyList()
        parseOrNull(text)?.let { return it }
        runCatching { file.renameTo(File(file.parentFile, "${file.name}.corrupt")) }
        return emptyList()
    }

    /**
     * Publishes a new list by writing a sibling temp file and renaming it
     * over the real one, which is atomic on the local filesystem: readers see
     * either the whole old library or the whole new one. `File.writeText`
     * truncates to zero first, so process death inside that window would
     * leave a zero-length library.json — a valid file that parses as an empty
     * library. A failed rename leaves the previous file untouched; losing one
     * write is recoverable, losing the library is not. Callers hold [lock].
     */
    private fun writeLocked(tracks: List<LibraryTrack>) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val ok =
            runCatching {
                tmp.writeText(serialize(tracks))
                tmp.renameTo(file)
            }.getOrDefault(false)
        if (!ok) runCatching { tmp.delete() }
    }

    companion object {
        /** Schema version written by [serialize]. */
        private const val VERSION = 3

        /**
         * Static because library.json is a single app-wide file: two
         * TrackLibrary instances (a rebuilt ViewModel racing the previous
         * one's in-flight folder scan) would otherwise take different locks
         * and interleave on it exactly as unsynchronised mutators did.
         */
        private val lock = Any()

        /**
         * Dedup key for imports. The same physical file reaches the library
         * under different uri strings — SAF document uris from the folder
         * scanner, content://media/… from MediaStore — so the uri identifies
         * the *route* to a file, not the file, and adding /Music as a library
         * folder on a device that already indexed it listed everything twice.
         * Display name plus byte size does identify it: both providers report
         * both, and they agree. Tags deliberately play no part — someone who
         * keeps the same song as FLAC and MP3 must keep both copies.
         *
         * Entries stored before schema v3 carry neither field and fall back
         * to their uri, i.e. exactly the old behaviour, so nothing is dropped
         * on upgrade; [mergeAdds] backfills them from the first import or
         * rescan that reaches the same uri, after which they dedupe like the
         * rest.
         */
        internal fun identityKey(t: LibraryTrack): String =
            if (t.fileName.isNotBlank() && t.sizeBytes > 0L) {
                "file:${t.fileName.lowercase()}:${t.sizeBytes}"
            } else {
                "uri:${t.uri}"
            }

        /** Pure add-merge behind [addAll]; split out for headless tests. */
        internal fun mergeAdds(
            existing: List<LibraryTrack>,
            incoming: List<LibraryTrack>,
        ): List<LibraryTrack> {
            val identityByUri =
                incoming
                    .filter { it.fileName.isNotBlank() && it.sizeBytes > 0L }
                    .associateBy { it.uri }
            val merged = LinkedHashMap<String, LibraryTrack>()

            fun keep(t: LibraryTrack) {
                val key = identityKey(t)
                val held = merged[key]
                merged[key] = if (held == null) t else richer(held, t)
            }
            for (t in existing) {
                val id = if (t.fileName.isBlank()) identityByUri[t.uri] else null
                keep(if (id == null) t else t.copy(fileName = id.fileName, sizeBytes = id.sizeBytes))
            }
            // Stored entries are kept ahead of incoming ones, so a rescan
            // never resets analysis or user edits to what the file's tags say.
            for (t in incoming) keep(t)
            return merged.values.sortedBy { it.title.lowercase() }
        }

        /**
         * Picks the entry to keep when two rows turn out to be the same file
         * — pre-v3 duplicates collapsing once their identity is backfilled.
         * The stored one is kept unless it is the only one without analysis,
         * so a merge can never throw away a cached BPM/key.
         */
        private fun richer(
            kept: LibraryTrack,
            other: LibraryTrack,
        ): LibraryTrack = if (!kept.analyzed && other.analyzed) other else kept

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
         * Parses any on-disk format: version 1 was a raw JSON array of
         * tracks; version 2 wraps it as {"version":2,"tracks":[...]}; version
         * 3 adds the file-identity fields. Malformed input yields an empty
         * library rather than a crash — [parseOrNull] is what the store
         * itself uses, so that it can tell "no tracks" from "unreadable".
         */
        internal fun parse(json: String): List<LibraryTrack> = parseOrNull(json) ?: emptyList()

        /** [parse], but null (rather than empty) when the input is malformed. */
        internal fun parseOrNull(json: String): List<LibraryTrack>? =
            runCatching {
                val trimmed = json.trim()
                val arr = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).getJSONArray("tracks")
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            }.getOrNull()

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
                .put("fileName", t.fileName)
                .put("sizeBytes", t.sizeBytes)

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
                fileName = o.optString("fileName", ""),
                sizeBytes = o.optLong("sizeBytes", 0L),
            )
    }
}
