package dev.musicviz.ui

import android.content.Context

/** Core playback preferences applied to the ExoPlayer and persisted. */
data class PlayerPrefs(
    val shuffle: Boolean = false,
    /** One of Player.REPEAT_MODE_OFF / ONE / ALL (0 / 1 / 2). */
    val repeatMode: Int = 0,
    /** Playback speed, 0.5..2 (1 = normal). */
    val speed: Float = 1f,
    /** Pitch offset in semitones, -6..+6 (0 = normal). */
    val pitchSemitones: Float = 0f,
    /** Skip silent passages during playback. */
    val skipSilence: Boolean = false,
    /** Pause when headphones unplug (audio becoming noisy). */
    val pauseOnNoisy: Boolean = true,
    /** Keep the screen awake while the visualizer is showing. */
    val keepScreenOn: Boolean = false,
    /** Prepare (not play) the last-played track on startup. */
    val autoResume: Boolean = false,
    /**
     * Last-chosen sleep-timer duration in minutes; 0 = off. Only the chosen
     * duration persists - a RUNNING timer is never restored across restarts.
     */
    val sleepTimerMinutes: Int = 0,
)

/** Persists [PlayerPrefs] in shared preferences (same pattern as ThemeStore). */
class PlayerPrefsStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("musicviz-player", Context.MODE_PRIVATE)

    fun load(): PlayerPrefs =
        PlayerPrefs(
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT, 0).coerceIn(0, 2),
            speed = prefs.getFloat(KEY_SPEED, 1f).coerceIn(0.5f, 2f),
            pitchSemitones = prefs.getFloat(KEY_PITCH, 0f).coerceIn(-6f, 6f),
            skipSilence = prefs.getBoolean(KEY_SKIP_SILENCE, false),
            pauseOnNoisy = prefs.getBoolean(KEY_NOISY, true),
            keepScreenOn = prefs.getBoolean(KEY_SCREEN_ON, false),
            autoResume = prefs.getBoolean(KEY_AUTO_RESUME, false),
            sleepTimerMinutes = prefs.getInt(KEY_SLEEP_MIN, 0).coerceAtLeast(0),
        )

    fun save(p: PlayerPrefs) {
        prefs
            .edit()
            .putBoolean(KEY_SHUFFLE, p.shuffle)
            .putInt(KEY_REPEAT, p.repeatMode)
            .putFloat(KEY_SPEED, p.speed)
            .putFloat(KEY_PITCH, p.pitchSemitones)
            .putBoolean(KEY_SKIP_SILENCE, p.skipSilence)
            .putBoolean(KEY_NOISY, p.pauseOnNoisy)
            .putBoolean(KEY_SCREEN_ON, p.keepScreenOn)
            .putBoolean(KEY_AUTO_RESUME, p.autoResume)
            .putInt(KEY_SLEEP_MIN, p.sleepTimerMinutes)
            .apply()
    }

    private companion object {
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_REPEAT = "repeat_mode"
        const val KEY_SPEED = "speed"
        const val KEY_PITCH = "pitch_semitones"
        const val KEY_SKIP_SILENCE = "skip_silence"
        const val KEY_NOISY = "pause_on_noisy"
        const val KEY_SCREEN_ON = "keep_screen_on"
        const val KEY_AUTO_RESUME = "auto_resume"
        const val KEY_SLEEP_MIN = "sleep_timer_minutes"
    }
}
