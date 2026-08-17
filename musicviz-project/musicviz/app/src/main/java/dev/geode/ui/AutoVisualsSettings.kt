package dev.geode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.geode.R
import dev.musicviz.render.scene.PMBridge
import kotlin.math.roundToInt

/** Slider range shared with the setters' clamps and the persistence store. */
private val INTERVAL_RANGE =
    AutoVisualsPrefsStore.INTERVAL_SEC.first.toFloat()..AutoVisualsPrefsStore.INTERVAL_SEC.last.toFloat()

/**
 * "Auto visuals": the two modes that change the look by themselves while a
 * track plays - Random, which jumps to something new, and the visual playlist,
 * which walks the looks the user hearted in Visuals › Presets.
 *
 * Settings rather than the Visuals hub. The hub's tabs all manipulate the
 * visual that is on screen right now; these decide how the app CHOOSES looks
 * over time, which is standing behaviour of the same kind as the rest of the
 * Behavior tab, and it is where a user goes looking for a rule rather than
 * for a picture. Every knob here persists (see [AutoVisualsPrefsStore]); the
 * playlist's CONTENTS stay with the hearts that build them.
 *
 * Random's own on/off stays on the Now Playing "Auto" button, which cycles the
 * four exclusive auto modes through one control on purpose - a second switch
 * here could put that control's label out of step with the engine, which is
 * the exact confusion the cycle exists to prevent. So this section shapes
 * Random and reports its state; it does not fork ownership of it.
 *
 * The playlist and Random are mutually exclusive in the engine
 * ([PlayerViewModel.setVizPlaylistEnabled] clears `randomEnabled`), so the
 * trade is stated on the switch that makes it and is visible in the Random
 * status line afterwards. A user must never be left wondering which of two
 * switches they set silently won.
 */
@Composable
internal fun AutoVisualsGroup(viewModel: PlayerViewModel) {
    val viz by viewModel.vizState.collectAsState()
    val nothingToPickFrom = !viz.randomIncludeStyles && !viz.randomIncludePresets && !viz.randomIncludeMilk
    Text(
        stringResource(R.string.autoviz_intro),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CrystalOverline(stringResource(R.string.autoviz_random_overline), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column {
        Text(
            stringResource(
                if (viz.randomEnabled) {
                    R.string.autoviz_random_running
                } else if (viz.vizPlaylistEnabled) {
                    R.string.autoviz_random_off_playlist
                } else {
                    R.string.autoviz_random_off_auto
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (viz.randomEnabled) accentTextColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.autoviz_shape_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column {
        Text(stringResource(R.string.autoviz_switch_every, viz.randomIntervalSec), style = MaterialTheme.typography.labelMedium)
        // Range is the setter's own clamp, so the slider cannot ask for a
        // value the view model will quietly refuse.
        CrystalSlider(
            value = viz.randomIntervalSec.toFloat(),
            onValueChange = { viewModel.setRandomInterval(it.roundToInt()) },
            valueRange = INTERVAL_RANGE,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.autoviz_on_beat), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.randomOnBeat, onCheckedChange = viewModel::setRandomOnBeat)
        }
        Text(
            stringResource(R.string.autoviz_on_beat_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column {
        Text(stringResource(R.string.autoviz_pick_from), style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.autoviz_pick_styles), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.randomIncludeStyles, onCheckedChange = viewModel::setRandomIncludeStyles)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.autoviz_pick_presets), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.randomIncludePresets, onCheckedChange = viewModel::setRandomIncludePresets)
        }
        // The engine drops .milk picks when libprojectM is missing, so on a
        // device without it the switch would be a control that changes
        // nothing - say so rather than offering it.
        if (PMBridge.available) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.autoviz_pick_milk), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = viz.randomIncludeMilk, onCheckedChange = viewModel::setRandomIncludeMilk)
            }
        }
        Text(
            stringResource(
                if (nothingToPickFrom) R.string.autoviz_nothing_selected else R.string.autoviz_pick_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (nothingToPickFrom) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.autoviz_roll_colors), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.randomizeColors, onCheckedChange = viewModel::setRandomizeColors)
        }
        Text(
            stringResource(R.string.autoviz_roll_colors_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    CrystalOverline(stringResource(R.string.autoviz_playlist_overline), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.autoviz_playlist_play), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.vizPlaylistEnabled, onCheckedChange = viewModel::setVizPlaylistEnabled)
        }
        Text(
            when {
                // Named before the tap, not discovered after it: this is the
                // one place a user can set two switches that contradict.
                viz.randomEnabled -> stringResource(R.string.autoviz_playlist_random_running)
                viz.vizPlaylist.size >= 2 ->
                    pluralStringResource(
                        R.plurals.autoviz_playlist_count,
                        viz.vizPlaylist.size,
                        viz.vizPlaylist.size,
                    )
                else -> stringResource(R.string.autoviz_playlist_add_hint)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column {
        Text(stringResource(R.string.autoviz_switch_every, viz.vizPlaylistIntervalSec), style = MaterialTheme.typography.labelMedium)
        CrystalSlider(
            value = viz.vizPlaylistIntervalSec.toFloat(),
            onValueChange = { viewModel.setVizPlaylistInterval(it.roundToInt()) },
            valueRange = INTERVAL_RANGE,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.autoviz_wait_strong), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.vizPlaylistIntelligent, onCheckedChange = viewModel::setVizPlaylistIntelligent)
        }
        Text(
            stringResource(R.string.autoviz_wait_strong_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
