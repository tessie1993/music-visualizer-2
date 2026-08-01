package dev.musicviz.audio

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * The empty half of "what is Spotify playing right now".
 *
 * `MediaSessionManager.getActiveSessions` is gated on the caller being an
 * enabled notification listener, and the only way to become one is to declare
 * a [NotificationListenerService] and have the user switch it on. This service
 * therefore does nothing at all: it posts no notifications, reads none, and
 * overrides no callbacks. Its entire job is to exist so the system will answer
 * the media-session question.
 *
 * Deliberately not used to read notification CONTENT. Every other music app
 * that wants "now playing" from a competitor scrapes notifications; this one
 * asks the media session, which is the documented API for it and carries
 * structured metadata instead of a formatted string.
 */
class MusicVizNotificationListener : NotificationListenerService()

/**
 * What some other app on this device is playing.
 *
 * Pairs with [PlaybackCapture]: the capture supplies the sound, this supplies
 * the name of it. They are independent on purpose - the visuals work with just
 * the audio, the labels work with just the session, and either can be granted
 * without the other.
 *
 * It is also what makes the "this app forbids capture" diagnosis honest. A
 * silent capture on its own is ambiguous: nothing playing looks exactly like
 * something playing that refuses to be heard. With a session saying "Spotify,
 * STATE_PLAYING" next to four seconds of digital silence, it is not ambiguous
 * any more.
 */
class NowPlayingBridge(
    private val context: Context,
) {
    /** A media session belonging to another app. */
    data class External(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val artist: String,
        val playing: Boolean,
    )

    private val component = ComponentName(context, MusicVizNotificationListener::class.java)

    /**
     * True when the user has switched this app's notification listener on.
     *
     * Read from the raw secure setting rather than through a support library:
     * the value is a flat colon-separated list of component names, and every
     * wrapper around it is doing this same string check.
     */
    fun hasAccess(): Boolean {
        val enabled =
            runCatching {
                Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS)
            }.getOrNull().orEmpty()
        return enabled.split(':').any {
            runCatching { ComponentName.unflattenFromString(it) }.getOrNull() == component
        }
    }

    /** The system screen where that switch lives. */
    fun settingsIntent(): android.content.Intent =
        android.content
            .Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The most interesting foreign session, or null.
     *
     * "Most interesting" is a playing one before a paused one, and never this
     * app's own session - MusicViz pauses itself while capture is on, but a
     * lingering paused session would still win over the app the user is
     * actually listening to.
     */
    fun current(): External? {
        if (!hasAccess()) return null
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions =
            runCatching { manager.getActiveSessions(component) }.getOrNull().orEmpty()
        val best =
            sessions
                .filter { it.packageName != context.packageName }
                .maxByOrNull { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) 1 else 0 }
                ?: return null
        val metadata = best.metadata
        return External(
            packageName = best.packageName,
            appLabel = appLabel(best.packageName),
            title =
                metadata
                    ?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                    .orEmpty(),
            artist =
                metadata
                    ?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                    .orEmpty(),
            playing = best.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    private fun appLabel(packageName: String): String =
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull() ?: packageName

    private companion object {
        const val ENABLED_LISTENERS = "enabled_notification_listeners"
    }
}
