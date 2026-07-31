package dev.musicviz.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.musicviz.ui.MainActivity

/**
 * Foreground media service: publishes a [MediaSession] over the shared
 * [PlaybackEngine] player so playback keeps running with the screen locked or
 * the app backgrounded, with transport controls on the lock screen and in the
 * notification shade.
 *
 * Media3 drives the notification and the foreground transition off the
 * session player's state, so this class only has to own the session's
 * lifetime. MainActivity starts the service while it is visible; nothing here
 * calls startForeground from the background.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Media3 only notices a session when addSession() runs, and it calls that
        // itself from exactly two places: a MediaController connecting through
        // onGetSession, or an ACTION_MEDIA_BUTTON intent. This app drives the
        // ExoPlayer directly and never builds a MediaController, and MainActivity
        // starts the service with an action-less Intent, so neither ever fired.
        // The result was a session that existed but was invisible to
        // MediaNotificationManager: no notification was ever posted and
        // startForeground() was never called, leaving this a plain background
        // service that Android stops once the app goes idle — taking background
        // playback with it. Registering the session here is what actually arms
        // the notification and the foreground transition.
        setListener(ForegroundStartListener())
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        session =
            MediaSession
                .Builder(this, PlaybackEngine.get(application).player)
                // Tapping the notification or the lock-screen artwork returns
                // to the visualizer rather than cold-starting a new task.
                .setSessionActivity(openApp)
                .build()
                .also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Android 12+ refuses a foreground start when the app has no exemption — for
     * example a headset button resuming playback long after the app was swiped
     * away. Media3 surfaces that instead of letting it become an uncaught
     * ForegroundServiceStartNotAllowedException; pausing is the honest response,
     * since audio that cannot hold a foreground service will be killed anyway.
     */
    private inner class ForegroundStartListener : Listener {
        override fun onForegroundServiceStartNotAllowedException() {
            runCatching { session?.player?.pause() }
        }
    }

    /**
     * Swiping the app away should not kill music that is still playing — but
     * leaving a started service behind for a paused, empty player would.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Releases the session only. The player belongs to PlaybackEngine and
        // outlives this service; a ViewModel may still be holding it, and
        // releasing it here would fail every later call with IllegalStateException.
        session?.release()
        session = null
        super.onDestroy()
    }
}
