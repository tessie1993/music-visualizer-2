package dev.musicviz.playback

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The sleep timer against a scripted player, pinning the two defects the
 * audit found in the old ViewModel-hosted countdown:
 *
 * - "Let the track finish" used to wait on `while (isPlaying)`, which on a
 *   queue never goes false (auto-advance) and under repeat-one never ends at
 *   all - the timer that never fired. The engine-hosted timer waits for the
 *   current item's play-through to end instead.
 * - The finish-track fade popped: volume ramped to zero at expiry, then
 *   snapped back up for the rest of the song. Finish-track now skips the
 *   fade entirely.
 *
 * Time is Robolectric's: idling the main looper advances SystemClock and
 * runs the coroutine delays in step, so a sixty-minute timer costs nothing.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerTest {
    /**
     * A player whose transport is a script. SimpleBasePlayer derives events
     * (isPlaying, media-item transitions) from state diffs the same way a
     * real player reports them, which is exactly the surface SleepTimer
     * listens to.
     */
    private class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        var playWhenReadyState = true
        var volumeState = 1f
        var index = 0
        var positionMs = 0L

        private val items =
            listOf(
                MediaItemData.Builder("track-a").build(),
                MediaItemData.Builder("track-b").build(),
            )

        override fun getState(): State =
            State
                .Builder()
                .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
                .setPlaylist(items)
                .setCurrentMediaItemIndex(index)
                .setContentPositionMs(positionMs)
                .setPlayWhenReady(playWhenReadyState, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(Player.STATE_READY)
                .setVolume(volumeState)
                .build()

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playWhenReadyState = playWhenReady
            return Futures.immediateVoidFuture()
        }

        override fun handleSetVolume(volume: Float): ListenableFuture<*> {
            volumeState = volume
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            index = mediaItemIndex
            this.positionMs = if (positionMs == androidx.media3.common.C.TIME_UNSET) 0L else positionMs
            return Futures.immediateVoidFuture()
        }

        /** What ExoPlayer does between tracks: the item changes, isPlaying never blinks. */
        fun autoAdvance() {
            index = 1
            positionMs = 0L
            invalidateState()
        }
    }

    private val player = FakePlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timer = SleepTimer(player, scope)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test
    fun `expiry pauses the player and gives the volume back`() {
        timer.start(minutes = 1, finishTrack = false)
        val remaining = timer.remainingMs.value
        assertTrue("countdown starts immediately", remaining != null && remaining in 1..60_000L)

        idleFor(59_000)
        assertTrue("still playing before expiry", player.playWhenReadyState)
        assertTrue("volume fades over the final seconds", player.volumeState < 1f)

        idleFor(2_000)
        assertFalse("expiry pauses", player.playWhenReadyState)
        assertEquals("full volume comes back so the next play is audible", 1f, player.volumeState, 1e-4f)
        assertNull(timer.remainingMs.value)
        assertFalse(timer.isRunning)
    }

    @Test
    fun `finish-track waits for the item to end, not for isPlaying to lie`() {
        // The old `while (player.isPlaying)` wait: on a queue, auto-advance
        // keeps isPlaying true forever, so the timer never paused. The timer
        // must survive expiry with music still going, then pause on the
        // transition - however long that takes.
        timer.start(minutes = 1, finishTrack = true)
        idleFor(61_000)
        assertTrue("the track the user asked to finish is still playing", player.playWhenReadyState)
        assertEquals("expired but waiting reads as 0:00", 0L, timer.remainingMs.value)

        idleFor(240_000) // a long song outlasts any polling assumption
        assertTrue("no amount of waiting pauses mid-track", player.playWhenReadyState)

        player.autoAdvance()
        idleFor(1)
        assertFalse("the play-through ending is what pauses", player.playWhenReadyState)
        assertNull(timer.remainingMs.value)
    }

    @Test
    fun `finish-track never touches the volume until it is done`() {
        // The D13 pop: fading to silence at expiry and snapping back up for
        // the rest of the song. Finish-track skips the fade entirely.
        val fades = mutableListOf<Float>()
        timer.onFadeVolume = { fades += it }
        timer.start(minutes = 1, finishTrack = true)
        idleFor(61_000)
        assertTrue("no fade below full volume in finish-track mode", fades.none { it < 1f })
        player.autoAdvance()
        idleFor(1)
        assertEquals("player volume was never written behind the hook's back", 1f, player.volumeState, 1e-4f)
    }

    @Test
    fun `a seek inside the track does not count as the track ending`() {
        timer.start(minutes = 1, finishTrack = true)
        idleFor(61_000)
        player.seekTo(5_000)
        idleFor(1)
        assertTrue("scrubbing within the song keeps the wait alive", player.playWhenReadyState)
        player.autoAdvance()
        idleFor(1)
        assertFalse(player.playWhenReadyState)
    }

    @Test
    fun `the user pausing during the wait is the end of the timer's job`() {
        timer.start(minutes = 1, finishTrack = true)
        idleFor(61_000)
        player.pause()
        idleFor(1)
        assertFalse(player.playWhenReadyState)
        assertNull("the timer wound down with them", timer.remainingMs.value)
        assertFalse(timer.isRunning)
    }

    @Test
    fun `cancel stops the countdown without pausing and restores volume`() {
        timer.start(minutes = 1, finishTrack = false)
        idleFor(58_000)
        assertTrue("mid-fade", player.volumeState < 1f)
        timer.cancel()
        assertEquals(1f, player.volumeState, 1e-4f)
        assertNull(timer.remainingMs.value)
        idleFor(120_000)
        assertTrue("a cancelled timer never pauses later", player.playWhenReadyState)
    }

    @Test
    fun `restarting replaces the old countdown cleanly`() {
        timer.start(minutes = 60, finishTrack = false)
        timer.start(minutes = 1, finishTrack = false)
        val remaining = timer.remainingMs.value
        assertTrue("the new countdown is the one showing", remaining != null && remaining <= 60_000L)
        idleFor(61_000)
        assertFalse("the new timer fires", player.playWhenReadyState)
        // The superseded job's cleanup must not clobber the live state - the
        // same handle-clobbering shape as the ViewModel's fadeJob defect.
        assertNull(timer.remainingMs.value)
        idleFor(3_600_000)
        assertEquals("the old 60-minute timer is gone for good", 1f, player.volumeState, 1e-4f)
    }

    @Test
    fun `the volume hook keeps the timer out of the player's volume`() {
        val fades = mutableListOf<Float>()
        timer.onFadeVolume = { fades += it }
        timer.start(minutes = 1, finishTrack = false)
        idleFor(61_000)
        assertTrue("fade values reached the hook", fades.any { it < 1f })
        assertEquals("last word restores full volume", 1f, fades.last(), 1e-4f)
        assertEquals("the raw player volume was left alone", 1f, player.volumeState, 1e-4f)
    }
}
