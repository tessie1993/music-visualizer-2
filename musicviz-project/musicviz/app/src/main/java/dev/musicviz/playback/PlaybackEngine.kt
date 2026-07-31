package dev.musicviz.playback

import android.content.Context
import dev.musicviz.audio.AudioFxController

/**
 * The one player in the process, and the effects chain bolted to its audio
 * session.
 *
 * Audio has to outlive whatever is on screen: a ViewModel dies when its
 * Activity finishes, and music must not stop because the user swiped away from
 * the app. So the player is owned here — created once, on first use, against
 * the application context — and both PlayerViewModel and [PlaybackService]
 * borrow the same instance.
 *
 * Teardown is the delicate part. The service is destroyed when playback is
 * done, but the process can outlive it, and a ViewModel built moments earlier
 * would then be holding a released ExoPlayer, which throws on every call. So
 * the UI takes an explicit hold ([acquireForUi]) and the service only frees the
 * player when no hold is outstanding ([releaseIfUnused]). The two orderings
 * both come out right: a hold taken first blocks the release, and a release
 * that lands first just means the next caller gets a fresh player.
 */
object PlaybackEngine {
    private var app: Context? = null
    private var controller: PlaybackController? = null
    private var audioFx: AudioFxController? = null
    private var uiHolds = 0

    /**
     * Drops everything if [context] belongs to a different Application than
     * what is cached.
     *
     * In the app this never fires — an Application is a process singleton, so
     * the check is free. Under Robolectric it is what keeps the suite honest:
     * each test method gets a fresh Application and a fresh main Looper, and
     * an ExoPlayer built in an earlier test would still be bound to the dead
     * one, so the next test would drive a player whose handler goes nowhere.
     */
    private fun rebindTo(context: Context): Context {
        val current = context.applicationContext
        if (app !== current) {
            audioFx?.release()
            audioFx = null
            controller?.release()
            controller = null
            uiHolds = 0
            app = current
        }
        return current
    }

    /** The process-wide player, created on first call. Main thread only. */
    @Synchronized
    fun controller(context: Context): PlaybackController {
        val current = rebindTo(context)
        return controller ?: PlaybackController(current).also { controller = it }
    }

    /**
     * The equalizer/bass/loudness chain. Lives here rather than in the
     * ViewModel because it is attached to the player's audio session: tearing
     * it down when a screen goes away would silently drop the user's EQ from
     * music that is still playing.
     */
    @Synchronized
    fun audioFx(context: Context): AudioFxController {
        val current = rebindTo(context)
        return audioFx ?: AudioFxController(current).also { audioFx = it }
    }

    /**
     * Takes the player *and* a hold that keeps it alive. Every caller must
     * release the hold with [releaseUi] when it goes away.
     */
    @Synchronized
    fun acquireForUi(context: Context): PlaybackController {
        uiHolds++
        return controller(context)
    }

    /** Drops a hold taken by [acquireForUi]. Does not stop playback. */
    @Synchronized
    fun releaseUi() {
        if (uiHolds > 0) uiHolds--
    }

    /**
     * Frees the player and its effects, unless a screen still holds it. Called
     * when the playback service is destroyed — that is, once playback is over
     * and the user has dismissed the app.
     */
    @Synchronized
    fun releaseIfUnused() {
        if (uiHolds > 0) return
        audioFx?.release()
        audioFx = null
        controller?.release()
        controller = null
        app = null
    }
}
