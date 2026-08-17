package dev.geode.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The queue you were listening to, where you were in it, and how far into the
 * track — kept across process death and reboot.
 *
 * ## Why this exists
 *
 * Resumption used to rebuild exactly ONE track starting at 0:00, from the play
 * history. A listener forty minutes into a mix, or thirty tracks into a queued
 * album, lost all of it the moment Android reclaimed the process overnight —
 * which is the most common resume scenario a phone music player has. Restoring
 * the queue at the exact position is the defining baseline of every serious
 * competitor.
 *
 * ## What is stored, and what is not
 *
 * Track titles and artists ride along with the uris. That looks redundant
 * against the library, but resumption runs *before* the library is scanned —
 * System UI asks for the resumption item straight after a reboot — and a
 * notification that says "Unknown" until a scan finishes is the failure this
 * avoids. Everything else (artwork, duration, analysis) is recovered normally.
 *
 * Writes go through [AtomicWrite], like every other store here: a session file
 * half-written by a process being killed is exactly the situation this feature
 * exists for, so it must never be the thing that breaks it. A file that cannot
 * be parsed is quarantined rather than silently overwritten.
 */
class SessionStore(
    context: Context,
) {
    private val file = File(context.filesDir, FILE_NAME)

    /** One entry of the saved queue. */
    data class SavedTrack(
        val uri: String,
        val title: String,
        val artist: String,
    )

    /**
     * A whole listening session.
     *
     * [index] is always a valid index into [tracks] and [positionMs] is never
     * negative — [load] repairs both rather than handing a caller a state it
     * would have to re-check.
     */
    data class Saved(
        val tracks: List<SavedTrack>,
        val index: Int,
        val positionMs: Long,
    )

    /**
     * Reads the stored session, or null when there is none, it is empty, or it
     * could not be parsed.
     *
     * An unreadable file is quarantined so the next save starts clean and the
     * broken one survives for diagnosis — the same three-outcome handling the
     * other stores use, for the same reason: one bad read must not erase the
     * record.
     */
    @Suppress("ReturnCount")
    fun load(): Saved? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val parsed =
            runCatching { parse(text) }.getOrElse {
                AtomicWrite.quarantine(file)
                return null
            }
        return parsed
    }

    /** Replaces the stored session. Returns false if it could not be written. */
    fun save(session: Saved): Boolean {
        if (session.tracks.isEmpty()) return clear()
        val items = JSONArray()
        for (t in session.tracks) {
            items.put(
                JSONObject()
                    .put(KEY_URI, t.uri)
                    .put(KEY_TITLE, t.title)
                    .put(KEY_ARTIST, t.artist),
            )
        }
        val root =
            JSONObject()
                .put(KEY_VERSION, VERSION)
                .put(KEY_TRACKS, items)
                .put(KEY_INDEX, session.index.coerceIn(0, session.tracks.size - 1))
                .put(KEY_POSITION, session.positionMs.coerceAtLeast(0L))
        return AtomicWrite.text(file, root.toString())
    }

    /** Forgets the session; the next launch starts from nothing. */
    fun clear(): Boolean {
        if (!file.exists()) return true
        return file.delete()
    }

    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    private fun parse(text: String): Saved? {
        val root = JSONObject(text)
        val array = root.optJSONArray(KEY_TRACKS) ?: return null
        val tracks = ArrayList<SavedTrack>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val uri = item.optString(KEY_URI).orEmpty()
            // A row with no uri cannot be played, so it cannot be part of a
            // queue; dropping it keeps the rest of the session usable rather
            // than failing the whole restore over one bad entry.
            if (uri.isBlank()) continue
            tracks +=
                SavedTrack(
                    uri = uri,
                    title = item.optString(KEY_TITLE).orEmpty(),
                    artist = item.optString(KEY_ARTIST).orEmpty(),
                )
        }
        if (tracks.isEmpty()) return null
        return Saved(
            tracks = tracks,
            index = root.optInt(KEY_INDEX, 0).coerceIn(0, tracks.size - 1),
            positionMs = root.optLong(KEY_POSITION, 0L).coerceAtLeast(0L),
        )
    }

    companion object {
        private const val FILE_NAME = "session.json"

        /**
         * Bumped only when the shape changes incompatibly. Readers tolerate
         * missing fields, so adding one does not need a bump.
         */
        private const val VERSION = 1

        private const val KEY_VERSION = "version"
        private const val KEY_TRACKS = "tracks"
        private const val KEY_INDEX = "index"
        private const val KEY_POSITION = "positionMs"
        private const val KEY_URI = "uri"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"

        /**
         * How far playback must move before the position is written again.
         *
         * The poll runs at 500 ms and a write is a file rename; persisting
         * every tick would be 120 renames a minute for a number that only has
         * to be roughly right. Five seconds is the most listening a restore can
         * lose, which is under the length of a fade-in.
         */
        const val POSITION_WRITE_INTERVAL_MS: Long = 5_000
    }
}
