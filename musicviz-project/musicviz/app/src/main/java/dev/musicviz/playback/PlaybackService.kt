package dev.musicviz.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Keeps playback alive while MusicViz is not on screen, and publishes it to
 * the rest of the system.
 *
 * The MediaSession is what earns the lock-screen and notification transport
 * controls, Bluetooth and wired headset buttons, watches, and Android Auto —
 * they all talk to the session, so they are one object rather than one
 * integration each. [MediaSessionService] handles the media notification and
 * the foreground promotion itself; this class only supplies the player and
 * decides when the service is finished.
 *
 * The player comes from [PlaybackEngine], not from here, so that the UI and
 * the service drive the same instance: a second ExoPlayer would show correct
 * controls over silent audio.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session =
            MediaSession
                .Builder(this, PlaybackEngine.controller(this).player)
                // Tapping the notification reopens MusicViz where the user
                // left it rather than starting a second task.
                .setSessionActivity(openAppIntent())
                .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * The user swiped MusicViz out of Recents. Paused audio has no reason to
     * hold a foreground service alive, so the service stops; playing audio
     * keeps going, which is the whole point of the service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    /**
     * The session is always released here. The player only follows if no screen
     * is holding it — see [PlaybackEngine] for why that check has to exist.
     */
    override fun onDestroy() {
        session?.release()
        session = null
        PlaybackEngine.releaseIfUnused()
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        // getLaunchIntentForPackage is nullable, and PendingIntent.getActivity
        // is not: fall back to an explicit MAIN intent at our own package.
        val launch =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        /**
         * Makes sure the service is running so playback survives the app going
         * to the background. Safe to call repeatedly — starting an already
         * started service just delivers another (ignored) start command.
         *
         * Called while the app is in the foreground, which is what makes the
         * plain `startService` legal: the service promotes itself once the
         * session reports playback, and Android only refuses foreground
         * service starts that originate from the background.
         */
        fun ensureRunning(context: Context) {
            runCatching {
                context.startService(Intent(context, PlaybackService::class.java))
            }
        }
    }
}
