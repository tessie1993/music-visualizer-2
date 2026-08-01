package dev.musicviz.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    /**
     * Imports a preset carried by a `musicviz://preset/...` link.
     *
     * Handled here rather than in a receiver because the payload IS the
     * preset - there is nothing to fetch - so the whole job is decode, save,
     * tell the user. A link that fails to decode (truncated by the chat app it
     * travelled through) is reported rather than silently ignored.
     */
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
        // Consumed: a config change must not re-import the same link.
        intent.data = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importSharedPreset(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate: installs the Android 12+ system
        // splash handler and swaps to postSplashScreenTheme.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppRoot(
                viewModel = viewModel,
                onPersistUri = { uri ->
                    // Not every provider grants persistable permissions;
                    // playback still works for the session either way.
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                },
            )
        }
        importSharedPreset(intent)
    }
}
