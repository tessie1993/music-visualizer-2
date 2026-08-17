package dev.geode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import dev.geode.render.VisualSafety
import dev.geode.render.VisualSafetyChoice
import kotlin.math.roundToInt

/**
 * BEHAVIOR: the standing rules for how the visualizer acts - touch gestures,
 * a connected display, the photosensitivity/motion limits, the two modes
 * that change the look by themselves, and the live wallpaper.
 */
@Composable
internal fun BehaviorSettingsTab(viewModel: PlayerViewModel) {
    val gui by viewModel.guiPrefs.collectAsState()
    SettingsTabColumn {
        item { SettingsGroup("Touch") { TouchGroup(viewModel, gui) } }
        item { SettingsGroup("Connected display") { ConnectedDisplayGroup(viewModel, gui) } }
        item { SettingsGroup("Visual safety") { VisualSafetyGroup(viewModel, gui) } }
        item { SettingsGroup("Auto visuals") { AutoVisualsGroup(viewModel) } }
        item { SettingsGroup("Live wallpaper") { LiveWallpaperGroup() } }
    }
}

/** Finger gestures on the fullscreen canvas: smear, and pinch/twist. */
@Composable
private fun TouchGroup(
    viewModel: PlayerViewModel,
    gui: GuiPrefs,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Smear the visuals with a finger", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.touchSmear,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(touchSmear = it)) },
            )
        }
        Text(
            "Drag on the fullscreen visualizer to push the image around: the drag raises the " +
                "surface ahead of your finger and dips it behind, and whatever is on screen bends " +
                "through it. On the Water style it stirs the pool itself and paints into it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (gui.touchSmear) {
            Text("Smear strength  ${(gui.touchSmearStrength * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            CrystalSlider(
                value = gui.touchSmearStrength,
                onValueChange = { viewModel.setGuiPrefs(gui.copy(touchSmearStrength = it)) },
                valueRange = 0.2f..2f,
            )
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Pinch and twist the canvas", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.touchTransform,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(touchTransform = it)) },
            )
        }
        Text(
            "Two fingers on the fullscreen visualizer: pinch moves the Zoom slider, twist moves " +
                "Rotation. They are the same controls the Customize panel shows, so a gesture is " +
                "saved into presets and takes — and undone by dragging the slider back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** HDMI/cast: visuals on the big screen, controls on the phone. */
@Composable
private fun ConnectedDisplayGroup(
    viewModel: PlayerViewModel,
    gui: GuiPrefs,
) {
    val external = rememberExternalDisplay()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use a connected display", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.secondScreen,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(secondScreen = it)) },
            )
        }
        Text(
            if (external != null) {
                "Connected: ${external.name}. The visuals play there and the phone becomes the control " +
                    "surface — the canvas moves rather than being mirrored, so the big screen shows " +
                    "exactly what the app renders."
            } else {
                "Nothing connected. Plug in HDMI or start casting, and the visuals move to that screen " +
                    "while the phone keeps the controls."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The choices a user can pick, in the order they are offered.
 * [VisualSafetyChoice.UNKNOWN] is deliberately absent: it is a state the app
 * can be in, not one anybody selects.
 */
private val SAFETY_CHOICES =
    listOf(
        VisualSafetyChoice.SAFE to "Safe",
        VisualSafetyChoice.REDUCED_MOTION to "Reduced motion",
        VisualSafetyChoice.CUSTOM to "Custom",
    )

/**
 * Photosensitivity and motion-comfort limits. Kept as its own group, near
 * the top of Behavior, because it is the one settings group a user may be
 * looking for before they let the app draw anything at all.
 */
@Composable
private fun VisualSafetyGroup(
    viewModel: PlayerViewModel,
    gui: GuiPrefs,
) {
    Column {
        Text("Flashing and motion", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Limits how fast and how strongly the whole screen can flash: caps the strobe and " +
                "beat flash, holds brightness and contrast near neutral, turns hard scene cuts into " +
                "crossfades, and slows any modulation aimed at brightness.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (gui.safetyChoice == VisualSafetyChoice.UNKNOWN) {
            // The whole reason the choice has an "unknown" state: until this
            // is answered the visuals run limited, and the user is told so
            // rather than left to wonder why the strobe looks tame.
            Text(
                "You have not chosen yet, so the visuals are running limited. Pick one — you can " +
                    "change it whenever you like.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        CrystalSegmented(
            options = SAFETY_CHOICES.map { it.second },
            // -1 while the choice is UNKNOWN: nothing is shown selected,
            // because nothing has been chosen. Presenting Safe as picked
            // would be the app answering a question asked of the user.
            selected = SAFETY_CHOICES.indexOfFirst { it.first == gui.safetyChoice },
            onSelect = { viewModel.setGuiPrefs(gui.copy(safetyChoice = SAFETY_CHOICES[it].first)) },
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            when (gui.safetyChoice) {
                VisualSafetyChoice.UNKNOWN, VisualSafetyChoice.SAFE ->
                    "Recommended if you or anyone watching is sensitive to flashing light."
                VisualSafetyChoice.REDUCED_MOTION ->
                    "Everything Safe does, plus speed, drift, shake and endless zoom scaled down " +
                        "for motion comfort. Colour and texture keep reacting to the music."
                VisualSafetyChoice.CUSTOM ->
                    "Your own limits, including switching them off entirely. The strobe then runs " +
                        "at 9 Hz and the beat flash at the track's rate."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (gui.safetyChoice == VisualSafetyChoice.CUSTOM) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Limit flashing", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.safeVisuals,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(safeVisuals = it)) },
            )
        }
    }
    if (gui.safetyChoice == VisualSafetyChoice.CUSTOM && gui.safeVisuals) {
        Column {
            Text(
                "Maximum flashes per second  ${"%.1f".format(gui.maxFlashHz)} Hz" +
                    if (gui.maxFlashHz <= VisualSafety.WCAG_FLASHES_PER_SECOND) "  (within guidance)" else "",
                style = MaterialTheme.typography.labelMedium,
            )
            CrystalSlider(
                value = gui.maxFlashHz,
                onValueChange = { viewModel.setGuiPrefs(gui.copy(maxFlashHz = it)) },
                valueRange = 1f..VisualSafety.DEFAULT_STROBE_HZ,
            )
            Text(
                "Published guidance (WCAG 2.3.1) puts the general limit at three per second; the " +
                    "risk is highest between about 15 and 20. Without this the strobe runs at 9.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Maximum flash strength  ${(gui.maxFlashDepth * 100).roundToInt()}%" +
                    if (gui.maxFlashDepth <= 0f) "  (no flashing at all)" else "",
                style = MaterialTheme.typography.labelMedium,
            )
            CrystalSlider(
                value = gui.maxFlashDepth,
                onValueChange = { viewModel.setGuiPrefs(gui.copy(maxFlashDepth = it)) },
                valueRange = 0f..1f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Allow invert and solarize",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = gui.allowInversion,
                    onCheckedChange = { viewModel.setGuiPrefs(gui.copy(allowInversion = it)) },
                )
            }
            Text(
                "These reverse the whole frame at once. Off is safer; on keeps them available if " +
                    "you are limiting the flash rate alone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (gui.safetyChoice == VisualSafetyChoice.CUSTOM) {
        // Only under Custom. Safe and Reduced motion already say what happens
        // to motion, and a switch that silently does nothing under those two
        // would be worse than no switch at all.
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Slow the motion down", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = gui.reducedMotion,
                    onCheckedChange = { viewModel.setGuiPrefs(gui.copy(reducedMotion = it)) },
                )
            }
            Text(
                "Slows movement, shake, drift and rotation. Independent of the flash limits above: " +
                    "this one is about motion comfort rather than seizures, and either can be used " +
                    "on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        "Both settings apply to exported video as well as the screen.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The wallpaper hand-off, with the honest battery note. */
@Composable
private fun LiveWallpaperGroup() {
    val ctx = LocalContext.current
    Text(
        "Set the visualizer as your wallpaper. It uses the style and settings the app was " +
            "last showing, reacts to whatever Geode is playing, and drifts gently on its " +
            "own the rest of the time. It draws nothing while another app is in front, so it " +
            "is not a background battery drain.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    CrystalButton(onClick = {
        // The direct "change to THIS wallpaper" screen; some launchers do not
        // implement it, so fall back to the system's live-wallpaper list
        // rather than doing nothing.
        val direct =
            android.content.Intent(
                android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER,
            ).putExtra(
                android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                android.content.ComponentName(
                    ctx,
                    dev.geode.wallpaper.VisualizerWallpaperService::class.java,
                ),
            )
        val ok = runCatching { ctx.startActivity(direct) }.isSuccess
        if (!ok) {
            runCatching {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_SET_WALLPAPER))
            }
        }
    }) { Text("Set as live wallpaper") }
}
