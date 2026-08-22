package dev.geode.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the app remembers about your listening: which tracks came back, which
 * ones you keep coming back to, and how much time actually went into them.
 * It is what auto-resume, shuffle-all and "most played" draw on. JSON in
 * files dir - one capped file, rewritten whole, is not worth a Room database.
 *
 * Two things are recorded, and they are deliberately different measurements.
 * [recordPlay] counts a track being STARTED, which is what "most played"
 * means to a person. [addListenTime] accumulates milliseconds actually spent
 * playing, which is what breaks most-played ties with real listening rather
 * than a count of how often you skipped past a track.
 *
 * Reads and mutations are cheap and happen wherever the caller is - usually
 * the main thread, from a player event or from a screen reading the numbers
 * back. The expensive half, serializing up to 200 entries and rewriting the
 * file, is pushed onto [writer]: [recordPlay] fires on every track
 * transition, so once per tap while skipping a queue, and that was the app's
 * heaviest main-thread disk activity. [lock] is what makes that safe - the
 * writer serializes the same map the main thread is mutating.
 *
 * That file is the only copy of everything above, and it is replaced whole on
 * every write, so both halves of losing it are guarded. The write itself goes
 * through [AtomicWrite] rather than `File.writeText`, which truncates to zero
 * before it writes anything - process death inside that window used to leave a
 * zero-length history.json, a file that parses perfectly as "nothing was ever
 * played". And the read distinguishes a file it could not read from a file
 * that is not there (see [readLocked]), because starting from an empty history
 * and writing it back is how a single bad read becomes permanent.
 */
class HistoryStore(
    context: Context,
) {
    private val file = java.io.File(context.filesDir, "history.json")

    /** Held across every read, mutation and serialization of [entries]. */
    private val lock = Any()

    /** uri -> what we know about it */
    private val entries = LinkedHashMap<String, Entry>()

    /**
     * False once [readLocked] has found a history file it could not read at
     * all. It is what stops the store answering an unreadable file by
     * overwriting it with the empty history it fell back to.
     *
     * Declared here, above the `init` block that decides it, because a
     * property initializer runs in declaration order: written further down it
     * would be reset to true immediately after [readLocked] cleared it.
     * Volatile because it is decided on the thread that builds the store and
     * obeyed on [writer].
     */
    @Volatile
    private var readable = true

    data class Entry(
        val uri: String,
        var lastPlayedMs: Long,
        var playCount: Int,
        var title: String,
        var artist: String = "",
        /** Milliseconds of this track actually played, across all plays. */
        var listenedMs: Long = 0L,
    )

    init {
        // Deliberately synchronous, unlike the writes. The ViewModel's
        // auto-resume reads the newest entry back a few lines after building
        // this store and must do so BEFORE the player listener registers, or
        // preparing the resumed track books a play the user never made. One
        // capped file (200 entries) is the cheapest of the reads that startup
        // makes, and buying it back would cost that ordering.
        synchronized(lock) { readLocked() }
    }

    /**
     * Loads the file into [entries], or decides that it must not be
     * written over. Callers hold [lock].
     *
     * The three outcomes are deliberately kept apart. No file at all is a
     * fresh install and starts empty. Content that reads but does not parse is
     * moved aside by [AtomicWrite.quarantine]: treating it as "nothing was
     * ever played" would let the very next [recordPlay] persist that emptiness
     * over the user's whole listening record, so the bytes are kept where they
     * can still be recovered while the store starts fresh rather than refusing
     * to record anything ever again. A file that cannot be READ - a directory
     * in its place, a permission problem, a failing disk - is the case nothing
     * can be salvaged from, so this instance simply never writes and leaves
     * whatever is there intact; the next store built retries the read.
     */
    private fun readLocked() {
        if (!file.exists()) return
        val text = runCatching { file.readText() }.getOrNull()
        if (text == null) {
            readable = false
            return
        }
        if (text.isBlank()) return
        if (runCatching { load(text) }.isSuccess) return
        // load() fills the map as it walks the document, so a throw part-way
        // through leaves half a history behind; it goes with the file.
        entries.clear()
        AtomicWrite.quarantine(file)
    }

    private fun load(text: String) {
        // v1 wrote a bare array of entries. Reading it back as one keeps every
        // existing install's history instead of silently starting over.
        if (text.trimStart().startsWith("[")) {
            readEntries(JSONArray(text))
            return
        }
        readEntries(JSONObject(text).optJSONArray("tracks") ?: JSONArray())
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
     * Adds real playing time to a track.
     *
     * Called from the player's polling tick, so it does NOT persist: writing
     * the file twice a second would be the app's busiest disk activity. The
     * caller flushes on the events that matter (track change, pause, the
     * ViewModel being torn down) via [flush].
     */
    fun addListenTime(
        uri: String,
        deltaMs: Long,
    ) {
        if (deltaMs <= 0) return
        synchronized(lock) {
            entries[uri]?.let { it.listenedMs += deltaMs }
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
    fun awaitWrites(timeoutMs: Long = AWAIT_WRITE_TIMEOUT_MS) {
        runCatching { writer.submit {}.get(timeoutMs.coerceAtLeast(0L), java.util.concurrent.TimeUnit.MILLISECONDS) }
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

    /**
     * Serializes the current state and publishes it. Runs on [writer].
     *
     * The serialization stays inside [lock] and the write stays outside it:
     * the main thread must not be blocked behind a disk write, and that is the
     * whole reason this method is on a background thread at all. [dirty] is
     * only cleared when the bytes actually landed, so a write that failed is
     * retried by the next [flush] instead of being forgotten.
     */
    private fun writeNow() {
        if (!readable) return
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
                    JSONObject().put("tracks", arr).toString()
                }
            if (AtomicWrite.text(file, text)) dirty = false
        }
    }

    companion object {
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
                Thread(r, "geode-history").apply { isDaemon = true }
            }
    }
}
