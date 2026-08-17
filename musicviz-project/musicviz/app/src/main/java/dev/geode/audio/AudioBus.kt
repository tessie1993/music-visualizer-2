package dev.geode.audio

import dev.geode.analysis.AudioFeatures

/**
 * The one place in the process that knows what the audio is doing right now.
 *
 * Exists for the live wallpaper. The wallpaper engine and the app run in the
 * same process but share no object graph - the analysis lives on an
 * Activity-scoped ViewModel, and a wallpaper outlives every Activity. Rather
 * than give the wallpaper a second analyzer (a second FFT, a second beat
 * tracker, and two answers to "was that a beat?"), the ViewModel publishes the
 * features it already computes here and the wallpaper reads them.
 *
 * Deliberately a plain volatile field, not a flow: there is exactly one
 * writer, the reader is a render thread that wants the newest value and never
 * a backlog, and a dropped update is the correct behaviour rather than a lost
 * event.
 *
 * [features] returns null when nothing has published recently, which is what
 * makes the wallpaper's idle path kick in when the app is gone.
 */
object AudioBus {
    /** After this long with no publish, the app is treated as not running. */
    private const val STALE_MS = 1_500L

    @Volatile
    private var latest: AudioFeatures? = null

    @Volatile
    private var latestAtMs: Long = 0L

    /** Called by the app whenever it has a fresh analysis frame. */
    fun publish(features: AudioFeatures) {
        latest = features
        latestAtMs = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * The newest features, or null when the app is not feeding them - the
     * wallpaper's cue to fall back to its own gentle idle motion rather than
     * freeze on the last frame the app happened to leave behind.
     */
    fun features(): AudioFeatures? {
        val f = latest ?: return null
        return if (android.os.SystemClock.elapsedRealtime() - latestAtMs > STALE_MS) null else f
    }

    /** True while something is publishing; for the wallpaper's own reporting. */
    val isLive: Boolean get() = features() != null

    /** Drops the published state (the app stopping playback). */
    fun clear() {
        latest = null
        latestAtMs = 0L
    }

    // ---- Consumer interest ----------------------------------------------
    //
    // The analysis worker costs ~62 wakeups a second, so it runs only while
    // someone is actually watching: the app's screen (the ViewModel holds a
    // count for its whole life) or a visible wallpaper (its feeder holds one
    // while it runs). PlaybackSession registers [onInterestChanged] and
    // starts/stops the analyzer on the edges.

    private val consumers = java.util.concurrent.atomic.AtomicInteger(0)

    /** Fires on every 0->1 and 1->0 interest edge; set by the analysis owner. */
    @Volatile
    var onInterestChanged: (() -> Unit)? = null

    /** True while anything wants live features. */
    val hasConsumers: Boolean get() = consumers.get() > 0

    fun addConsumer() {
        if (consumers.incrementAndGet() == 1) onInterestChanged?.invoke()
    }

    fun removeConsumer() {
        if (consumers.decrementAndGet() == 0) onInterestChanged?.invoke()
    }
}
