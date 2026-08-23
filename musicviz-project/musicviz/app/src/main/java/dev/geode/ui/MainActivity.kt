package dev.geode.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.geode.R

class MainActivity : ComponentActivity() {
    private val factory by lazy { GeodeViewModelFactory(application) }

    private val settingsViewModel: SettingsViewModel by viewModels { factory }

    private val visualsViewModel: VisualsViewModel by viewModels { factory }

    private fun importSharedPreset(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        val message =
            when (val result = visualsViewModel.importSharedPreset(data)) {
                PresetLinkImport.NotALink -> return
                is PresetLinkImport.Imported -> getString(R.string.preset_link_imported, result.name)
                PresetLinkImport.Unreadable -> getString(R.string.preset_link_unreadable)
            }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        intent.data = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importSharedPreset(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !settingsViewModel.userDataLoaded.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot()
        }
        if (savedInstanceState == null) importSharedPreset(intent)
    }
}
