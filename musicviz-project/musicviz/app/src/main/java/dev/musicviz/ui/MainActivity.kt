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
        // Starts the media service while this Activity is visible, so it may
        // later promote itself to the foreground when playback begins. Started
        // here rather than on first play because a background start would be
        // refused on Android 12+.
        startService(Intent(this, PlaybackService::class.java))
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
}
