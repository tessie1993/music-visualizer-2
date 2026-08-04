package dev.musicviz.ui

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
        "Two modes rotate the look on a clock while a track plays. Only one of them runs at a time: " +
            "starting the visual playlist stops Random, and turning Random on stops the playlist.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CrystalOverline("Random", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column {
        Text(
            if (viz.randomEnabled) {
                "Random is running."
            } else if (viz.vizPlaylistEnabled) {
                "Random is off — the visual playlist below has it."
            } else {
                "Random is off — the Auto button on the Now Playing screen turns it on."
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (viz.randomEnabled) accentTextColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "The settings below shape it whether it is running or not, so a session can be set up before " +
                "it starts — and they are remembered across restarts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column {
        Text("Switch every ${viz.randomIntervalSec} s", style = MaterialTheme.typography.labelMedium)
        // Range is the setter's own clamp, so the slider cannot ask for a
        // value the view model will quietly refuse.
        CrystalSlider(
            value = viz.randomIntervalSec.toFloat(),
            onValueChange = { viewModel.setRandomInterval(it.roundToInt()) },
            valueRange = INTERVAL_RANGE,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Switch on a strong beat", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.randomOnBeat, onCheckedChange = viewModel::setRandomOnBeat)
        }
        Text(
            "Waits for a big moment in the music instead of switching the instant the timer is up, so a " +
                "change lands with the track rather than across it. It still holds a look for at least half " +
                "the interval, and forces a switch at twice it, so a quiet passage cannot stall the rotation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column {
        Text("Pick from", style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Styles", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.randomIncludeStyles, onCheckedChange = viewModel::setRandomIncludeStyles)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Saved presets", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.randomIncludePresets, onCheckedChange = viewModel::setRandomIncludePresets)
        }
        // The engine drops .milk picks when libprojectM is missing, so on a
        // device without it the switch would be a control that changes
        // nothing - say so rather than offering it.
        if (dev.musicviz.render.scene.PMBridge.available) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MilkDrop presets", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = viz.randomIncludeMilk, onCheckedChange = viewModel::setRandomIncludeMilk)
            }
        }
        Text(
            if (nothingToPickFrom) {
                "Nothing is selected, so Random has nothing to switch to and will leave the visuals alone."
            } else {
                "Styles are the built-in looks; saved presets carry their own settings with them."
            },
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
            Text("Roll the colours too", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.randomizeColors, onCheckedChange = viewModel::setRandomizeColors)
        }
        Text(
            "Rolls both palettes, the blend between them and the hue shift on every switch. It clears a " +
                "custom palette you made in Customize › Color, because that override outranks the palettes " +
                "being rolled and the new ones would otherwise never show.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    CrystalOverline("Visual playlist", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Play the visual playlist", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.vizPlaylistEnabled, onCheckedChange = viewModel::setVizPlaylistEnabled)
        }
        Text(
            when {
                // Named before the tap, not discovered after it: this is the
                // one place a user can set two switches that contradict.
                viz.randomEnabled -> "Random is running — turning this on will stop it."
                viz.vizPlaylist.size >= 2 -> "${viz.vizPlaylist.size} looks in the playlist."
                else ->
                    "Add looks with the heart button in Visuals › Presets. The playlist needs at least " +
                        "two before it has anywhere to go."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Column {
        Text("Switch every ${viz.vizPlaylistIntervalSec} s", style = MaterialTheme.typography.labelMedium)
        CrystalSlider(
            value = viz.vizPlaylistIntervalSec.toFloat(),
            onValueChange = { viewModel.setVizPlaylistInterval(it.roundToInt()) },
            valueRange = INTERVAL_RANGE,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wait for a strong moment", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = viz.vizPlaylistIntelligent, onCheckedChange = viewModel::setVizPlaylistIntelligent)
        }
        Text(
            "The same timing Random's \"strong beat\" uses, applied to the playlist order: the next look " +
                "still comes next, it just waits for a moment worth arriving on.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
