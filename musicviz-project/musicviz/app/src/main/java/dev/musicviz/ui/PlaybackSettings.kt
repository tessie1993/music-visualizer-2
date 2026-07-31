package dev.musicviz.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.musicviz.analysis.PlaybackMath

/** Sleep-timer choices in minutes; 0 renders as "Off". */
private val SLEEP_TIMER_CHOICES = listOf(0, 15, 30, 45, 60)

/**
 * Playback section for the Settings screen: speed/pitch sliders, skip
 * silence, pause-on-unplug, keep-screen-on, auto-resume and the sleep timer.
 * Mounted by SettingsScreen as a single item after the Player controls.
 */
@Composable
fun PlaybackSettingsSection(viewModel: PlayerViewModel) {
    val prefs by viewModel.playerPrefs.collectAsState()
    val sleepRemainingMs by viewModel.sleepTimerRemainingMs.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text("Playback", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Column {
            Text("Speed  ${"%.2f".format(prefs.speed)}x", style = MaterialTheme.typography.labelMedium)
            CrystalSlider(
                value = prefs.speed,
                onValueChange = { viewModel.setPlayerPrefs(prefs.copy(speed = PlaybackMath.snap(it, 0.05f))) },
                valueRange = 0.5f..2f,
            )
        }
        Column {
            Text(
                "Pitch  ${"%.1f".format(prefs.pitchSemitones)} st (0 = normal)",
                style = MaterialTheme.typography.labelMedium,
            )
            CrystalSlider(
                value = prefs.pitchSemitones,
                onValueChange = { viewModel.setPlayerPrefs(prefs.copy(pitchSemitones = PlaybackMath.snap(it, 0.5f))) },
                valueRange = -6f..6f,
            )
        }
        PlaybackSwitchRow("Skip silence", prefs.skipSilence) {
            viewModel.setPlayerPrefs(prefs.copy(skipSilence = it))
        }
        PlaybackSwitchRow("Pause when unplugged", prefs.pauseOnNoisy) {
            viewModel.setPlayerPrefs(prefs.copy(pauseOnNoisy = it))
        }
        PlaybackSwitchRow("Keep screen on", prefs.keepScreenOn) {
            viewModel.setPlayerPrefs(prefs.copy(keepScreenOn = it))
        }
        PlaybackSwitchRow("Auto-resume last track", prefs.autoResume) {
            viewModel.setPlayerPrefs(prefs.copy(autoResume = it))
        }
        Column {
            Text("Sleep timer", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val running = sleepRemainingMs != null
                SLEEP_TIMER_CHOICES.forEach { minutes ->
                    FilterChip(
                        selected =
                            if (running) {
                                minutes != 0 && minutes == prefs.sleepTimerMinutes
                            } else {
                                minutes == 0
                            },
                        onClick = {
                            if (minutes == 0) viewModel.cancelSleepTimer() else viewModel.startSleepTimer(minutes)
                        },
                        label = {
                            Text(
                                if (minutes == 0) "Off" else "$minutes min",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
            sleepRemainingMs?.let { remaining ->
                Text(
                    "Pausing in ${PlaybackMath.formatCountdown(remaining)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PlaybackSwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
