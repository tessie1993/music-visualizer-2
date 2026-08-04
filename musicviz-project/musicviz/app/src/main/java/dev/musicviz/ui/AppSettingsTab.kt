package dev.musicviz.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.musicviz.analysis.FeatureExtractor
import dev.musicviz.render.VisualSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A collapsible settings group: header row with title and chevron toggles
 * the section body; expanded state is remembered per section.
 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CrystalGem(MaterialTheme.colorScheme.primary, size = 6.dp, glow = expanded)
            Text(
                title.uppercase(),
                Modifier.weight(1f).padding(start = 10.dp),
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.2.sp),
                color = accentTextColor(),
            )
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(1.dp)
                .luminousHairline(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        )
    }
}

/** The app-preferences half of [SettingsScreen]. */
@Composable
internal fun AppSettingsTab(
    viewModel: PlayerViewModel,
    onOpenExport: () -> Unit,
) {
    val gui by viewModel.guiPrefs.collectAsState()
    val appTheme by viewModel.theme.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SettingsSection("Appearance") {
                CrystalOverline("Theme", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(AppTheme.entries.toList()) { t ->
                        val sel = t == appTheme
                        Box(
                            Modifier
                                .crystalPanel(
                                    if (sel) 0.55f else 0.25f,
                                    if (sel) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                    MaterialTheme.colorScheme.primary,
                                    corner = 20.dp,
                                    glowStrength = if (sel) 1.2f else 0.4f,
                                    prismatic = sel,
                                    sheen = MaterialTheme.colorScheme.secondary,
                                ).clickable { viewModel.setTheme(t) },
                        ) {
                            Text(
                                t.label,
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color =
                                    if (sel) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }
                    }
                }
                Column {
                    Text("Bar opacity  ${(gui.barOpacity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    CrystalSlider(
                        value = gui.barOpacity,
                        onValueChange = { viewModel.setGuiPrefs(gui.copy(barOpacity = it)) },
                        valueRange = 0.2f..1f,
                    )
                }
                Column {
                    Text("Player position", style = MaterialTheme.typography.labelMedium)
                    CrystalSegmented(
                        options = PlayerPosition.entries.map { it.label },
                        selected = PlayerPosition.entries.indexOf(gui.playerPosition),
                        onSelect = { viewModel.setGuiPrefs(gui.copy(playerPosition = PlayerPosition.entries[it])) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column {
                    Text("Corner style", style = MaterialTheme.typography.labelMedium)
                    CrystalSegmented(
                        options = CornerStyle.entries.map { it.label },
                        selected = CornerStyle.entries.indexOf(gui.cornerStyle),
                        onSelect = { viewModel.setGuiPrefs(gui.copy(cornerStyle = CornerStyle.entries[it])) },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text("Accent intensity  ${(gui.accentIntensity * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                CrystalSlider(
                    value = gui.accentIntensity,
                    onValueChange = { viewModel.setGuiPrefs(gui.copy(accentIntensity = it)) },
                    valueRange = 0.5f..1.5f,
                )
                Text("Background dim  ${(gui.backgroundDim * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                CrystalSlider(
                    value = gui.backgroundDim,
                    onValueChange = { viewModel.setGuiPrefs(gui.copy(backgroundDim = it)) },
                    valueRange = 0f..0.6f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Follow system light/dark", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = gui.followSystemDark,
                        onCheckedChange = { viewModel.setGuiPrefs(gui.copy(followSystemDark = it)) },
                    )
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("White font", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = gui.whiteFont,
                            onCheckedChange = { viewModel.setGuiPrefs(gui.copy(whiteFont = it)) },
                        )
                    }
                    Text(
                        "Forces labels and body text to pure white. No effect on light themes, where it would be unreadable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Compact mini-player", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = gui.compactPlayer,
                        onCheckedChange = { viewModel.setGuiPrefs(gui.copy(compactPlayer = it)) },
                    )
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Clear-overlay Visuals menu", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = gui.clearVisualsMenu,
                            onCheckedChange = { viewModel.setGuiPrefs(gui.copy(clearVisualsMenu = it)) },
                        )
                    }
                    Text(
                        "Text-only Visuals menu over the live visuals, so adjustments are visible as you make them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Boot animation toggle (persisted directly; read by AppRoot at start).
                val bootCtx = LocalContext.current
                val bootPrefs = remember { bootCtx.getSharedPreferences("musicviz-prefs", android.content.Context.MODE_PRIVATE) }
                var bootAnim by remember { mutableStateOf(bootPrefs.getBoolean("boot_anim", true)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Boot animation", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = bootAnim, onCheckedChange = {
                        bootAnim = it
                        bootPrefs.edit().putBoolean("boot_anim", it).apply()
                    })
                }
            }
        }
        item {
            SettingsSection("Other apps' audio") { ExternalAudioSettings(viewModel) }
        }
        item {
            SettingsSection("Live input & touch") { LiveInputSettings(viewModel) }
        }
        item {
            SettingsSection("Playback") {
                PlaybackSettingsSection(viewModel)
                EqualizerSettings(viewModel)
            }
        }
        item {
            SettingsSection("Library") {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val folderPicker =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        if (uri != null) {
                            ctx.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                            viewModel.setGuiPrefs(gui.copy(presetMirrorUri = uri.toString()))
                        }
                    }
                Column {
                    Text(
                        if (gui.presetMirrorUri != null) {
                            "Preset folder: chosen — saves are mirrored there"
                        } else {
                            "Preset folder: internal only"
                        },
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CrystalButton(filled = false, onClick = { folderPicker.launch(null) }) { Text("Choose preset folder") }
                        if (gui.presetMirrorUri != null) {
                            TextButton(onClick = { viewModel.setGuiPrefs(gui.copy(presetMirrorUri = null)) }) { Text("Clear") }
                        }
                    }
                }
                Text(
                    "Music folders are managed in Library › Folders.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            // Its own section, above the creative controls, because it is the
            // one settings group a user may be looking for before they let the
            // app draw anything at all.
            SettingsSection("Visual safety") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Safe visuals", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = gui.safeVisuals,
                            onCheckedChange = { viewModel.setGuiPrefs(gui.copy(safeVisuals = it)) },
                        )
                    }
                    Text(
                        "Limits how fast and how strongly the whole screen can flash: caps the strobe and " +
                            "beat flash, holds brightness and contrast near neutral, turns hard scene cuts into " +
                            "crossfades, and slows any modulation aimed at brightness. Recommended if you or " +
                            "anyone watching is sensitive to flashing light.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (gui.safeVisuals) {
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
                                "you turned Safe visuals on for the flash-rate limits alone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reduced motion", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = gui.reducedMotion,
                            onCheckedChange = { viewModel.setGuiPrefs(gui.copy(reducedMotion = it)) },
                        )
                    }
                    Text(
                        "Slows movement, shake, drift and rotation. Separate from Safe visuals: this one is " +
                            "about motion comfort rather than flashing, and either can be used on its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Both settings apply to exported video as well as the screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsSection("Visuals & Analysis") {
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
                    Text(
                        "Beat sensitivity  ${"%.1f".format(gui.beatThresholdSigma)}σ " +
                            "— drag right for LESS sensitive (fewer beat flashes)",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    // Range comes from the extractor so the slider can never
                    // saturate against a tighter clamp in AnalysisEngine.
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
                val context = androidx.compose.ui.platform.LocalContext.current
                var cacheInfo by remember { mutableStateOf("…") }
                var cacheBump by remember { mutableIntStateOf(0) }
                LaunchedEffect(cacheBump) {
                    cacheInfo =
                        withContext(Dispatchers.IO) {
                            val app = context.applicationContext
                            val n =
                                dev.musicviz.analysis.AnalysisCache
                                    .entryCount(app)
                            val mb =
                                dev.musicviz.analysis.AnalysisCache
                                    .sizeBytes(app) / (1024f * 1024f)
                            "%d tracks · %.1f MB".format(n, mb)
                        }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Analysis cache: $cacheInfo", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        dev.musicviz.analysis.AnalysisCache
                            .clear(context.applicationContext)
                        cacheBump++
                    }) { Text("Clear") }
                }
            }
        }
        item {
            SettingsSection("Auto visuals") { AutoVisualsSettings(viewModel) }
        }
        item {
            SettingsSection("Live wallpaper") {
                val ctx = LocalContext.current
                Text(
                    "Set the visualizer as your wallpaper. It uses the style and settings the app was " +
                        "last showing, reacts to whatever MusicViz is playing, and drifts gently on its " +
                        "own the rest of the time. It draws nothing while another app is in front, so it " +
                        "is not a background battery drain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CrystalButton(onClick = {
                    // The direct "change to THIS wallpaper" screen; some
                    // launchers do not implement it, so fall back to the
                    // system's live-wallpaper list rather than doing nothing.
                    val direct =
                        android.content.Intent(
                            android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER,
                        ).putExtra(
                            android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            android.content.ComponentName(
                                ctx,
                                dev.musicviz.wallpaper.VisualizerWallpaperService::class.java,
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
        }
        item {
            SettingsSection("Export & About") {
                CrystalButton(onClick = onOpenExport) { Text("Export video…") }
                AboutSection()
            }
        }
    }
}

/**
 * "Auto visuals": the two modes that change the look by themselves while a
 * track plays - Random, which jumps to something new, and the visual playlist,
 * which walks the looks the user hearted in Visuals › Presets.
 *
 * Settings rather than the Visuals hub. The hub's five tabs all manipulate the
 * visual that is on screen right now; these decide how the app CHOOSES looks
 * over time, which is standing behaviour of the same kind as "Visuals &
 * Analysis" above and the live wallpaper below, and it is where a user goes
 * looking for a rule rather than for a picture.
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
private fun AutoVisualsSettings(viewModel: PlayerViewModel) {
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
                "it starts.",
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
            valueRange = 5f..300f,
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
            valueRange = 5f..300f,
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

/**
 * "Live input & touch": drive the visuals from the microphone with nothing
 * playing, and push them around with a finger.
 *
 * The two belong together because they are the same idea - the visualizer
 * responding to the room it is in rather than to a file - and because the
 * second is what you reach for the moment the first is on and there is no
 * transport to touch.
 *
 * The permission is requested at the moment the switch is used, never at
 * launch, and a denial is reported in place rather than leaving a switch that
 * silently springs back.
 */
@Composable
private fun LiveInputSettings(viewModel: PlayerViewModel) {
    val gui by viewModel.guiPrefs.collectAsState()
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
        val external = rememberExternalDisplay()
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
