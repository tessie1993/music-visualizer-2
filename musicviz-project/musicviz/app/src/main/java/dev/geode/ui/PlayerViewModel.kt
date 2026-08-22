package dev.geode.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.geode.analysis.AudioFeatures
import dev.geode.analysis.IntelligenceMode
import dev.geode.analysis.LiveInputProfile
import dev.geode.audio.AudioFxState
import dev.geode.audio.MicCapture
import dev.geode.data.MilkPackImporter
import dev.geode.data.MilkTexture
import dev.geode.data.PlayerPrefs
import dev.geode.data.Preset
import dev.geode.data.PresetFolders
import dev.geode.export.ExportAspect
import dev.geode.export.ExportRange
import dev.geode.export.VideoExporter
import dev.geode.render.AdsrConfig
import dev.geode.render.LfoConfig
import dev.geode.render.TransitionStyle
import dev.geode.render.scene.CustomizeTab
import dev.geode.render.scene.PcmChunk
import dev.geode.render.scene.SceneParams
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

@OptIn(UnstableApi::class)
class PlayerViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val session = PlayerSession.acquire(application)

    val micState: StateFlow<MicState> get() = session.micState

    val externalAudio: StateFlow<ExternalAudioState> get() = session.externalAudio

    fun setMicEnabled(enabled: Boolean): MicCapture.Failure? = session.setMicEnabled(enabled)

    fun hasMicPermission(): Boolean = session.hasMicPermission()

    fun noteExternalAudioConsentPending() = session.noteExternalAudioConsentPending()

    fun noteExternalAudioConsentDenied() = session.noteExternalAudioConsentDenied()

    fun stopExternalAudio() = session.stopExternalAudio()

    fun notificationAccessIntent(): Intent = session.notificationAccessIntent()

    val player: ExoPlayer get() = session.player

    val uiState: StateFlow<PlayerUiState> get() = session.uiState

    val vizState: StateFlow<VizUiState> get() = session.vizState

    val exportState: StateFlow<ExportUiState> get() = session.exportState

    val library: StateFlow<LibraryState> get() = session.library

    val trackOverrides: StateFlow<Map<String, LibraryTrack>> get() = session.trackOverrides

    val userDataLoaded: StateFlow<Boolean> get() = session.userDataLoaded

    val theme: StateFlow<dev.geode.ui.theme.ThemePack> get() = session.theme

    val guiPrefs: StateFlow<GuiPrefs> get() = session.guiPrefs

    val playerPrefs: StateFlow<PlayerPrefs> get() = session.playerPrefs

    val audioFx: StateFlow<AudioFxState> get() = session.audioFx

    fun setGuiPrefs(prefs: GuiPrefs) = session.setGuiPrefs(prefs)

    fun setTheme(theme: dev.geode.ui.theme.ThemePack) = session.setTheme(theme)

    fun setPlayerPrefs(prefs: PlayerPrefs) = session.setPlayerPrefs(prefs)

    fun setAudioFxEnabled(enabled: Boolean) = session.setAudioFxEnabled(enabled)

    fun setAudioFxBand(
        band: Int,
        levelMb: Int,
    ) = session.setAudioFxBand(band, levelMb)

    fun useAudioFxPreset(index: Int) = session.useAudioFxPreset(index)

    fun setAudioFxBassBoost(strength: Int) = session.setAudioFxBassBoost(strength)

    fun setAudioFxLoudness(gainMb: Int) = session.setAudioFxLoudness(gainMb)

    fun toggleShuffle() = session.toggleShuffle()

    fun cycleRepeatMode() = session.cycleRepeatMode()

    fun applyLiveInputProfile(profile: LiveInputProfile) = session.applyLiveInputProfile(profile)

    val textures: StateFlow<List<MilkTexture>> get() = session.textures

    val lfos: StateFlow<List<LfoConfig>> get() = session.lfos

    val adsrs: StateFlow<List<AdsrConfig>> get() = session.adsrs

    val lockedParams: StateFlow<Set<String>> get() = session.lockedParams

    fun toggleParamLock(label: String) = session.toggleParamLock(label)

    fun randomizeParams(tab: CustomizeTab? = null) = session.randomizeParams(tab)

    fun setAdsr(
        index: Int,
        config: AdsrConfig,
    ) = session.setAdsr(index, config)

    fun setLfo(
        index: Int,
        config: LfoConfig,
    ) = session.setLfo(index, config)

    fun importTextures(
        uris: List<Uri>,
        onImported: () -> Unit,
    ) = session.importTextures(uris, onImported)

    fun removeTexture(name: String) = session.removeTexture(name)

    fun useTexture(
        name: String,
        onReady: (String) -> Unit,
    ) = session.useTexture(name, onReady)

    val features: StateFlow<AudioFeatures> get() = session.features

    fun enrichFeatures(f: AudioFeatures): AudioFeatures = session.enrichFeatures(f)

    fun latestPcm(): PcmChunk? = session.latestPcm()

    fun milkPresetFilesAsync(onDone: (List<MilkFile>) -> Unit) = session.milkPresetFilesAsync(onDone)

    fun importMilkPresetAsync(
        uri: Uri,
        onDone: (String?) -> Unit,
    ) = session.importMilkPresetAsync(uri, onDone)

    internal fun importMilkPresetBlocking(uri: Uri): String? = session.importMilkPresetBlocking(uri)

    fun importMilkFolderAsync(
        treeUri: Uri,
        onDone: (MilkPackImporter.Report) -> Unit,
    ) = session.importMilkFolderAsync(treeUri, onDone)

    val historyTick: StateFlow<Int> get() = session.historyTick

    val favourites: StateFlow<Set<String>> get() = session.favourites

    val takeState: StateFlow<TakeUiState> get() = session.takeState

    val presetLocked: StateFlow<Boolean> get() = session.presetLocked

    val artPaletteNote: StateFlow<String?> get() = session.artPaletteNote

    fun applyKeyColor(key: String) = session.applyKeyColor(key)

    fun setKeyColor(enabled: Boolean) = session.setKeyColor(enabled)

    fun applyArtworkPalette() = session.applyArtworkPalette()

    fun currentTrackKey(): String? = session.currentTrackKey()

    val waveform: StateFlow<FloatArray?> get() = session.waveform

    fun setIntelligenceMode(mode: IntelligenceMode) = session.setIntelligenceMode(mode)

    fun analyzeCurrentTrack() = session.analyzeCurrentTrack()

    val lyrics: StateFlow<Lyrics?> get() = session.lyrics

    val autoMode: StateFlow<Int> get() = session.autoMode

    val sleepTimerRemainingMs: StateFlow<Long?> get() = session.sleepTimerRemainingMs

    fun startSleepTimer(minutes: Int) = session.startSleepTimer(minutes)

    fun cancelSleepTimer() = session.cancelSleepTimer()

    val vizApply: SharedFlow<VizApply> get() = session.vizApply

    val morphFade: SharedFlow<Float> get() = session.morphFade

    fun addToVizPlaylist(entry: VizPlaylistEntry) = session.addToVizPlaylist(entry)

    fun removeVizPlaylistAt(index: Int) = session.removeVizPlaylistAt(index)

    fun setVizPlaylistEnabled(enabled: Boolean) = session.setVizPlaylistEnabled(enabled)

    fun setVizPlaylistIntelligent(enabled: Boolean) = session.setVizPlaylistIntelligent(enabled)

    fun setVizPlaylistInterval(seconds: Int) = session.setVizPlaylistInterval(seconds)

    fun setRandomEnabled(enabled: Boolean) = session.setRandomEnabled(enabled)

    fun setRandomInterval(seconds: Int) = session.setRandomInterval(seconds)

    fun setRandomOnBeat(enabled: Boolean) = session.setRandomOnBeat(enabled)

    fun setRandomIncludeStyles(enabled: Boolean) = session.setRandomIncludeStyles(enabled)

    fun setRandomIncludePresets(enabled: Boolean) = session.setRandomIncludePresets(enabled)

    fun setRandomIncludeMilk(enabled: Boolean) = session.setRandomIncludeMilk(enabled)

    fun setRandomizeColors(enabled: Boolean) = session.setRandomizeColors(enabled)

    fun setSectionStaging(enabled: Boolean) = session.setSectionStaging(enabled)

    fun randomStepNow() = session.randomStepNow()

    fun applyVizEntry(entry: VizPlaylistEntry) = session.applyVizEntry(entry)

    val deviceTracks: StateFlow<List<DeviceTrack>> get() = session.deviceTracks

    val mediaRoots: StateFlow<Set<String>> get() = session.mediaRoots

    val libraryScanning: StateFlow<Boolean> get() = session.libraryScanning

    fun refreshDeviceTracks() = session.refreshDeviceTracks()

    fun importTracks(uris: List<Uri>) = session.importTracks(uris)

    fun trackOverride(uri: String): LibraryTrack? = session.trackOverride(uri)

    suspend fun trackInfoFor(uriStr: String): LibraryTrack = session.trackInfoFor(uriStr)

    fun saveTrackInfo(
        uri: String,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int,
        trackNo: Int,
        comment: String,
    ) = session.saveTrackInfo(uri, title, artist, album, genre, year, trackNo, comment)

    fun importFolder(treeUri: Uri) = session.importFolder(treeUri)

    fun removeMediaRoot(uriStr: String) = session.removeMediaRoot(uriStr)

    fun rescanMediaRoots() = session.rescanMediaRoots()

    fun createMusicPlaylist(name: String) = session.createMusicPlaylist(name)

    fun renameMusicPlaylist(
        oldName: String,
        newName: String,
    ): Boolean = session.renameMusicPlaylist(oldName, newName)

    fun moveMusicPlaylistTrack(
        name: String,
        from: Int,
        to: Int,
    ) = session.moveMusicPlaylistTrack(name, from, to)

    fun deleteMusicPlaylist(name: String) = session.deleteMusicPlaylist(name)

    fun addTrackToPlaylist(
        playlist: String,
        uri: String,
    ) = session.addTrackToPlaylist(playlist, uri)

    fun removeTrackFromPlaylist(
        playlist: String,
        uri: String,
    ) = session.removeTrackFromPlaylist(playlist, uri)

    fun playPlaylist(
        playlist: String,
        startIndex: Int = 0,
    ) = session.playPlaylist(playlist, startIndex)

    fun recentlyPlayed() = session.recentlyPlayed()

    fun currentTrackUri(): String? = session.currentTrackUri()

    fun toggleFavourite(uri: String? = null) = session.toggleFavourite(uri)

    val abLoop: StateFlow<AbLoop?> get() = session.abLoop

    fun cycleAbLoop() = session.cycleAbLoop()

    fun clearAbLoop() = session.clearAbLoop()

    val queue: StateFlow<QueueUiState> get() = session.queue

    fun removeQueueItem(index: Int) = session.removeQueueItem(index)

    fun moveQueueItem(
        from: Int,
        to: Int,
    ) = session.moveQueueItem(from, to)

    fun togglePlayPauseFaded() = session.togglePlayPauseFaded()

    fun resumeLastPlayed() = session.resumeLastPlayed()

    fun togglePresetLock() = session.togglePresetLock()

    fun cycleAutoMode() = session.cycleAutoMode()

    fun playNext(uri: String) = session.playNext(uri)

    fun enqueue(uri: String) = session.enqueue(uri)

    fun shuffleAllHistory() = session.shuffleAllHistory()

    val presetFolders: StateFlow<PresetFolders> get() = session.presetFolders

    fun addPresetFolder(path: String) = session.addPresetFolder(path)

    fun renamePresetFolder(
        from: String,
        to: String,
    ) = session.renamePresetFolder(from, to)

    fun movePresetToFolder(
        name: String,
        folder: String,
    ) = session.movePresetToFolder(name, folder)

    fun userMilkPresets(): List<File> = session.userMilkPresets()

    fun playTrack(uri: String) = session.playTrack(uri)

    fun playFrom(
        tracks: List<QueueTrack>,
        startUri: String,
    ) = session.playFrom(tracks, startUri)

    fun playAll(
        tracks: List<QueueTrack>,
        shuffled: Boolean = false,
    ) = session.playAll(tracks, shuffled)

    fun open(uris: List<Uri>) = session.open(uris)

    fun queueTitles(): List<String> = session.queueTitles()

    fun playQueueIndex(index: Int) = session.playQueueIndex(index)

    fun next() = session.next()

    fun previous() = session.previous()

    fun togglePlayPause() = session.togglePlayPause()

    fun seekToMs(positionMs: Long) = session.seekToMs(positionMs)

    fun seekTo(fraction: Float) = session.seekTo(fraction)

    val playbackNotice: StateFlow<String?> get() = session.playbackNotice

    fun clearPlaybackNotice() = session.clearPlaybackNotice()

    fun startRecording() = session.startRecording()

    fun stopRecording(name: String? = null) = session.stopRecording(name)

    fun playTake(name: String) = session.playTake(name)

    fun stopReplay() = session.stopReplay()

    fun deleteTake(name: String) = session.deleteTake(name)

    fun renameTake(
        from: String,
        to: String,
    ) = session.renameTake(from, to)

    fun setExportTake(name: String?) = session.setExportTake(name)

    fun selectScene(sceneId: String) = session.selectScene(sceneId)

    fun setReactivity(
        attack: Float,
        decay: Float,
    ) = session.setReactivity(attack, decay)

    fun setSceneParams(params: SceneParams) = session.setSceneParams(params)

    fun resetSceneParams() = session.resetSceneParams()

    fun nudgeTransform(
        zoomFactor: Float,
        rotationDegrees: Float,
    ) = session.nudgeTransform(zoomFactor, rotationDegrees)

    fun reportShaderError(error: String?) = session.reportShaderError(error)

    fun applyCustomShader(source: String) = session.applyCustomShader(source)

    fun setTransitionStyle(style: TransitionStyle) = session.setTransitionStyle(style)

    fun setTransitionId(id: String) = session.setTransitionId(id)

    fun setTransitionDuration(seconds: Float) = session.setTransitionDuration(seconds)

    fun applyPreset(preset: Preset) = session.applyPreset(preset)

    internal fun awaitStoreWrites(timeoutMs: Long) = session.awaitStoreWrites(timeoutMs)

    fun savePreset(
        name: String,
        customShader: String?,
        folder: String = "",
    ) = session.savePreset(name, customShader, folder)

    internal fun milkPresetPathFor(preset: Preset): String? = session.milkPresetPathFor(preset)

    val activeMilkPath: StateFlow<String?> get() = session.activeMilkPath

    fun noteMilkPreset(path: String) = session.noteMilkPreset(path)

    fun presetShareLink(name: String): String? = session.presetShareLink(name)

    fun importPresetLink(text: String): String? = session.importPresetLink(text)

    fun importPresetFile(
        uri: Uri,
        onResult: (String?) -> Unit,
    ) = session.importPresetFile(uri, onResult)

    fun presetFile(name: String): File? = session.presetFile(name)

    fun deletePreset(name: String) = session.deletePreset(name)

    fun startExport(
        aspect: ExportAspect,
        fps: Int,
        sceneFactory: VideoExporter.SceneFactory,
        destination: Uri? = null,
        loopSafe: Boolean = false,
        range: ExportRange? = null,
        sceneFactoryFor: ((String) -> VideoExporter.SceneFactory)? = null,
    ) = session.startExport(aspect, fps, sceneFactory, destination, loopSafe, range, sceneFactoryFor)

    fun cancelExport() = session.cancelExport()

    fun resetExportState() = session.resetExportState()

    override fun onCleared() {
        PlayerSession.release()
    }
}
