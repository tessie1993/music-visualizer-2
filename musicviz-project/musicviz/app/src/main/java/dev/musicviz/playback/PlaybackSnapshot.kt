package dev.musicviz.playback

/**
 * Everything the UI shows about the transport, sampled from the player.
 *
 * Deliberately free of media3 types so the headless suite can build one:
 * [repeatMode] is the raw `Player.REPEAT_MODE_*` int (0 = off, 1 = one,
 * 2 = all) rather than the constant, which is the same widening the
 * persisted [dev.musicviz.ui.PlayerPrefs.repeatMode] already does.
 */
data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val title: String? = null,
    val artist: String? = null,
    val hasMedia: Boolean = false,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
)
