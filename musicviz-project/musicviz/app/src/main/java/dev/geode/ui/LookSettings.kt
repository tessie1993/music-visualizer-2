package dev.geode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.geode.R

/**
 * LOOK: everything about how the app itself is dressed - the theme stones,
 * the writing (font colour and size), and the layout of the control shell.
 * Every control applies live; nothing here needs a restart.
 */
@Composable
internal fun LookSettingsTab(viewModel: PlayerViewModel) {
    val gui by viewModel.guiPrefs.collectAsState()
    val appTheme by viewModel.theme.collectAsState()
    SettingsTabColumn {
        item {
            SettingsGroup(stringResource(R.string.look_group_theme)) {
                ThemePickerRow(viewModel, appTheme)
                Column {
                    Text(
                        stringResource(R.string.look_accent_intensity, (gui.accentIntensity * 100).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    CrystalSlider(
                        value = gui.accentIntensity,
                        onValueChange = { viewModel.setGuiPrefs(gui.copy(accentIntensity = it)) },
                        valueRange = 0.5f..1.5f,
                    )
                }
                Column {
                    Text(
                        stringResource(R.string.look_background_dim, (gui.backgroundDim * 100).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    CrystalSlider(
                        value = gui.backgroundDim,
                        onValueChange = { viewModel.setGuiPrefs(gui.copy(backgroundDim = it)) },
                        valueRange = 0f..0.6f,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.look_follow_system), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = gui.followSystemDark,
                        onCheckedChange = { viewModel.setGuiPrefs(gui.copy(followSystemDark = it)) },
                    )
                }
            }
        }
        item {
            SettingsGroup(stringResource(R.string.look_group_text)) {
                FontColorRow(viewModel, gui, appTheme)
                Column {
                    Text(
                        stringResource(R.string.look_text_size, (gui.textScale * 100).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    CrystalSlider(
                        value = gui.textScale,
                        onValueChange = { viewModel.setGuiPrefs(gui.copy(textScale = it)) },
                        valueRange = GuiPrefs.TEXT_SCALE_MIN..GuiPrefs.TEXT_SCALE_MAX,
                    )
                    Text(
                        stringResource(R.string.look_text_size_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SettingsGroup(stringResource(R.string.look_group_layout)) { LayoutGroup(viewModel, gui) } }
    }
}

/**
 * Material-true theme swatches: each chip renders inside a
 * [dev.geode.ui.theme.LocalThemePack] override so [crystalPanel] tiles the
 * CANDIDATE pack's photographed stone rather than the current one's - a tray
 * of material samples, not a row of labeled buttons. Order is the catalog's,
 * which is the pack import order.
 */
@Composable
private fun ThemePickerRow(
    viewModel: PlayerViewModel,
    current: dev.geode.ui.theme.ThemePack,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(dev.geode.ui.theme.ThemePackCatalog.all) { t ->
            val sel = t.slug == current.slug
            CompositionLocalProvider(dev.geode.ui.theme.LocalThemePack provides t) {
                Column(
                    Modifier
                        .width(88.dp)
                        .crystalPanel(
                            if (sel) 0.95f else 0.8f,
                            t.palette.surface,
                            t.palette.primary,
                            corner = 14.dp,
                            glowStrength = if (sel) 1.1f else 0.35f,
                            prismatic = sel,
                            sheen = t.palette.secondary,
                        ).clickable { viewModel.setTheme(t) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CrystalGem(t.palette.primary, size = 9.dp, glow = sel)
                    Text(
                        t.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = t.palette.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Font colour, as the curated swatch row ([FontColorChoice.CHOICES], Auto
 * first). Applies live through [GuiPrefs.fontColorArgb]. On light themes the
 * swatches that would be unreadable ([AppTheme.fontColorActive] false) are
 * greyed out and inert rather than silently ignored after the tap. The
 * "White" swatch is what the retired white-font switch became.
 */
@Composable
private fun FontColorRow(
    viewModel: PlayerViewModel,
    gui: GuiPrefs,
    appTheme: dev.geode.ui.theme.ThemePack,
) {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(stringResource(R.string.look_font_color), style = MaterialTheme.typography.labelMedium)
        LazyRow(
            Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(FontColorChoice.CHOICES) { choice ->
                // The dim is part of the question: it is what decides whether
                // this theme is painting a light surface right now, and the
                // greying here has to agree with what colorScheme will do.
                val usable = choice.argb == null || appTheme.fontColorActive(choice.argb, gui.backgroundDim)
                val sel = gui.fontColorArgb == choice.argb
                val shape = crystalShardShape(8.dp, 3.dp)
                val fill =
                    choice.argb?.let { argb ->
                        val c = Color(argb)
                        Brush.verticalGradient(listOf(c, lerp(c, Color.Black, 0.22f)))
                    } ?: Brush.verticalGradient(listOf(cs.primary, cs.secondary))
                Column(
                    Modifier
                        .graphicsLayer { alpha = if (usable) 1f else 0.35f }
                        .clickable(enabled = usable) {
                            // whiteFont is the retired legacy switch; cleared on
                            // every pick so it can never resurrect an override
                            // after the user chooses Auto.
                            viewModel.setGuiPrefs(gui.copy(fontColorArgb = choice.argb, whiteFont = false))
                        }.padding(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(shape)
                            .background(fill)
                            .border(
                                1.dp,
                                if (sel) Color.White.copy(alpha = 0.9f) else cs.primary.copy(alpha = 0.35f),
                                shape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sel) {
                            val swatch = choice.argb?.let { Color(it) } ?: cs.primary
                            CrystalGem(if (swatch.luminance() > 0.55f) Color.Black else Color.White, size = 7.dp)
                        }
                    }
                    Text(
                        stringResource(choice.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sel) accentTextColor() else cs.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Text(
            stringResource(
                if (appTheme.isLight) R.string.look_font_color_light_hint else R.string.look_font_color_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The control-shell layout knobs: bars, corners, player placement. */
@Composable
private fun LayoutGroup(
    viewModel: PlayerViewModel,
    gui: GuiPrefs,
) {
    Column {
        Text(stringResource(R.string.look_bar_opacity, (gui.barOpacity * 100).toInt()), style = MaterialTheme.typography.labelMedium)
        CrystalSlider(
            value = gui.barOpacity,
            onValueChange = { viewModel.setGuiPrefs(gui.copy(barOpacity = it)) },
            valueRange = 0.2f..1f,
        )
    }
    Column {
        Text(stringResource(R.string.look_player_position), style = MaterialTheme.typography.labelMedium)
        CrystalSegmented(
            options = PlayerPosition.entries.map { stringResource(it.labelRes) },
            selected = PlayerPosition.entries.indexOf(gui.playerPosition),
            onSelect = { viewModel.setGuiPrefs(gui.copy(playerPosition = PlayerPosition.entries[it])) },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Column {
        Text(stringResource(R.string.look_corner_style), style = MaterialTheme.typography.labelMedium)
        CrystalSegmented(
            options = CornerStyle.entries.map { stringResource(it.labelRes) },
            selected = CornerStyle.entries.indexOf(gui.cornerStyle),
            onSelect = { viewModel.setGuiPrefs(gui.copy(cornerStyle = CornerStyle.entries[it])) },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.look_compact_player), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = gui.compactPlayer,
            onCheckedChange = { viewModel.setGuiPrefs(gui.copy(compactPlayer = it)) },
        )
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.look_clear_visuals_menu), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gui.clearVisualsMenu,
                onCheckedChange = { viewModel.setGuiPrefs(gui.copy(clearVisualsMenu = it)) },
            )
        }
        Text(
            stringResource(R.string.look_clear_visuals_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    BootAnimationRow()
}

/**
 * Boot animation toggle. Persisted directly rather than through GuiPrefs
 * because AppRoot reads it before any ViewModel exists, at start.
 */
@Composable
private fun BootAnimationRow() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("geode-prefs", android.content.Context.MODE_PRIVATE) }
    var bootAnim by remember { mutableStateOf(prefs.getBoolean("boot_anim", true)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.look_boot_animation), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = bootAnim, onCheckedChange = {
            bootAnim = it
            prefs.edit().putBoolean("boot_anim", it).apply()
        })
    }
}
