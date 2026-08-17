package dev.geode.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.geode.analysis.PlaybackMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The sleep timer, hosted next to the player rather than in a ViewModel.
 *
 * It lives here because the countdown must share the player's lifetime, not
 * the screen's: playback continues in [PlaybackService] after the last
 * Activity is swiped away, and a timer riding a `viewModelScope` dies at that
 * exact moment - the one moment the user is relying on it, having set a timer
 * and put the phone down. The scope passed in belongs to [PlaybackSession],
 * so the timer runs for as long as the player exists and not one tick longer.
 *
 * "Let the track finish" waits for the *current item's play-through to end*,
 * not for `isPlaying` to go false. On a queue ExoPlayer auto-advances without
 * `isPlaying` ever dropping, and under REPEAT_ONE it never drops at all - a
 * `while (isPlaying)` wait plays music all night. So expiry arms a listener
 * and pauses on the first media-item transition (auto-advance, a repeat-one
 * restart, a manual skip - any of them means the track the user asked to
 * finish has finished), on the player ending or idling, or on the user
 * pausing themselves.
 *
 * The pre-expiry volume fade is skipped entirely in finish-track mode:
 * fading to silence at expiry and then snapping back up for the remainder of
 * the track is a pop, not a feature.
 *
 * Main thread only, like everything else that talks to the player.
 */
class SleepTimer internal constructor(
    private val player: Player,
    private val scope: CoroutineScope,
) {
    private val _remainingMs = MutableStateFlow<Long?>(null)

    /**
     * Remaining time, or null when no timer runs. 0 while a finish-track
     * timer has expired and is waiting out the current song.
     */
    val remainingMs: StateFlow<Long?> = _remainingMs

    /**
     * Where fade volumes go while a screen is attached, so the UI can fold
     * the sleep fade into its own volume mix (pause fades, etc.) instead of
     * having two writers race over [Player.setVolume]. With no screen - the
     * exact situation this class exists for - the timer writes the player's
     * volume itself.
     */
    @Volatile
    var onFadeVolume: ((Float) -> Unit)? = null

    private var job: Job? = null

    /** True while a timer is counting down or waiting out a track. */
    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * Starts (or restarts) the countdown. Unless [finishTrack] is set, the
     * volume ramps to zero over the final [PlaybackMath.SLEEP_FADE_MS] and
     * the player pauses at expiry; full volume is always restored afterwards
     * so the next play is not mysteriously silent.
     */
    fun start(
        minutes: Int,
        finishTrack: Boolean,
    ) {
        cancel()
        if (minutes <= 0) return
        lateinit var started: Job
        started =
            scope.launch {
                try {
                    val endMs = SystemClock.elapsedRealtime() + minutes * 60_000L
                    while (true) {
                        val remaining = endMs - SystemClock.elapsedRealtime()
                        if (remaining <= 0L) break
                        _remainingMs.value = remaining
                        if (!finishTrack) applyFade(PlaybackMath.sleepFadeVolume(remaining))
                        delay(if (remaining <= PlaybackMath.SLEEP_FADE_MS) 100 else 500)
                    }
                    if (finishTrack && playbackOngoing()) {
                        _remainingMs.value = 0L
                        awaitCurrentTrackDone()
                    }
                    player.pause()
                } finally {
                    // Runs on cancel() too. Volume always comes back; the
                    // shared handle is only cleared if it still points at this
                    // job, so a cancel-then-restart cannot have the dying
                    // job's cleanup clobber the new timer's state.
                    applyFade(1f)
                    if (job === started) {
                        job = null
                        _remainingMs.value = null
                    }
                }
            }
        job = started
    }

    /** Stops the timer without pausing, and restores full volume. */
    fun cancel() {
        val running = job
        job = null
        _remainingMs.value = null
        running?.cancel()
        applyFade(1f)
    }

    private fun applyFade(volume: Float) {
        val hook = onFadeVolume
        if (hook != null) hook(volume) else player.volume = volume
    }

    /** Same reading as [PlaybackSession.playbackWanted]: intent, not sound. */
    private fun playbackOngoing(): Boolean =
        player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED

    /**
     * Suspends until the playing item's play-through is over, by any route:
     * auto-advance to the next item, a repeat-one restart (which real
     * ExoPlayers report both as a transition and as an AUTO_TRANSITION
     * discontinuity - [done] is idempotent so both arriving is fine), the
     * queue ending, or the user pausing/stopping themselves.
     */
    private suspend fun awaitCurrentTrackDone() =
        suspendCancellableCoroutine { cont ->
            val listener =
                object : Player.Listener {
                    override fun onMediaItemTransition(
                        mediaItem: MediaItem?,
                        reason: Int,
                    ) = done()

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) done()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) done()
                    }

                    override fun onPlayWhenReadyChanged(
                        playWhenReady: Boolean,
                        reason: Int,
                    ) {
                        if (!playWhenReady) done()
                    }

                    private fun done() {
                        player.removeListener(this)
                        if (cont.isActive) cont.resume(Unit)
                    }
                }
            player.addListener(listener)
            cont.invokeOnCancellation { player.removeListener(listener) }
        }
}
