package dev.musicviz.playback

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.ui.PlayerViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Player screen drives the ENGINE's sleep timer, and hosts none of its own.
 *
 * [SleepTimer] was written to fix two defects and then had no reader in
 * `main/`: the shipped countdown was still a `viewModelScope.launch` in
 * PlayerViewModel, so setting a thirty-minute timer and backing out of the app
 * cancelled it silently - `player.pause()` was never reached and the service
 * played on all night - and its finish-track wait was `while (isPlaying)
 * delay(500)`, which on a queue never ends because ExoPlayer auto-advances
 * without isPlaying ever going false.
 *
 * [SleepTimerTest] covers the timer's own behaviour against a scripted player.
 * What is checked HERE is only the seam: that the ViewModel is a remote
 * control for the session's timer rather than a second implementation of one,
 * and that the volume hook is attached while a screen is up and given back
 * when it goes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerDelegationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    /**
     * A screen going away, as Android does it: the store owns the ViewModel
     * and clearing the store is what runs `onCleared`. (It is protected, so a
     * test cannot call it directly - and should not want to.)
     */
    private fun screenGoesAway(vm: PlayerViewModel) {
        androidx.lifecycle.ViewModelStore().apply {
            put("player", vm)
            clear()
        }
    }

    private val viewModelSource: String by lazy { dev.musicviz.ParamSurface.source("ui/PlayerViewModel.kt") }

    @Test
    fun `the screen exposes the session's countdown, not one of its own`() {
        val vm = PlayerViewModel(ctx)
        val session = PlaybackEngine.acquireForUi(ctx)
        assertSame(
            "a ViewModel-owned StateFlow is a second timer waiting to happen",
            session.sleepTimer.remainingMs,
            vm.sleepTimerRemainingMs,
        )
    }

    @Test
    fun `starting a timer from the screen arms the engine's timer`() {
        val vm = PlayerViewModel(ctx)
        val session = PlaybackEngine.acquireForUi(ctx)
        assertFalse(session.sleepTimer.isRunning)

        vm.startSleepTimer(30)
        assertTrue("the engine timer is what runs", session.sleepTimer.isRunning)
        assertNotNull(vm.sleepTimerRemainingMs.value)

        vm.cancelSleepTimer()
        assertFalse(session.sleepTimer.isRunning)
        assertNull(vm.sleepTimerRemainingMs.value)
    }

    @Test
    fun `a zero-minute request is a cancel, as the settings row assumes`() {
        val vm = PlayerViewModel(ctx)
        val session = PlaybackEngine.acquireForUi(ctx)
        vm.startSleepTimer(45)
        assertTrue(session.sleepTimer.isRunning)
        vm.startSleepTimer(0)
        assertFalse(session.sleepTimer.isRunning)
    }

    @Test
    fun `the screen mixes the fade while it is up and hands it back when it goes`() {
        // Two writers on Player.volume (the sleep fade and the pause fade)
        // would overwrite each other's ramp, so while a screen is attached the
        // timer's fade goes through the ViewModel's mixer. Once it is gone
        // there is no mixer, and the timer must go back to writing the
        // player's volume itself - it outlives the screen, which is the whole
        // point of hosting it on the session.
        val vm = PlayerViewModel(ctx)
        val session = PlaybackEngine.acquireForUi(ctx)
        assertNotNull("nothing is folding the sleep fade into the volume mix", session.sleepTimer.onFadeVolume)

        screenGoesAway(vm)
        assertNull("a dead ViewModel left holding the hook would swallow the fade", session.sleepTimer.onFadeVolume)
    }

    @Test
    fun `a timer armed by one screen is still running for the next one`() {
        // The reachable case: set a timer, back out of the app, come back.
        val first = PlayerViewModel(ctx)
        // The service's hold is what keeps the player - and with it the timer
        // - alive once the last screen goes; that is exactly the situation
        // being modelled, so it is taken here as PlaybackService takes it.
        val session = PlaybackEngine.acquireForService(ctx)
        first.startSleepTimer(30)
        screenGoesAway(first)

        assertTrue("the countdown died with the screen", session.sleepTimer.isRunning)
        val second = PlayerViewModel(ctx)
        assertNotNull("the new screen cannot see the running timer", second.sleepTimerRemainingMs.value)
        assertNotNull("the new screen did not take over the fade mix", session.sleepTimer.onFadeVolume)
        second.cancelSleepTimer()
    }

    @Test
    fun `the ViewModel keeps no countdown of its own`() {
        // The deleted implementation, held deleted. Its two signatures were a
        // job field and the isPlaying spin-wait; either coming back means
        // there are two timers again, and the one that dies with the screen
        // is the one the user set.
        assertFalse(
            "PlayerViewModel is hosting a sleep-timer job again - the countdown must ride the session",
            viewModelSource.contains("sleepTimerJob"),
        )
        assertFalse(
            "the `while (player.isPlaying)` finish-track wait is back; it never ends on a queue",
            Regex("""while\s*\(\s*player\.isPlaying\s*\)""").containsMatchIn(viewModelSource),
        )
        assertTrue(
            "startSleepTimer no longer delegates to the engine timer",
            Regex("""sleepTimer\.start\(""").containsMatchIn(viewModelSource),
        )
    }
}
