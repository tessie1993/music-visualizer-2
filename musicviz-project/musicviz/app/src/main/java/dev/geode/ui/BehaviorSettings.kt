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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.geode.R
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
        item { SettingsGroup(stringResource(R.string.behavior_group_touch)) { TouchGroup(viewModel, gui) } }
        item { SettingsGroup(stringResource(R.string.behavior_group_display)) { ConnectedDisplayGroup(viewModel, gui) } }
        item { SettingsGroup(stringResource(R.string.behavior_group_safety)) { VisualSafetyGroup(viewModel, gui) } }
        item { SettingsGroup(stringResource(R.string.behavior_group_auto)) { AutoVisualsGroup(viewModel) } }
        item { SettingsGroup(stringResource(R.string.behavior_group_wallpaper)) { LiveWallpaperGroup() } }
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
            Text(stringResource(R.string.behavior_touch_smear), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.touchSmear,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(touchSmear = it)) },
            )
        }
        Text(
            stringResource(R.string.behavior_touch_smear_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (gui.touchSmear) {
            Text(
                stringResource(R.string.behavior_smear_strength, (gui.touchSmearStrength * 100).toInt()),
                style = MaterialTheme.typography.labelMedium,
            )
            CrystalSlider(
                value = gui.touchSmearStrength,
                onValueChange = { viewModel.setGuiPrefs(gui.copy(touchSmearStrength = it)) },
                valueRange = 0.2f..2f,
            )
        }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.behavior_touch_transform), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.touchTransform,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(touchTransform = it)) },
            )
        }
        Text(
            stringResource(R.string.behavior_touch_transform_hint),
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
            Text(stringResource(R.string.behavior_display_use), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.secondScreen,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(secondScreen = it)) },
            )
        }
        Text(
            if (external != null) {
                stringResource(R.string.behavior_display_connected, external.name)
            } else {
                stringResource(R.string.behavior_display_none)
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
        VisualSafetyChoice.SAFE to R.string.behavior_safety_safe,
        VisualSafetyChoice.REDUCED_MOTION to R.string.behavior_safety_reduced,
        VisualSafetyChoice.CUSTOM to R.string.behavior_safety_custom,
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
        Text(stringResource(R.string.behavior_safety_title), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.behavior_safety_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (gui.safetyChoice == VisualSafetyChoice.UNKNOWN) {
            // The whole reason the choice has an "unknown" state: until this
            // is answered the visuals run limited, and the user is told so
            // rather than left to wonder why the strobe looks tame.
            Text(
                stringResource(R.string.behavior_safety_unanswered),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        CrystalSegmented(
            options = SAFETY_CHOICES.map { stringResource(it.second) },
            // -1 while the choice is UNKNOWN: nothing is shown selected,
            // because nothing has been chosen. Presenting Safe as picked
            // would be the app answering a question asked of the user.
            selected = SAFETY_CHOICES.indexOfFirst { it.first == gui.safetyChoice },
            onSelect = { viewModel.setGuiPrefs(gui.copy(safetyChoice = SAFETY_CHOICES[it].first)) },
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            stringResource(
                when (gui.safetyChoice) {
                    VisualSafetyChoice.UNKNOWN, VisualSafetyChoice.SAFE -> R.string.behavior_safety_safe_hint
                    VisualSafetyChoice.REDUCED_MOTION -> R.string.behavior_safety_reduced_hint
                    VisualSafetyChoice.CUSTOM -> R.string.behavior_safety_custom_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (gui.safetyChoice == VisualSafetyChoice.CUSTOM) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.behavior_limit_flashing), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.safeVisuals,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(safeVisuals = it)) },
            )
        }
    }
    if (gui.safetyChoice == VisualSafetyChoice.CUSTOM && gui.safeVisuals) {
        Column {
            Text(
                stringResource(R.string.behavior_max_flash_hz, "%.1f".format(gui.maxFlashHz)) +
                    if (gui.maxFlashHz <= VisualSafety.WCAG_FLASHES_PER_SECOND) {
                        stringResource(R.string.behavior_within_guidance)
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.labelMedium,
            )
            CrystalSlider(
                value = gui.maxFlashHz,
                onValueChange = { viewModel.setGuiPrefs(gui.copy(maxFlashHz = it)) },
                valueRange = 1f..VisualSafety.DEFAULT_STROBE_HZ,
            )
            Text(
                stringResource(R.string.behavior_flash_guidance),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.behavior_max_flash_depth, (gui.maxFlashDepth * 100).roundToInt()) +
                    if (gui.maxFlashDepth <= 0f) stringResource(R.string.behavior_no_flashing) else "",
                style = MaterialTheme.typography.labelMedium,
            )
            CrystalSlider(
                value = gui.maxFlashDepth,
                onValueChange = { viewModel.setGuiPrefs(gui.copy(maxFlashDepth = it)) },
                valueRange = 0f..1f,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.behavior_allow_inversion),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = gui.allowInversion,
                    onCheckedChange = { viewModel.setGuiPrefs(gui.copy(allowInversion = it)) },
                )
            }
            Text(
                stringResource(R.string.behavior_inversion_hint),
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
                Text(stringResource(R.string.behavior_slow_motion), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = gui.reducedMotion,
                    onCheckedChange = { viewModel.setGuiPrefs(gui.copy(reducedMotion = it)) },
                )
            }
            Text(
                stringResource(R.string.behavior_slow_motion_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        stringResource(R.string.behavior_safety_export_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The wallpaper hand-off, with the honest battery note. */
@Composable
private fun LiveWallpaperGroup() {
    val ctx = LocalContext.current
    Text(
        stringResource(R.string.behavior_wallpaper_hint),
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
    }) { Text(stringResource(R.string.behavior_wallpaper_button)) }
}
