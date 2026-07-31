package dev.musicviz.ui

import androidx.media3.common.Player
import dev.musicviz.analysis.PlaybackMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The sleep timer: counts down, fades the volume over the final stretch (see
 * [PlaybackMath.sleepFadeVolume]), pauses, then restores full volume so the
 * next play does not start silent.
 *
 * Owns only the countdown. Persisting the chosen duration is the caller's
 * job — a running timer is deliberately never persisted, only the last
 * duration the user picked.
 */
class SleepTimerController(
    private val scope: CoroutineScope,
    private val player: Player,
) {
    private var job: Job? = null

    private val _remainingMs = MutableStateFlow<Long?>(null)

    /** Remaining sleep-timer time, or null when no timer is running. */
    val remainingMs: StateFlow<Long?> = _remainingMs

    /** Starts (or restarts) the timer. [minutes] must be positive. */
    fun start(minutes: Int) {
        job?.cancel()
        job =
            scope.launch {
                val endMs = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L
                while (true) {
                    val remaining = endMs - android.os.SystemClock.elapsedRealtime()
                    if (remaining <= 0L) break
                    _remainingMs.value = remaining
                    player.volume = PlaybackMath.sleepFadeVolume(remaining)
                    delay(if (remaining <= PlaybackMath.SLEEP_FADE_MS) 100 else 500)
                }
                player.pause()
                player.volume = 1f
                _remainingMs.value = null
                job = null
            }
    }

    /** Cancels a running timer and restores full volume. */
    fun cancel() {
        job?.cancel()
        job = null
        _remainingMs.value = null
        player.volume = 1f
    }
}
