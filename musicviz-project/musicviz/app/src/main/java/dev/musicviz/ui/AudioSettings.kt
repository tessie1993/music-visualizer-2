package dev.musicviz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.musicviz.analysis.FeatureExtractor
import kotlin.math.roundToInt

/**
 * AUDIO: everything about what the app hears and how it plays it - playback
 * shaping, the equalizer chain, the analyser's beat settings, live input from
 * the microphone, and capturing other apps' audio.
 */
@Composable
internal fun AudioSettingsTab(viewModel: PlayerViewModel) {
    SettingsTabColumn {
        item { SettingsGroup("Playback") { PlaybackSettingsSection(viewModel) } }
        // Carries its own SettingsGroup card: the master switch lives on the
        // group header, so the whole card is the control.
        item { EqualizerSettings(viewModel) }
        item { SettingsGroup("Analysis") { AnalysisGroup(viewModel) } }
        item { SettingsGroup("Live input") { LiveInputGroup(viewModel) } }
        item { SettingsGroup("Other apps") { ExternalAudioSettings(viewModel) } }
    }
}

/**
 * The beat detector and its downstream colour/morph decisions. Slider ranges
 * come from the extractor so they can never saturate against a tighter clamp
 * in AnalysisEngine.
 */
@Composable
private fun AnalysisGroup(viewModel: PlayerViewModel) {
    val gui by viewModel.guiPrefs.collectAsState()
    Column {
        Text(
            "Beat sensitivity  ${"%.1f".format(gui.beatThresholdSigma)}σ " +
                "— drag right for LESS sensitive (fewer beat flashes)",
            style = MaterialTheme.typography.labelMedium,
        )
        CrystalSlider(
            value = gui.beatThresholdSigma,
            onValueChange = { viewModel.setGuiPrefs(gui.copy(beatThresholdSigma = it)) },
            valueRange = FeatureExtractor.SIGMA_MIN..FeatureExtractor.SIGMA_MAX,
        )
        Text(
            "Minimum gap between beats  ${gui.beatMinIntervalMs.roundToInt()} ms " +
                "— never flash faster than ${(60_000f / gui.beatMinIntervalMs).roundToInt()} BPM",
            style = MaterialTheme.typography.labelMedium,
        )
        CrystalSlider(
            value = gui.beatMinIntervalMs,
            onValueChange = { viewModel.setGuiPrefs(gui.copy(beatMinIntervalMs = it)) },
            valueRange = FeatureExtractor.INTERVAL_MS_MIN..FeatureExtractor.INTERVAL_MS_MAX,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CrystalButton(
                filled = false,
                onClick = {
                    viewModel.setGuiPrefs(
                        gui.copy(
                            beatThresholdSigma = FeatureExtractor.SLOW_SIGMA,
                            beatMinIntervalMs = FeatureExtractor.SLOW_INTERVAL_MS,
                        ),
                    )
                },
            ) { Text("Slow track") }
            TextButton(
                onClick = {
                    viewModel.setGuiPrefs(
                        gui.copy(
                            beatThresholdSigma = FeatureExtractor.SIGMA_DEFAULT,
                            beatMinIntervalMs = FeatureExtractor.INTERVAL_MS_DEFAULT,
                        ),
                    )
                },
            ) { Text("Default") }
        }
    }
    Column {
        Text("Preset morph: ${gui.morphBeats} beats (0 = snap)")
        CrystalSlider(
            value = gui.morphBeats.toFloat(),
            onValueChange = { viewModel.setGuiPrefs(gui.copy(morphBeats = it.toInt())) },
            valueRange = 0f..16f,
            steps = 15,
        )
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Colour from the musical key", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = gui.keyColor, onCheckedChange = viewModel::setKeyColor)
        }
        Text(
            "Sets Hue shift from the key the analyser found, around the circle of fifths — so a " +
                "track keeps the same colour every time you play it, and two songs that sound " +
                "related look related. It moves the ordinary Hue shift slider, so you can always " +
                "disagree with it; switching this off gives your own value back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * "Live input": drive the visuals from the microphone with nothing playing.
 * The permission is requested at the moment the switch is used, never at
 * launch, and a denial is reported in place rather than leaving a switch
 * that silently springs back.
 */
@Composable
private fun LiveInputGroup(viewModel: PlayerViewModel) {
    val mic by viewModel.micState.collectAsState()
    var denied by remember { mutableStateOf(false) }
    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            denied = !granted
            if (granted) viewModel.setMicEnabled(true)
        }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("React to the microphone", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = mic.active,
                onCheckedChange = { want ->
                    denied = false
                    if (!want) {
                        viewModel.setMicEnabled(false)
                    } else if (viewModel.hasMicPermission()) {
                        viewModel.setMicEnabled(true)
                    } else {
                        micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
            )
        }
        Text(
            "Plays nothing and drives the visuals from what the phone hears — a room, an " +
                "instrument, a speaker across the street. Playback pauses while it is on, because " +
                "the analyzer has one input and a track plus the room would just blur together. " +
                "Audio is analysed live and never recorded, saved or sent anywhere.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            denied || mic.failure == dev.musicviz.audio.MicCapture.Failure.PERMISSION ->
                Text(
                    "Microphone access is off for MusicViz. Turn it on in Android Settings › Apps › " +
                        "MusicViz › Permissions to use live input.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            mic.failure == dev.musicviz.audio.MicCapture.Failure.UNAVAILABLE ->
                Text(
                    "The microphone could not be opened — another app may be using it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
        }
    }
    Column {
        Text("Tune for what the phone is hearing", style = MaterialTheme.typography.labelMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(dev.musicviz.analysis.LiveInputProfile.entries.toList()) { profile ->
                CrystalButton(
                    compact = true,
                    filled = false,
                    onClick = { viewModel.applyLiveInputProfile(profile) },
                ) { Text(profile.label) }
            }
        }
        Text(
            dev.musicviz.analysis.LiveInputProfile.entries.joinToString("  ·  ") { "${it.label}: ${it.summary}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Each sets the beat threshold, the reactivity envelope and the band balance together — " +
                "they are one decision, and they live on three different screens. Every value stays " +
                "an ordinary slider afterwards.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
