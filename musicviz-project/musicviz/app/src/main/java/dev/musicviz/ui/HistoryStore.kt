package dev.musicviz.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the Home screen knows about your listening: which tracks came back,
 * which ones you keep coming back to, and how much time actually went into
 * them. JSON in files dir - a Room database is overkill until analysis data
 * moves in (see FEATURES_TODO).
 *
 * Two things are recorded, and they are deliberately different measurements.
 * [recordPlay] counts a track being STARTED, which is what "most played"
 * means to a person. [addListenTime] accumulates milliseconds actually spent
 * playing, which is what makes "you gave this artist four hours" true rather
 * than a count of how often you skipped past them. Per-day totals are kept
 * alongside so Home can draw the week without re-deriving it from timestamps
 * that only remember the LAST play of each track.
 */
class HistoryStore(
    context: Context,
) {
    private val file = java.io.File(context.filesDir, "history.json")

    /** uri -> what we know about it */
    private val entries = LinkedHashMap<String, Entry>()

    /** epoch day -> milliseconds listened on that day */
    private val daily = LinkedHashMap<Long, Long>()

    data class Entry(
        val uri: String,
        var lastPlayedMs: Long,
        var playCount: Int,
        var title: String,
        var artist: String = "",
        /** Milliseconds of this track actually played, across all plays. */
        var listenedMs: Long = 0L,
    )

    /** Everything Home's stats strip shows, computed in one pass. */
    data class Stats(
        val trackCount: Int,
        val totalPlays: Int,
        val totalListenedMs: Long,
        /** Milliseconds per day for the last [WEEK_DAYS] days, oldest first. */
        val week: List<Long>,
        val topArtist: String?,
        val topArtistMs: Long,
    ) {
        val weekListenedMs: Long get() = week.sum()
    }

    init {
        runCatching {
            if (file.exists()) load(file.readText())
        }
    }

    private fun load(text: String) {
        // v1 wrote a bare array of entries. Reading it back as one keeps every
        // existing install's history instead of silently starting over.
        if (text.trimStart().startsWith("[")) {
            readEntries(JSONArray(text))
            return
        }
        val root = JSONObject(text)
        readEntries(root.optJSONArray("tracks") ?: JSONArray())
        val days = root.optJSONArray("daily") ?: JSONArray()
        for (i in 0 until days.length()) {
            val o = days.getJSONObject(i)
            daily[o.getLong("day")] = o.getLong("ms")
        }
    }

    private fun readEntries(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val e =
                Entry(
                    uri = o.getString("uri"),
                    lastPlayedMs = o.getLong("last"),
                    playCount = o.getInt("count"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    listenedMs = o.optLong("ms", 0L),
                )
            entries[e.uri] = e
        }
    }

    fun recordPlay(
        uri: String,
        title: String,
        artist: String = "",
    ) {
        val e = entries.getOrPut(uri) { Entry(uri, 0L, 0, title) }
        // Strictly increasing, not just "now". The wall clock has millisecond
        // resolution and two plays can land inside one tick - skipping through
        // a queue, or a test - which left "recently played" ordering two
        // entries by whatever order the map happened to hold them in. Nudging
        // past the newest stamp keeps the order the user actually created.
        e.lastPlayedMs = maxOf(System.currentTimeMillis(), newestStamp() + 1)
        e.playCount++
        e.title = title
        // Never overwrite a known artist with a blank one: the transition
        // event fires before metadata has always resolved, and "" would erase
        // a name an earlier play already learned.
        if (artist.isNotBlank()) e.artist = artist
        persist()
    }

    /**
     * Adds real playing time to a track and to its day.
     *
     * Called from the player's polling tick, so it does NOT persist: writing
     * the file twice a second would be the app's busiest disk activity. The
     * caller flushes on the events that matter (track change, pause, the
     * ViewModel being torn down) via [flush].
     */
    fun addListenTime(
        uri: String,
        deltaMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (deltaMs <= 0) return
        entries[uri]?.let { it.listenedMs += deltaMs }
        val day = nowMs / DAY_MS
        daily[day] = (daily[day] ?: 0L) + deltaMs
        dirty = true
    }

    /** Writes pending [addListenTime] accumulation, if any. */
    fun flush() {
        if (dirty) persist()
    }

    private var dirty = false

    private fun newestStamp(): Long = entries.values.maxOfOrNull { it.lastPlayedMs } ?: 0L

    fun recentlyPlayed(limit: Int = 20): List<Entry> = entries.values.sortedByDescending { it.lastPlayedMs }.take(limit)

    fun mostPlayed(limit: Int = 20): List<Entry> =
        entries.values
            .filter { it.playCount > 0 }
            .sortedWith(compareByDescending<Entry> { it.playCount }.thenByDescending { it.listenedMs })
            .take(limit)

    fun entryFor(uri: String): Entry? = entries[uri]

    fun stats(nowMs: Long = System.currentTimeMillis()): Stats {
        val today = nowMs / DAY_MS
        val week = (WEEK_DAYS - 1 downTo 0).map { back -> daily[today - back] ?: 0L }
        val byArtist = HashMap<String, Long>()
        for (e in entries.values) {
            if (e.artist.isBlank()) continue
            byArtist[e.artist] = (byArtist[e.artist] ?: 0L) + e.listenedMs
        }
        val top = byArtist.entries.maxByOrNull { it.value }?.takeIf { it.value > 0 }
        return Stats(
            trackCount = entries.size,
            totalPlays = entries.values.sumOf { it.playCount },
            totalListenedMs = entries.values.sumOf { it.listenedMs },
            week = week,
            topArtist = top?.key,
            topArtistMs = top?.value ?: 0L,
        )
    }

    private fun persist() {
        runCatching {
            val arr = JSONArray()
            // Cap stored history so the file stays small.
            entries.values.sortedByDescending { it.lastPlayedMs }.take(200).forEach { e ->
                arr.put(
                    JSONObject()
                        .put("uri", e.uri)
                        .put("last", e.lastPlayedMs)
                        .put("count", e.playCount)
                        .put("title", e.title)
                        .put("artist", e.artist)
                        .put("ms", e.listenedMs),
                )
            }
            val days = JSONArray()
            val cutoff = System.currentTimeMillis() / DAY_MS - KEEP_DAYS
            daily.entries.filter { it.key >= cutoff }.sortedBy { it.key }.forEach { (day, ms) ->
                days.put(JSONObject().put("day", day).put("ms", ms))
            }
            file.writeText(JSONObject().put("tracks", arr).put("daily", days).toString())
            dirty = false
        }
    }

    companion object {
        /** Days on Home's listening bar. */
        const val WEEK_DAYS = 7

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Per-day totals older than this are dropped on the next write. */
        private const val KEEP_DAYS = 60L
    }
}
