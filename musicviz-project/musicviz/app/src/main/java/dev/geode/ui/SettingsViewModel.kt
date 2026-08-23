package dev.geode.ui

import androidx.lifecycle.ViewModel
import dev.geode.audio.AudioFxState
import dev.geode.data.PlayerPrefs
import dev.geode.ui.theme.ThemePack
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel internal constructor(
    private val session: PlayerSession,
) : ViewModel() {
    private val userData: UserDataRepository = session.userDataRepository

    val theme: StateFlow<ThemePack> get() = userData.theme

    val guiPrefs: StateFlow<GuiPrefs> get() = userData.guiPrefs

    val userDataLoaded: StateFlow<Boolean> get() = userData.loaded

    val playerPrefs: StateFlow<PlayerPrefs> get() = session.playerPrefs

    val audioFx: StateFlow<AudioFxState> get() = session.audioFx

    fun setTheme(theme: ThemePack) = session.setTheme(theme)

    fun setGuiPrefs(prefs: GuiPrefs) = session.setGuiPrefs(prefs)

    fun setPlayerPrefs(prefs: PlayerPrefs) = session.setPlayerPrefs(prefs)

    fun setAudioFxEnabled(enabled: Boolean) = session.setAudioFxEnabled(enabled)

    fun setAudioFxBand(
        band: Int,
        levelMb: Int,
    ) = session.setAudioFxBand(band, levelMb)

    fun useAudioFxPreset(index: Int) = session.useAudioFxPreset(index)

    fun setAudioFxBassBoost(strength: Int) = session.setAudioFxBassBoost(strength)

    fun setAudioFxLoudness(gainMb: Int) = session.setAudioFxLoudness(gainMb)

    override fun onCleared() {
        PlayerSession.release()
    }
}
