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
 *
 * Reads and mutations are cheap and happen wherever the caller is - usually
 * the main thread, from a player event or from Home reading the numbers back.
 * The expensive half, serializing up to 200 entries and rewriting the file, is
 * pushed onto [writer]: [recordPlay] fires on every track transition, so once
 * per tap while skipping a queue, and that was the app's heaviest main-thread
 * disk activity. [lock] is what makes that safe - the writer serializes the
 * same maps the main thread is mutating.
 */
class HistoryStore(
    context: Context,
) {
    private val file = java.io.File(context.filesDir, "history.json")

    /** Held across every read, mutation and serialization of [entries]/[daily]. */
    private val lock = Any()

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
        // Deliberately synchronous, unlike the writes. The ViewModel's
        // auto-resume reads the newest entry back a few lines after building
        // this store and must do so BEFORE the player listener registers, or
        // preparing the resumed track books a play the user never made. One
        // capped file (200 entries) is the cheapest of the reads that startup
        // makes, and buying it back would cost that ordering.
        synchronized(lock) {
            runCatching {
                if (file.exists()) load(file.readText())
            }
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
        synchronized(lock) {
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
        }
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
        synchronized(lock) {
            entries[uri]?.let { it.listenedMs += deltaMs }
            val day = nowMs / DAY_MS
            daily[day] = (daily[day] ?: 0L) + deltaMs
            dirty = true
        }
    }

    /** Queues a write of pending [addListenTime] accumulation, if any. */
    fun flush() {
        if (dirty) persist()
    }

    /**
     * Blocks until every queued write has reached the disk.
     *
     * For the one caller that cannot come back later: the ViewModel being torn
     * down, which is the last moment the process is guaranteed to be alive. A
     * write still sitting on [writer] there is a play that never happened.
     */
    fun awaitWrites() {
        runCatching { writer.submit {}.get(AWAIT_WRITE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    @Volatile
    private var dirty = false

    private val writeQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun newestStamp(): Long = entries.values.maxOfOrNull { it.lastPlayedMs } ?: 0L

    fun recentlyPlayed(limit: Int = 20): List<Entry> =
        synchronized(lock) { entries.values.sortedByDescending { it.lastPlayedMs }.take(limit) }

    fun mostPlayed(limit: Int = 20): List<Entry> =
        synchronized(lock) {
            entries.values
                .filter { it.playCount > 0 }
                .sortedWith(compareByDescending<Entry> { it.playCount }.thenByDescending { it.listenedMs })
                .take(limit)
        }

    fun entryFor(uri: String): Entry? = synchronized(lock) { entries[uri] }

    fun stats(nowMs: Long = System.currentTimeMillis()): Stats =
        synchronized(lock) {
            val today = nowMs / DAY_MS
            val week = (WEEK_DAYS - 1 downTo 0).map { back -> daily[today - back] ?: 0L }
            val byArtist = HashMap<String, Long>()
            for (e in entries.values) {
                if (e.artist.isBlank()) continue
                byArtist[e.artist] = (byArtist[e.artist] ?: 0L) + e.listenedMs
            }
            val top = byArtist.entries.maxByOrNull { it.value }?.takeIf { it.value > 0 }
            Stats(
                trackCount = entries.size,
                totalPlays = entries.values.sumOf { it.playCount },
                totalListenedMs = entries.values.sumOf { it.listenedMs },
                week = week,
                topArtist = top?.key,
                topArtistMs = top?.value ?: 0L,
            )
        }

    /**
     * Queues a write of the CURRENT state onto [writer], coalescing bursts.
     *
     * The flag is cleared before the write runs rather than after, so a change
     * that lands while the file is being written queues a fresh write instead
     * of being folded into one that already read past it - the last play of a
     * skip-happy minute has to be the one on disk.
     */
    private fun persist() {
        if (!writeQueued.compareAndSet(false, true)) return
        runCatching {
            writer.execute {
                writeQueued.set(false)
                writeNow()
            }
        }
    }

    private fun writeNow() {
        runCatching {
            val text =
                synchronized(lock) {
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
                    JSONObject().put("tracks", arr).put("daily", days).toString()
                }
            file.writeText(text)
            dirty = false
        }
    }

    companion object {
        /** Days on Home's listening bar. */
        const val WEEK_DAYS = 7

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Per-day totals older than this are dropped on the next write. */
        private const val KEEP_DAYS = 60L

        /**
         * Longest [awaitWrites] will hold a teardown up. A history write is a
         * few tens of kilobytes; anything past this is a wedged filesystem, and
         * losing the last play beats refusing to let the app close.
         */
        private const val AWAIT_WRITE_TIMEOUT_MS = 2_000L

        /**
         * One writer thread for every store, so writes stay ordered - the last
         * one queued is the last one on disk - and a process with several
         * stores alive (tests build one per case) does not accumulate threads.
         * Daemon: a pending write must never be what keeps the JVM up.
         */
        private val writer =
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "musicviz-history").apply { isDaemon = true }
            }
    }
}
