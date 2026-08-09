package dev.musicviz.playback

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.data.PlayerPrefs
import dev.musicviz.data.PlayerPrefsStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The playback-prefs store, previously the largest untested store in the app
 * (the audit's test-mining table): every playback setting the user can touch
 * funnels through this one save/load pair, and an asymmetric key or a missing
 * clamp silently resets or corrupts a setting on the next launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerPrefsStoreTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `every field survives a save and reload`() {
        val saved =
            PlayerPrefs(
                shuffle = true,
                repeatMode = 2,
                speed = 1.5f,
                pitchSemitones = -3.5f,
                skipSilence = true,
                pauseOnNoisy = false,
                keepScreenOn = true,
                autoResume = true,
                sleepTimerMinutes = 45,
                sleepFinishTrack = true,
                fadeMs = 1_250,
            )
        PlayerPrefsStore(ctx).save(saved)
        // A fresh store = a fresh process: nothing may ride along in memory.
        assertEquals(saved, PlayerPrefsStore(ctx).load())
    }

    @Test
    fun `defaults load from an empty store`() {
        assertEquals(PlayerPrefs(), PlayerPrefsStore(ctx).load())
    }

    @Test
    fun `garbage on disk is clamped, not obeyed`() {
        // Values no current UI can produce, but an old build, a backup
        // restore or a corrupt write can. Each would break playback in its
        // own way: repeatMode 7 crashes ExoPlayer's setter, speed 0 freezes
        // the clock, a negative fade underflows the ramp arithmetic.
        ctx
            .getSharedPreferences("musicviz-player", Context.MODE_PRIVATE)
            .edit()
            .putInt("repeat_mode", 7)
            .putFloat("speed", 0f)
            .putFloat("pitch_semitones", 40f)
            .putInt("sleep_timer_minutes", -5)
            .putInt("fade_ms", 999_999)
            .apply()
        val loaded = PlayerPrefsStore(ctx).load()
        assertEquals(2, loaded.repeatMode)
        assertEquals(0.5f, loaded.speed, 0f)
        assertEquals(6f, loaded.pitchSemitones, 0f)
        assertEquals(0, loaded.sleepTimerMinutes)
        assertEquals(PlayerPrefs.MAX_FADE_MS, loaded.fadeMs)
    }
}
