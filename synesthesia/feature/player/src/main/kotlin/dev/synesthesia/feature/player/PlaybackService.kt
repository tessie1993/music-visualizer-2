package dev.synesthesia.feature.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Player foundation (M7-foundation, data-flow step 1).
 * Owns ExoPlayer + MediaSession; every decoded frame tees into the shared
 * SampleRing through TapAudioProcessor (POST-EQ law applies once EQ lands).
 *
 * Platform checklist status: FGS type + POST_NOTIFICATIONS declared in this
 * module's manifest; wake/onTaskRemoved policy + notification provider land
 * with real queue UI (next block).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var ring: SampleRing

    override fun onCreate() {
        super.onCreate()
        ring = PlayerGraph.buildRing()
        val player = PlayerGraph.buildExoPlayer(this, PlayerGraph.buildTap(ring))
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
