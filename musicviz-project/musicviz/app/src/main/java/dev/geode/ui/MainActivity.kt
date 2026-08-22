package dev.geode.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    private fun importSharedPreset(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (!PresetLink.isPresetLink(data)) return
        val imported = viewModel.importPresetLink(data)
        android.widget.Toast
            .makeText(
                this,
                imported?.let { "Preset \"$it\" imported — it is in Visuals › Presets." }
                    ?: "That preset link could not be read. It may have been cut short in transit.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        intent.data = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importSharedPreset(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setKeepOnScreenCondition { !viewModel.userDataLoaded.value }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot(viewModel = viewModel)
        }
        if (savedInstanceState == null) importSharedPreset(intent)
    }
}
