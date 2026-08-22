package dev.geode.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import dev.geode.export.ClipEdit
import dev.geode.export.StudioClip
import kotlinx.coroutines.flow.StateFlow

class StudioViewModel internal constructor(
    private val session: PlayerSession,
) : ViewModel() {
    val studio: StateFlow<StudioUiState> get() = session.studio

    fun refreshStudioClips() = session.refreshStudioClips()

    fun describeStudioClip(
        uri: Uri,
        onReady: (StudioClip) -> Unit,
    ) = session.describeStudioClip(uri, onReady)

    fun renameStudioClip(
        uri: String,
        name: String,
        onResult: (Boolean) -> Unit,
    ) = session.renameStudioClip(uri, name, onResult)

    fun deleteStudioClip(
        uri: String,
        onResult: (Boolean) -> Unit,
    ) = session.deleteStudioClip(uri, onResult)

    fun startStudioExport(
        clip: StudioClip,
        edit: ClipEdit,
    ) = session.startStudioExport(clip, edit)

    fun cancelStudioExport() = session.cancelStudioExport()

    fun clearStudioResult() = session.clearStudioResult()

    override fun onCleared() {
        PlayerSession.release()
    }
}
