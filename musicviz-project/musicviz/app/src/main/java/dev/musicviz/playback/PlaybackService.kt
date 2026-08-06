package dev.musicviz.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.musicviz.ui.HistoryStore

/**
 * Keeps music playing when MusicViz is not on screen, and publishes what is
 * playing to the rest of the system.
 *
 * Two jobs, and the same object does both. Android only lets an app keep audio
 * going in the background from a foreground service, and the MediaSession is
 * what earns the notification transport, the lock-screen controls, wired and
 * Bluetooth media buttons, watches and Android Auto - they all talk to the
 * session, so they are one integration rather than one each.
 *
 * The player comes from [PlaybackEngine] rather than being built here, and that
 * is the load-bearing decision: the ExoPlayer the UI drives is the one this
 * session publishes, so the notification controls the audio the user can hear
 * and the PCM tap teed off that player keeps feeding the visualizer. A player
 * built here instead would be a second one - correct-looking controls over
 * silence.
 *
 * [MediaSessionService] posts and updates the media notification itself and
 * promotes the service to the foreground when playback starts, so there is no
 * notification code here. That also keeps this service out of the way of
 * [dev.musicviz.audio.PlaybackCaptureService], which runs its own foreground
 * notification for a different reason: the two have different service types,
 * different notification ids and different channels, and Android is happy to
 * run both at once.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        session =
            MediaSession
                .Builder(this, PlaybackEngine.acquireForService(this).player)
                // Tapping the notification reopens MusicViz where the user left
                // it rather than starting a second copy of the app.
                .setSessionActivity(openAppIntent())
                .setCallback(ResumptionCallback(this))
                .build()
    }

    /**
     * What the system gets when it asks this session to resume playback with
     * an empty player - the path behind a media button or System UI's media
     * resumption after the process has been killed. Without it those
     * controls connect, find nothing loaded, and silently do nothing.
     */
    private class ResumptionCallback(
        private val context: Context,
    ) : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resumption =
                lastPlayedResumption(context)
                    // The same refusal Media3's default gives: a failed future
                    // makes the controller drop the request cleanly, rather
                    // than "succeeding" with an empty queue.
                    ?: return Futures.immediateFailedFuture(
                        UnsupportedOperationException("nothing was ever played"),
                    )
            return Futures.immediateFuture(resumption)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // onTaskRemoved is deliberately not overridden. Media3's own implementation
    // is already the behaviour we want and states it more precisely than a
    // hand-rolled one could: if the service is in the foreground AND a session
    // is actually playing, swiping the app out of Recents changes nothing -
    // which is the entire point of this service - and otherwise it pauses every
    // player and stops itself, so a paused player never leaves a service and a
    // notification standing over nothing.

    /**
     * The session is always released here, and the hold on the player is always
     * given back. Whether that frees the player is [PlaybackEngine]'s decision,
     * not this one: a screen may have been opened while the service was
     * finishing, and releasing a player it is holding would make every call it
     * makes throw.
     */
    override fun onDestroy() {
        session?.release()
        session = null
        PlaybackEngine.releaseService()
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        // getLaunchIntentForPackage is nullable and PendingIntent.getActivity is
        // not, so fall back to an explicit MAIN intent at our own package.
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
         * The most recently played track as a resumption queue, or null when
         * nothing was ever played. Starts from 0:00 because positions are not
         * persisted anywhere in the app (HistoryStore records play counts and
         * listened time, not offsets) - resuming at the start is the same
         * behaviour the in-app Resume card gives.
         *
         * Internal (not private) so the headless suite can pin it without
         * standing up a MediaSession, which Robolectric cannot drive.
         */
        @OptIn(UnstableApi::class)
        internal fun lastPlayedResumption(context: Context): MediaSession.MediaItemsWithStartPosition? {
            val last = HistoryStore(context).recentlyPlayed(1).firstOrNull() ?: return null
            val item =
                MediaItem
                    .Builder()
                    .setUri(last.uri)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setTitle(last.title)
                            .setArtist(last.artist)
                            .build(),
                    ).build()
            return MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L)
        }

        /**
         * Makes sure the service exists, so that playback survives the app
         * going away. Safe to call repeatedly - starting an already started
         * service just delivers another start command, which this one ignores.
         *
         * Called the moment playback actually begins, which is what makes the
         * plain `startService` legal: Android only refuses service starts that
         * originate from the background, and the service promotes itself to the
         * foreground on its own once the session reports playback. Starting it
         * any earlier would mean a media notification for a player with nothing
         * in it.
         *
         * The failure is swallowed on purpose. The one way to reach it is
         * playback starting while the app is already in the background, and the
         * only way playback can start there is a transport control - which
         * exists only because this service is already running.
         */
        fun ensureRunning(context: Context) {
            runCatching {
                context.startService(Intent(context, PlaybackService::class.java))
            }
        }

        /**
         * Takes the service down. Called when the last screen goes away with
         * nothing playing: a media service over a paused player is a
         * notification the user has no reason to look at and a process Android
         * has no reason to keep.
         */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, PlaybackService::class.java))
            }
        }
    }
}
