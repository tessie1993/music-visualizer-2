package dev.musicviz.ui

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.musicviz.audio.CaptureFailure
import dev.musicviz.audio.PlaybackCaptureService

/**
 * "Visualize other apps": the settings half of reading Spotify, YouTube or
 * anything else playing on the device.
 *
 * The screen is mostly honesty. Playback capture is a permission the user is
 * right to be careful with, and it is a feature that will genuinely not work
 * for some apps no matter what anyone does - so this says both things plainly,
 * up front, and offers the microphone as the fallback that always works
 * instead of leaving a switch that appears to do nothing.
 */
@Composable
fun ExternalAudioSettings(viewModel: PlayerViewModel) {
    val context = LocalContext.current
    val external by viewModel.externalAudio.collectAsState()

    val projectionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                PlaybackCaptureService.start(context, result.resultCode, data)
            } else {
                viewModel.noteExternalAudioConsentDenied()
            }
        }
    val askConsent = {
        viewModel.noteExternalAudioConsentPending()
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }
    // RECORD_AUDIO is what the capture recorder needs; POST_NOTIFICATIONS is
    // what lets its ongoing notification actually appear. Asked together so
    // the user answers once, then meets the system capture dialog.
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[android.Manifest.permission.RECORD_AUDIO] != false) {
                askConsent()
            } else {
                viewModel.noteExternalAudioConsentDenied()
            }
        }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!external.supported) {
            Text("Visualize other apps", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Needs Android 10 or newer — the API that lets one app read another app's audio " +
                    "arrived with it. Live input (the microphone) works on every version and can " +
                    "hear the same speaker.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Visualize other apps", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = external.active || external.awaitingConsent,
                onCheckedChange = { want ->
                    if (!want) {
                        viewModel.stopExternalAudio()
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissions.launch(
                            arrayOf(
                                android.Manifest.permission.RECORD_AUDIO,
                                android.Manifest.permission.POST_NOTIFICATIONS,
                            ),
                        )
                    } else if (viewModel.hasMicPermission()) {
                        askConsent()
                    } else {
                        permissions.launch(arrayOf(android.Manifest.permission.RECORD_AUDIO))
                    }
                },
            )
        }
        Text(
            "Drives the visuals from whatever is already playing on this device — a streaming app, " +
                "a video, a game — instead of from a file in your library. Android asks for capture " +
                "permission first, and shows a notification the whole time it is running. Audio is " +
                "analysed live and never recorded, saved or sent anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            external.awaitingConsent ->
                Text(
                    "Waiting for the capture permission…",
                    style = MaterialTheme.typography.bodySmall,
                    color = accentTextColor(),
                )
            external.refusedByApp -> RefusedNotice(viewModel, external)
            external.active ->
                Text(
                    external.nowPlaying?.let { "Listening to ${it.appLabel}." }
                        ?: "Listening. Start something playing in any app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = accentTextColor(),
                )
            external.failure != null -> Text(failureText(external.failure!!), style = MaterialTheme.typography.bodySmall)
        }

        external.nowPlaying?.takeIf { it.title.isNotBlank() }?.let { np ->
            Text(
                "${np.appLabel}: ${np.title}${if (np.artist.isNotBlank()) " — ${np.artist}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!external.hasSessionAccess) {
            Column {
                Text(
                    "MusicViz can also show WHAT the other app is playing — the track and artist, on the " +
                        "visualizer. That reads the media session, which Android gates behind notification " +
                        "access. Optional: the visuals work on the sound alone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CrystalButton(
                    filled = false,
                    modifier = Modifier.padding(top = 6.dp),
                    onClick = {
                        runCatching { context.startActivity(viewModel.notificationAccessIntent()) }
                    },
                ) { Text("Allow reading now playing") }
            }
        }
    }
}

/**
 * The Spotify case, spelled out.
 *
 * This is the one failure the feature cannot engineer its way out of, so it
 * gets a real explanation and a working alternative rather than an error
 * string. The microphone hears the same speaker; it is worse, and it is not
 * nothing.
 */
@Composable
private fun RefusedNotice(
    viewModel: PlayerViewModel,
    external: ExternalAudioState,
) {
    val app = external.refusingApp ?: "That app"
    Column(
        Modifier
            .fillMaxWidth()
            .crystalPanel(
                0.32f,
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.error,
                corner = 16.dp,
                glowStrength = 0.5f,
            ).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CrystalOverline("Silence, on purpose", color = MaterialTheme.colorScheme.error)
        Text(
            "$app does not allow other apps to capture its audio. Android honours that, and there " +
                "is no setting on either side that changes it — the capture is running and being " +
                "handed digital silence. Most other apps (YouTube, podcast players, games) do allow " +
                "it and work here.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "The microphone still hears your speaker. It picks up the room with it, so it is a " +
                "rougher signal — but it reacts to $app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CrystalButton(onClick = {
            viewModel.stopExternalAudio()
            viewModel.setMicEnabled(true)
        }) { Text("Use the microphone instead") }
    }
}

private fun failureText(failure: CaptureFailure): String =
    when (failure) {
        CaptureFailure.UNSUPPORTED ->
            "This device is older than Android 10, which introduced audio capture between apps."
        CaptureFailure.PERMISSION ->
            "Microphone access is off for MusicViz. Android requires it for audio capture of any " +
                "kind — turn it on in Android Settings › Apps › MusicViz › Permissions."
        CaptureFailure.CONSENT ->
            "Capture permission was not given. Nothing is being read."
        CaptureFailure.UNAVAILABLE ->
            "The device would not open an audio capture at any format — another app may be holding it."
    }
