package dev.synesthesia.feature.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Player foundation skeleton (M7-foundation, data-flow law step 1).
 * Owns the ExoPlayer + MediaSession lifecycle. The PCM tap (PlayerTapSource
 * wrapping TeeAudioProcessor into :core:audio's SampleRing) attaches here next.
 * Platform checklist (blueprint P3) lands with real playback: FGS type,
 * POST_NOTIFICATIONS flow, wake/onTaskRemoved policy.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
