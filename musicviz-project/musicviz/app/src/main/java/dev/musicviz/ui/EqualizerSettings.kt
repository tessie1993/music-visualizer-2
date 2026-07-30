package dev.musicviz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.musicviz.audio.AudioFxFormat

/**
 * "Equalizer" settings card: master switch, device preset chips, one
 * horizontal slider per band (vertical sliders are awkward in plain Compose),
 * plus bass boost and loudness. Everything greys out while the chain is off,
 * and the whole card degrades to a "not supported" note on devices whose
 * audiofx constructors fail (common on emulators). Rendered as a crystal
 * glass panel per the design mockups.
 */
@Composable
fun EqualizerSettings(viewModel: PlayerViewModel) {
    val fx by viewModel.audioFx.collectAsState()
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .crystalPanel(0.25f, cs.surfaceVariant, cs.primary, corner = 18.dp, glowStrength = 0.4f)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CrystalOverline("Equalizer", Modifier.weight(1f))
            Switch(
                checked = fx.enabled && fx.available,
                onCheckedChange = { viewModel.setAudioFxEnabled(it) },
                enabled = fx.available,
                colors = crystalSwitchColors(),
            )
        }
        if (!fx.available) {
            Text(
                "Not supported on this device",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        val controlsOn = fx.enabled
        if (fx.presets.isNotEmpty()) {
            Text("Preset", style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(fx.presets) { i, name ->
                    CrystalChip(
                        name,
                        selected = fx.presetIndex == i,
                        onClick = { if (controlsOn) viewModel.useAudioFxPreset(i) },
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
            CrystalSliderRow(
                band.label,
                band.levelMb.toFloat(),
                band.minMb.toFloat()..band.maxMb.toFloat(),
                onChange = { viewModel.setAudioFxBand(i, it.toInt()) },
                valueText = AudioFxFormat.dbLabel(band.levelMb),
                enabled = controlsOn,
            )
        }
        CrystalSliderRow(
            "Bass boost",
            fx.bassBoost.toFloat(),
            0f..1000f,
            onChange = { viewModel.setAudioFxBassBoost(it.toInt()) },
            valueText = "${fx.bassBoost / 10}%",
            enabled = controlsOn,
        )
        CrystalSliderRow(
            "Loudness",
            fx.loudness.toFloat(),
            0f..1000f,
            onChange = { viewModel.setAudioFxLoudness(it.toInt()) },
            valueText = AudioFxFormat.dbLabel(fx.loudness),
            enabled = controlsOn,
        )
    }
}
