package dev.geode.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

class GeodeViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val session = PlayerSession.acquire(application)
        val created: ViewModel =
            when (modelClass) {
                StudioViewModel::class.java -> StudioViewModel(session)
                else -> {
                    PlayerSession.release()
                    error("Unknown ViewModel ${modelClass.name}")
                }
            }
        @Suppress("UNCHECKED_CAST")
        return created as T
    }
}

@Composable
internal inline fun <reified T : ViewModel> geodeViewModel(): T {
    val application = LocalContext.current.applicationContext as Application
    return viewModel(factory = GeodeViewModelFactory(application))
}
