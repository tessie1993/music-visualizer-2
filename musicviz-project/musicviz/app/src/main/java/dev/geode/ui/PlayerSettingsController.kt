package dev.geode.ui

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.geode.analysis.AnalysisEngine
import dev.geode.analysis.PlaybackMath
import dev.geode.audio.AudioFxController
import dev.geode.audio.AudioFxState
import dev.geode.data.GeodePrefsFiles
import dev.geode.data.PlayerPrefs
import dev.geode.data.PlayerPrefsStore
import dev.geode.ui.theme.ThemePack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(UnstableApi::class)
internal class PlayerSettingsController(
    prefsFiles: GeodePrefsFiles,
    private val player: ExoPlayer,
    private val engine: AnalysisEngine,
    private val audioFx: AudioFxController,
    private val host: Host,
) {
    interface Host {
        fun redecideCachedBeats(prefs: GuiPrefs)

        fun refreshUi()
    }

    private val themeStore = ThemeStore(prefsFiles.general)
    private val playerPrefsStore = PlayerPrefsStore(prefsFiles.player)

    private val _theme = MutableStateFlow(themeStore.load())
    val theme: StateFlow<ThemePack> = _theme

    private val _guiPrefs = MutableStateFlow(themeStore.loadGui())
    val guiPrefs: StateFlow<GuiPrefs> = _guiPrefs

    private val _playerPrefs = MutableStateFlow(playerPrefsStore.load())
    val playerPrefs: StateFlow<PlayerPrefs> = _playerPrefs

    private val _audioFxState = MutableStateFlow(audioFx.snapshot())
    val audioFxState: StateFlow<AudioFxState> = _audioFxState

    fun setGuiPrefs(prefs: GuiPrefs) {
        val previous = _guiPrefs.value
        themeStore.saveGui(prefs)
        _guiPrefs.value = prefs
        engine.beatSensitivity = prefs.beatSensitivity
        engine.beatMinIntervalMs = prefs.effectiveBeatMinIntervalMs
        val sensitivityChanged =
            previous.beatSensitivity != prefs.beatSensitivity ||
                previous.effectiveBeatMinIntervalMs != prefs.effectiveBeatMinIntervalMs
        if (sensitivityChanged) host.redecideCachedBeats(prefs)
    }

    fun setTheme(theme: ThemePack) {
        themeStore.save(theme)
        _theme.value = theme
    }

    fun setPlayerPrefs(prefs: PlayerPrefs) {
        val p =
            prefs.copy(
                speed = prefs.speed.coerceIn(0.5f, 2f),
                pitchSemitones = prefs.pitchSemitones.coerceIn(-6f, 6f),
                sleepTimerMinutes = prefs.sleepTimerMinutes.coerceAtLeast(0),
            )
        _playerPrefs.value = p
        playerPrefsStore.save(p)
        applyPlaybackPrefs(p)
    }

    fun applyPlaybackPrefs(p: PlayerPrefs) {
        player.playbackParameters = PlaybackParameters(p.speed, PlaybackMath.semitonesToRatio(p.pitchSemitones))
        player.skipSilenceEnabled = p.skipSilence
        player.setHandleAudioBecomingNoisy(p.pauseOnNoisy)
    }

    private fun persistPlayerOptions() {
        val p = _playerPrefs.value.copy(shuffle = player.shuffleModeEnabled, repeatMode = player.repeatMode)
        _playerPrefs.value = p
        playerPrefsStore.save(p)
    }

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        persistPlayerOptions()
        host.refreshUi()
    }

    fun cycleRepeatMode() {
        player.repeatMode =
            when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        persistPlayerOptions()
        host.refreshUi()
    }

    fun refreshAudioFx() {
        _audioFxState.value = audioFx.snapshot()
    }

    fun setAudioFxEnabled(enabled: Boolean) {
        audioFx.setEnabled(enabled)
        refreshAudioFx()
    }

    fun setAudioFxBand(
        band: Int,
        levelMb: Int,
    ) {
        audioFx.setBandLevel(band, levelMb)
        refreshAudioFx()
    }

    fun useAudioFxPreset(index: Int) {
        audioFx.usePreset(index)
        refreshAudioFx()
    }

    fun setAudioFxBassBoost(strength: Int) {
        audioFx.setBassBoost(strength)
        refreshAudioFx()
    }

    fun setAudioFxLoudness(gainMb: Int) {
        audioFx.setLoudness(gainMb)
        refreshAudioFx()
    }
}
