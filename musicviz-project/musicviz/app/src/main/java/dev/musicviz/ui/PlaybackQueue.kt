package dev.musicviz.ui

/**
 * Queue-building rules for the transport, kept as pure functions so the
 * behaviour behind Next / Previous is unit-testable without an ExoPlayer.
 *
 * The defect these exist for: tapping a track called
 * `setMediaItems(listOf(one))`, so the player held a ONE-item queue and both
 * transport buttons were no-ops - Next and Previous "only worked when a
 * playlist was active". A tap now opens the list the track belongs to, at
 * that track, which is what every other music player does.
 */
object PlaybackQueue {
    /**
     * How far into a track Previous stops meaning "the one before" and starts
     * meaning "start this one over". Three seconds is the long-standing
     * convention (CD players, every mainstream music app, and Media3's own
     * `DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION`).
     */
    const val PREV_RESTART_MS: Long = 3_000L

    /**
     * Largest queue a single tap builds, centred on the tapped track.
     *
     * A device index can hold thousands of rows and each queue entry becomes a
     * `MediaSource` eagerly inside `setMediaItems`, so an uncapped queue turns
     * one tap into thousands of allocations on the main thread. The window is
     * generous enough that walking off either end is a deliberate act, and
     * [PlayerViewModel.next] wraps when it happens, so nothing is unreachable.
     */
    const val MAX_QUEUE = 1001

    /** A queue slice plus where playback starts inside it. */
    data class Window(
        val tracks: List<QueueTrack>,
        val startIndex: Int,
    )

    /**
     * Clamps [tracks] to [MAX_QUEUE] entries centred on [startUri].
     *
     * A [startUri] the list does not contain yields a one-track queue for it
     * rather than silently playing the wrong row - the caller asked for that
     * track, and a stale list is not a reason to play a different one.
     */
    fun window(
        tracks: List<QueueTrack>,
        startUri: String,
    ): Window {
        val at = tracks.indexOfFirst { it.uri == startUri }
        if (at < 0) return Window(listOf(QueueTrack(startUri)), 0)
        if (tracks.size <= MAX_QUEUE) return Window(tracks, at)
        val half = MAX_QUEUE / 2
        // Slide the window inside the list instead of shrinking it at the
        // ends, so a track near either edge still gets a full-size queue.
        val from = (at - half).coerceIn(0, tracks.size - MAX_QUEUE)
        return Window(tracks.subList(from, from + MAX_QUEUE), at - from)
    }

    /**
     * The list a single-track play should join, best first: the ordering the
     * user was last looking at, then the device index, then the imported
     * library. Falls back to the track on its own when nothing contains it
     * (a freshly opened file, a history entry whose source list is gone).
     *
     * Ordering matters: [browse] is what the user can actually see, so a hit
     * there beats the device index even though both contain the track.
     */
    fun contextFor(
        uri: String,
        browse: List<QueueTrack>,
        deviceTracks: List<DeviceTrack>,
        libraryTracks: List<LibraryTrack>,
    ): List<QueueTrack> {
        browse.takeIf { list -> list.any { it.uri == uri } }?.let { return it }
        deviceTracks.takeIf { list -> list.any { it.uri == uri } }?.let { return it.map(::queueTrack) }
        libraryTracks.takeIf { list -> list.any { it.uri == uri } }?.let { return it.map(::queueTrack) }
        return listOf(QueueTrack(uri))
    }

    /** Device index row -> queue entry, carrying the metadata it already has. */
    fun queueTrack(track: DeviceTrack): QueueTrack = QueueTrack(track.uri, track.title, track.artist)

    /** Imported library row -> queue entry. */
    fun queueTrack(track: LibraryTrack): QueueTrack = QueueTrack(track.uri, track.title, track.artist)
}
