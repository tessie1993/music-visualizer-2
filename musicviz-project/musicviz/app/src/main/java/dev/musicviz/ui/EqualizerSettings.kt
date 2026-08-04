package dev.musicviz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import dev.musicviz.audio.AudioFxFormat

/**
 * "Equalizer" group: one crystal card ([SettingsGroup], same material as the
 * rest of the Settings tabs - the stock Material Card it used to ride on was
 * the one flat-grey surface in the shell). The master switch sits on the
 * card's own header row, then device preset chips, one horizontal slider per
 * band (vertical sliders are awkward in plain Compose), plus bass boost and
 * loudness. Everything greys out while the chain is off, and the whole card
 * degrades to a "not supported" note on devices whose audiofx constructors
 * fail (common on emulators).
 */
@Composable
fun EqualizerSettings(viewModel: PlayerViewModel) {
    val fx by viewModel.audioFx.collectAsState()
    SettingsGroup(
        title = "Equalizer",
        header = {
            Switch(
                checked = fx.enabled && fx.available,
                onCheckedChange = { viewModel.setAudioFxEnabled(it) },
                enabled = fx.available,
            )
        },
    ) {
        if (!fx.available) {
            Text(
                "Not supported on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsGroup
        }
        val controlsOn = fx.enabled
        if (fx.presets.isNotEmpty()) {
            Text("Preset", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(fx.presets) { i, name ->
                    FilterChip(
                        selected = fx.presetIndex == i,
                        onClick = { viewModel.useAudioFxPreset(i) },
                        label = { Text(name) },
                        enabled = controlsOn,
                    )
                }
            }
            if (fx.presetIndex < 0) {
                Text(
                    "Custom",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        fx.bands.forEachIndexed { i, band ->
            Text(
                "${band.label}  ${AudioFxFormat.dbLabel(band.levelMb)}",
                style = MaterialTheme.typography.labelMedium,
            )
            CrystalSlider(
                value = band.levelMb.toFloat(),
                onValueChange = { viewModel.setAudioFxBand(i, it.toInt()) },
                valueRange = band.minMb.toFloat()..band.maxMb.toFloat(),
                enabled = controlsOn,
            )
        }
        Text("Bass boost  ${fx.bassBoost / 10}%", style = MaterialTheme.typography.labelMedium)
        CrystalSlider(
            value = fx.bassBoost.toFloat(),
            onValueChange = { viewModel.setAudioFxBassBoost(it.toInt()) },
            valueRange = 0f..1000f,
            enabled = controlsOn,
        )
        Text("Loudness  ${AudioFxFormat.dbLabel(fx.loudness)}", style = MaterialTheme.typography.labelMedium)
        CrystalSlider(
            value = fx.loudness.toFloat(),
            onValueChange = { viewModel.setAudioFxLoudness(it.toInt()) },
            valueRange = 0f..1000f,
            enabled = controlsOn,
        )
    }
}
