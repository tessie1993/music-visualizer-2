package dev.geode.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.R
import dev.geode.audio.AudioFxFormat
import dev.geode.audio.AudioFxState

/**
 * "Equalizer" group: one crystal card ([SettingsGroup], same material as the
 * rest of the Settings tabs - the stock Material Card it used to ride on was
 * the one flat-grey surface in the shell). The master switch sits on the
 * card's own header row, then device preset chips, one horizontal slider per
 * band (vertical sliders are awkward in plain Compose), plus bass boost and
 * loudness. Everything greys out while the chain is off.
 *
 * Three states, not two. `available` alone cannot tell "the device refused
 * the effect" apart from "there is no audio session to attach to yet", and
 * the second is EVERY cold start: ExoPlayer's session id is UNSET until its
 * sink first initializes, so before a single note has played `attach()`
 * returns early, there is no Equalizer, and the card used to declare the
 * device unsupported and disable itself. [AudioFxState.attached] is the
 * distinction, and it is why it exists.
 *
 * The three effects are also independent grants - a device may hand out a
 * BassBoost and refuse an Equalizer - so each control follows its own flag
 * rather than all three riding on the equalizer's.
 */
@Composable
fun EqualizerSettings(viewModel: PlayerViewModel) {
    val fx by viewModel.audioFx.collectAsState()
    EqualizerCard(
        fx = fx,
        onEnabled = viewModel::setAudioFxEnabled,
        onPreset = viewModel::useAudioFxPreset,
        onBand = viewModel::setAudioFxBand,
        onBassBoost = viewModel::setAudioFxBassBoost,
        onLoudness = viewModel::setAudioFxLoudness,
    )
}

/**
 * The card itself, over a plain [AudioFxState].
 *
 * Split from the ViewModel binding above so the three states can be composed
 * and read back: which one is showing depends on flags a test cannot make a
 * real device produce (a session id of 0 exists only before the audio sink
 * has ever initialised, and no shadow reproduces a device that grants a
 * BassBoost while refusing an Equalizer).
 */
@Composable
internal fun EqualizerCard(
    fx: AudioFxState,
    onEnabled: (Boolean) -> Unit,
    onPreset: (Int) -> Unit,
    onBand: (Int, Int) -> Unit,
    onBassBoost: (Int) -> Unit,
    onLoudness: (Int) -> Unit,
) {
    val anyEffect = fx.available || fx.bassAvailable || fx.loudnessAvailable
    SettingsGroup(
        title = stringResource(R.string.eq_title),
        header = {
            Switch(
                checked = fx.enabled && anyEffect,
                onCheckedChange = onEnabled,
                enabled = anyEffect,
            )
        },
    ) {
        if (!fx.attached) {
            Text(
                stringResource(R.string.eq_no_session),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsGroup
        }
        if (!anyEffect) {
            Text(
                stringResource(R.string.eq_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsGroup
        }
        val controlsOn = fx.enabled
        if (!fx.available) {
            Text(
                stringResource(R.string.eq_partial),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (fx.presets.isNotEmpty()) {
            Text(stringResource(R.string.eq_preset), style = MaterialTheme.typography.labelMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(fx.presets) { i, name ->
                    FilterChip(
                        selected = fx.presetIndex == i,
                        onClick = { onPreset(i) },
                        label = { Text(name) },
                        enabled = controlsOn,
                    )
                }
            }
            if (fx.presetIndex < 0) {
                Text(
                    stringResource(R.string.eq_custom),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        fx.bands.forEachIndexed { i, band ->
            Text(
                stringResource(R.string.eq_band, band.label, AudioFxFormat.dbLabel(band.levelMb)),
                style = MaterialTheme.typography.labelMedium,
            )
            CrystalSlider(
                value = band.levelMb.toFloat(),
                onValueChange = { onBand(i, it.toInt()) },
                valueRange = band.minMb.toFloat()..band.maxMb.toFloat(),
                enabled = controlsOn,
            )
        }
        if (fx.bassAvailable) {
            Text(
                stringResource(R.string.eq_bass_boost, fx.bassBoost / 10),
                style = MaterialTheme.typography.labelMedium,
            )
            CrystalSlider(
                value = fx.bassBoost.toFloat(),
                onValueChange = { onBassBoost(it.toInt()) },
                valueRange = 0f..1000f,
                enabled = controlsOn,
            )
        }
        if (fx.loudnessAvailable) {
            Text(
                stringResource(R.string.eq_loudness, AudioFxFormat.dbLabel(fx.loudness)),
                style = MaterialTheme.typography.labelMedium,
            )
            CrystalSlider(
                value = fx.loudness.toFloat(),
                onValueChange = { onLoudness(it.toInt()) },
                valueRange = 0f..1000f,
                enabled = controlsOn,
            )
        }
    }
}
