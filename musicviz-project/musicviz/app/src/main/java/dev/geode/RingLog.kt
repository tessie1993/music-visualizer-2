package dev.geode

/**
 * A tiny in-memory ring of recent non-fatal failures.
 *
 * The codebase's silent-failure posture (`runCatching { … }.getOrNull()` at
 * every store boundary) is deliberate - a preset that will not parse must not
 * crash the app - but it left nothing behind to debug WITH: an empty preset
 * list, a take that would not load and a texture removal that did nothing all
 * looked identical. Failures noted here cost one string in a bounded ring
 * (no disk, no allocation storm) and surface in two places: appended to
 * `crash-latest.txt` when a crash report is written, and on demand via
 * [dump].
 *
 * Pure JVM on purpose - the headless store tests exercise the callers - so
 * echoing to logcat is injected by [GeodeApp] rather than called directly.
 */
object RingLog {
    private const val CAPACITY = 500

    private val entries = ArrayDeque<String>(CAPACITY)

    /** Set once at app start to also mirror notes into logcat. */
    @Volatile
    var echo: (tag: String, line: String) -> Unit = { _, _ -> }

    /** Records a non-fatal failure; [error]'s type and message ride along. */
    fun note(
        tag: String,
        message: String,
        error: Throwable? = null,
    ) {
        val detail = error?.let { " (${it.javaClass.simpleName}: ${it.message ?: "no message"})" } ?: ""
        val line = "${System.currentTimeMillis()} $tag: $message$detail"
        synchronized(entries) {
            if (entries.size == CAPACITY) entries.removeFirst()
            entries.addLast(line)
        }
        echo(tag, "$message$detail")
    }

    /** Newest-last snapshot of everything currently held. */
    fun dump(): String = synchronized(entries) { entries.joinToString("\n") }

    /** Test seam: a run's notes must not leak into the next test's dump. */
    fun clear() = synchronized(entries) { entries.clear() }
}
