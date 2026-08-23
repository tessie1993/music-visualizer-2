package dev.geode.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.di.PlayerSessionProvider
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel
    @Inject
    constructor(
        private val sessions: PlayerSessionProvider,
    ) : ViewModel() {
        private val session: PlayerSession = sessions.acquire()
        val library: StateFlow<LibraryState> get() = session.library

        val deviceTracks: StateFlow<List<DeviceTrack>> get() = session.deviceTracks

        val mediaRoots: StateFlow<Set<String>> get() = session.mediaRoots

        val libraryScanning: StateFlow<Boolean> get() = session.libraryScanning

        val trackOverrides: StateFlow<Map<String, LibraryTrack>> get() = session.trackOverrides

        fun refreshDeviceTracks() = session.refreshDeviceTracks()

        fun importTracks(uris: List<Uri>) = session.importTracks(uris)

        fun importFolder(treeUri: Uri) = session.importFolder(treeUri)

        fun removeMediaRoot(uriStr: String) = session.removeMediaRoot(uriStr)

        fun rescanMediaRoots() = session.rescanMediaRoots()

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

        override fun onCleared() {
            sessions.release()
        }
    }
