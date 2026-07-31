package dev.musicviz.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.musicviz.playback.PlaybackService

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    // Android 13+ hides the media notification without this. Playback and the
    // foreground service still work when it is denied, so the result is only
    // used to stop asking.
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate: installs the Android 12+ system
        // splash handler and swaps to postSplashScreenTheme.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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
    }

    /**
     * Re-asserts the media service every time the app becomes visible.
     *
     * Starting it once from onCreate was not enough: the service can stop for
     * reasons this Activity never sees — onTaskRemoved, a system stop, or the
     * user force-stopping playback — and nothing then brought it back for the
     * rest of the Activity's life, silently losing background playback and the
     * lock-screen controls. startService on an already-running service is
     * idempotent (it just delivers another onStartCommand), and onStart is by
     * definition a foreground moment, so this is never a background start.
     */
    override fun onStart() {
        super.onStart()
        runCatching { startService(Intent(this, PlaybackService::class.java)) }
    }
}
