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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
