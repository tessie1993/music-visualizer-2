package dev.musicviz.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.musicviz.analysis.IntelligenceMode
import dev.musicviz.render.LfoConfig
import dev.musicviz.render.LfoTarget
import dev.musicviz.render.LfoWave
import dev.musicviz.render.scene.SceneParams

/**
 * Full-screen scene customization panel. Six tabs group controls the way a
 * VJ thinks about them: Motion (how it moves), Shape (geometry and
 * distortion), Behavior (audio reactivity), Color (palettes and grading),
 * FX (screen effects, settings fade, and the assignable LFO automations),
 * and GLSL (raw shader editing on shader scenes). Sections inside each tab
 * carry small headers so long lists stay scannable. All changes apply live.
 */
val LocalParamLocks =
    androidx.compose.runtime.compositionLocalOf<Pair<Set<String>, (String) -> Unit>> { emptySet<String>() to {} }

@Composable
fun CustomizeDialog(
    params: SceneParams,
    onParamsChange: (SceneParams) -> Unit,
    lockedParams: Set<String> = emptySet(),
    onToggleLock: (String) -> Unit = {},
    onRandomize: () -> Unit = {},
    adsr: List<dev.musicviz.render.AdsrConfig> = emptyList(),
    onAdsrChange: (Int, dev.musicviz.render.AdsrConfig) -> Unit = { _, _ -> },
    attack: Float,
    decay: Float,
    onReactivityChange: (Float, Float) -> Unit,
    intelligenceMode: IntelligenceMode,
    onIntelligenceModeChange: (IntelligenceMode) -> Unit,
    lfos: List<LfoConfig>,
    onLfoChange: (Int, LfoConfig) -> Unit,
    shaderSource: String?,
    shaderError: String?,
    onApplyShader: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var source by remember(shaderSource) { mutableStateOf(shaderSource.orEmpty()) }
    val hasAdvanced = shaderSource != null
    val tabs =
        if (hasAdvanced) {
            listOf("Motion", "Shape", "Behavior", "Color", "FX", "GLSL")
        } else {
            listOf("Motion", "Shape", "Behavior", "Color", "FX")
        }
    val glslIndex = 5
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(0.96f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Customize scene",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onParamsChange(SceneParams.DEFAULT) }) { Text("Reset") }
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
                ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    tabs.forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 6.dp),
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalParamLocks provides (lockedParams to onToggleLock),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onRandomize) { Text("Randomize (skips locked)") }
                        }
                        when (tab) {
                            0 -> MotionTab(params, onParamsChange)
                            1 -> ShapeTab(params, onParamsChange)
                            2 ->
                                BehaviorTab(
                                    params,
                                    onParamsChange,
                                    attack,
                                    decay,
                                    onReactivityChange,
                                    intelligenceMode,
                                    onIntelligenceModeChange,
                                )
                            3 -> ColorTab(params, onParamsChange)
                            4 -> FxTab(params, onParamsChange, lfos, onLfoChange, adsr, onAdsrChange)
                            else -> {
                                OutlinedTextField(
                                    value = source,
                                    onValueChange = { source = it },
                                    modifier = Modifier.fillMaxWidth().height(360.dp),
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                )
                                if (shaderError != null) {
                                    Text(
                                        text = shaderError,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                                TextButton(onClick = { onApplyShader(source) }) { Text("Apply shader") }
                            }
                        }
                    }
                }
            }
        }
    }
    // Suppress the unused warning when GLSL tab is hidden.
    LaunchedEffect(hasAdvanced) {
        // Writing state directly during composition is illegal; reset the tab
        // as an effect when the GLSL tab disappears (scene switched to one
        // without an editable shader).
        if (tab >= glslIndex && !hasAdvanced) tab = 0
    }
}

@Composable
private fun SectionHeader(title: String) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun MotionTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
) {
    Column {
        SectionHeader("Movement")
        LabeledSlider("Speed", p.speed, 0.05f..4f) { onChange(p.copy(speed = it)) }
        LabeledSlider("Zoom", p.zoom, 0.3f..3f) { onChange(p.copy(zoom = it)) }
        LabeledSlider("Rotation", p.rotation, -3f..3f) { onChange(p.copy(rotation = it)) }
        LabeledSlider("Sway", p.sway, 0f..1f) { onChange(p.copy(sway = it)) }
        SectionHeader("Drift")
        LabeledSlider("Drift X", p.driftX, -1f..1f) { onChange(p.copy(driftX = it)) }
        LabeledSlider("Drift Y", p.driftY, -1f..1f) { onChange(p.copy(driftY = it)) }
        SectionHeader("Beat motion")
        LabeledSlider("Beat pulse", p.pulse, 0f..1f) { onChange(p.copy(pulse = it)) }
        LabeledSlider("Beat shake", p.shake, 0f..1f) { onChange(p.copy(shake = it)) }
        SectionHeader("Endless zoom")
        CheckRow("Endless zoom", p.endlessZoom) { onChange(p.copy(endlessZoom = it)) }
        if (p.endlessZoom) {
            LabeledSlider("Dive speed", p.endlessZoomSpeed, 0.05f..1.2f) { onChange(p.copy(endlessZoomSpeed = it)) }
        }
    }
}

@Composable
internal fun ShapeTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
) {
    Column {
        SectionHeader("Distortion")
        LabeledSlider("Domain warp", p.warp, 0f..1f) { onChange(p.copy(warp = it)) }
        LabeledSlider("Ripple", p.ripple, 0f..1f) { onChange(p.copy(ripple = it)) }
        LabeledSlider("Morph", p.morph, 0f..1f) { onChange(p.copy(morph = it)) }
        LabeledSlider("Twist", p.twist, -1f..1f) { onChange(p.copy(twist = it)) }
        SectionHeader("Symmetry & tiling")
        CheckRow("Kaleidoscope", p.kaleidoscope) { onChange(p.copy(kaleidoscope = it)) }
        if (p.kaleidoscope) {
            Text("Folds", style = MaterialTheme.typography.labelSmall)
            ChipRow(
                SceneParams.SYMMETRY_FOLDS.filter { it >= 2 }.map { "$it" },
                selectedIndex = SceneParams.SYMMETRY_FOLDS.filter { it >= 2 }.indexOf(p.symmetry),
            ) { idx -> onChange(p.copy(symmetry = SceneParams.SYMMETRY_FOLDS.filter { it >= 2 }[idx])) }
        }
        LabeledSlider("Tile", p.tile, 1f..6f) { onChange(p.copy(tile = it)) }
        SectionHeader("Stylize")
        LabeledSlider("Pixelate", p.pixelate, 0f..1f) { onChange(p.copy(pixelate = it)) }
        LabeledSlider("Posterize", p.posterize, 0f..1f) { onChange(p.copy(posterize = it)) }
        SectionHeader("Particles")
        ChipRow(SceneParams.PARTICLE_SHAPES, p.particleShape) { onChange(p.copy(particleShape = it)) }
        LabeledSlider("Particle size", p.particleSize, 0.3f..2.5f) { onChange(p.copy(particleSize = it)) }
    }
}

@Composable
internal fun BehaviorTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
    attack: Float,
    decay: Float,
    onReactivityChange: (Float, Float) -> Unit,
    intelligenceMode: IntelligenceMode,
    onIntelligenceModeChange: (IntelligenceMode) -> Unit,
    transitionStyle: dev.musicviz.render.TransitionStyle? = null,
    transitionDurationSec: Float = 0.8f,
    onTransitionStyle: (dev.musicviz.render.TransitionStyle) -> Unit = {},
    onTransitionDuration: (Float) -> Unit = {},
) {
    Column {
        if (transitionStyle != null) {
            SectionHeader("Scene transition")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dev.musicviz.render.TransitionStyle.entries.forEach { t ->
                    FilterChip(
                        selected = transitionStyle == t,
                        onClick = { onTransitionStyle(t) },
                        label = { Text(t.name.lowercase()) },
                    )
                }
            }
            Text("Duration ${"%.1f".format(transitionDurationSec)}s", style = MaterialTheme.typography.labelMedium)
            Slider(value = transitionDurationSec, onValueChange = onTransitionDuration, valueRange = 0.1f..3f)
        }
        SectionHeader("Audio response")
        LabeledSlider("Audio drive", p.audioDrive, 0.2f..2.5f) { onChange(p.copy(audioDrive = it)) }
        LabeledSlider("Beat response", p.beatResponse, 0f..2f) { onChange(p.copy(beatResponse = it)) }
        LabeledSlider("Beat flash", p.flash, 0f..1f) { onChange(p.copy(flash = it)) }
        SectionHeader("Band balance")
        LabeledSlider("Bass gain", p.bassGain, 0f..2f) { onChange(p.copy(bassGain = it)) }
        LabeledSlider("Mid gain", p.midGain, 0f..2f) { onChange(p.copy(midGain = it)) }
        LabeledSlider("Treble gain", p.trebGain, 0f..2f) { onChange(p.copy(trebGain = it)) }
        SectionHeader("Texture & motion")
        LabeledSlider("Turbulence", p.turbulence, 0f..1.5f) { onChange(p.copy(turbulence = it)) }
        LabeledSlider("Density", p.density, 0.1f..1f) { onChange(p.copy(density = it)) }
        CheckRow("Mirror", p.mirror) { onChange(p.copy(mirror = it)) }
        CheckRow("Trails (particle scenes)", p.trails) { onChange(p.copy(trails = it)) }
        if (p.trails) {
            LabeledSlider("Trail length", p.trailLength, 0.05f..0.98f) { onChange(p.copy(trailLength = it)) }
        }
        SectionHeader("Reactivity envelope")
        LabeledSlider("Attack", attack, 0.05f..1f) { onReactivityChange(it, decay) }
        LabeledSlider("Decay", decay, 0.02f..0.6f) { onReactivityChange(attack, it) }
        SectionHeader("Scene intelligence")
        ChipRow(IntelligenceMode.entries.map { it.name.lowercase() }, IntelligenceMode.entries.indexOf(intelligenceMode)) {
            onIntelligenceModeChange(IntelligenceMode.entries[it])
        }
    }
}

@Composable
internal fun ColorTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
) {
    Column {
        SectionHeader("Palettes")
        ChipRow(SceneParams.PALETTES.map { it.first }, p.palette) { onChange(p.copy(palette = it)) }
        LabeledSlider("Palette blend", p.paletteMix, 0f..1f) { onChange(p.copy(paletteMix = it)) }
        if (p.paletteMix > 0.001f) {
            Text("Second palette", style = MaterialTheme.typography.labelSmall)
            ChipRow(SceneParams.PALETTES.map { it.first }, p.palette2) { onChange(p.copy(palette2 = it)) }
        }
        SectionHeader("Hue")
        LabeledSlider("Hue shift", p.colorShift, 0f..1f) { onChange(p.copy(colorShift = it)) }
        LabeledSlider("Hue range", p.hueRange, 0f..1.5f) { onChange(p.copy(hueRange = it)) }
        CheckRow("Color cycle", p.colorCycle) { onChange(p.copy(colorCycle = it)) }
        if (p.colorCycle) {
            LabeledSlider("Cycle speed", p.cycleSpeed, 0.02f..0.6f) { onChange(p.copy(cycleSpeed = it)) }
        }
        SectionHeader("Grading")
        LabeledSlider("Saturation", p.saturation, 0f..1.5f) { onChange(p.copy(saturation = it)) }
        LabeledSlider("Brightness", p.brightness, 0.2f..2f) { onChange(p.copy(brightness = it)) }
        LabeledSlider("Contrast", p.contrast, 0.3f..2.5f) { onChange(p.copy(contrast = it)) }
        LabeledSlider("Gamma", p.gamma, 0.3f..2.5f) { onChange(p.copy(gamma = it)) }
        LabeledSlider("Intensity", p.intensity, 0.2f..2f) { onChange(p.copy(intensity = it)) }
        LabeledSlider("Temperature", p.temperature, -1f..1f) { onChange(p.copy(temperature = it)) }
        SectionHeader("Effects")
        LabeledSlider("Bloom", p.bloom, 0f..1f) { onChange(p.copy(bloom = it)) }
        CheckRow("Duotone", p.duotone) { onChange(p.copy(duotone = it)) }
        CheckRow("Solarize", p.solarize) { onChange(p.copy(solarize = it)) }
        CheckRow("Invert", p.invert) { onChange(p.copy(invert = it)) }
    }
}

@Composable
internal fun FxTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
    lfos: List<LfoConfig>,
    onLfoChange: (Int, LfoConfig) -> Unit,
    adsr: List<dev.musicviz.render.AdsrConfig> = emptyList(),
    onAdsrChange: (Int, dev.musicviz.render.AdsrConfig) -> Unit = { _, _ -> },
) {
    Column {
        SectionHeader("Settings fade (automation)")
        Text(
            "Changes to sliders and preset loads glide to their new values over this time.",
            style = MaterialTheme.typography.labelSmall,
        )
        LabeledSlider("Fade time (s)", p.paramFadeSec, 0f..5f) { onChange(p.copy(paramFadeSec = it)) }
        SectionHeader("Screen FX")
        LabeledSlider("Chromatic aberration", p.chromaAb, 0f..1f) { onChange(p.copy(chromaAb = it)) }
        LabeledSlider("Vignette", p.vignette, 0f..1f) { onChange(p.copy(vignette = it)) }
        LabeledSlider("Scanlines", p.scanlines, 0f..1f) { onChange(p.copy(scanlines = it)) }
        LabeledSlider("Film grain", p.grain, 0f..1f) { onChange(p.copy(grain = it)) }
        LabeledSlider("Glitch", p.glitch, 0f..1f) { onChange(p.copy(glitch = it)) }
        LabeledSlider("Fisheye", p.fisheye, -1f..1f) { onChange(p.copy(fisheye = it)) }
        LabeledSlider("Strobe", p.strobe, 0f..1f) { onChange(p.copy(strobe = it)) }
        SectionHeader("Envelopes (ADSR)")
        Text(
            "Two beat-triggered envelopes. Each can drive SEVERAL parameters " +
                "(or an LFO's rate/depth) at once - tap + to add targets, tap a " +
                "target chip to remove it.",
            style = MaterialTheme.typography.labelSmall,
        )
        for (i in 0 until dev.musicviz.render.AdsrEngine.COUNT) {
            AdsrCard(
                index = i,
                config = adsr.getOrElse(i) { dev.musicviz.render.AdsrConfig() },
                onChange = { onAdsrChange(i, it) },
            )
        }
        SectionHeader("LFO automations")
        Text(
            "Assign an oscillator to any parameter. LFO 1 can drive LFO 2/3's " +
                "rate or depth for chained motion. Rates can lock to the detected BPM.",
            style = MaterialTheme.typography.labelSmall,
        )
        for (i in 0 until 3) {
            LfoCard(index = i, config = lfos.getOrElse(i) { LfoConfig() }, onChange = { onLfoChange(i, it) })
        }
    }
}

@Composable
private fun LfoCard(
    index: Int,
    config: LfoConfig,
    onChange: (LfoConfig) -> Unit,
) {
    var showTargetPicker by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LFO ${index + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = config.enabled, onCheckedChange = { onChange(config.copy(enabled = it)) })
        }
        if (config.enabled) {
            TextButton(onClick = { showTargetPicker = true }) {
                Text("Target: ${config.target.label}")
            }
            ChipRow(LfoWave.entries.map { it.label }, LfoWave.entries.indexOf(config.wave)) {
                onChange(config.copy(wave = LfoWave.entries[it]))
            }
            CheckRow("Sync to BPM", config.beatSync) { onChange(config.copy(beatSync = it)) }
            if (config.beatSync) {
                val divs = listOf(0.25f, 0.5f, 1f, 2f, 4f, 8f)
                val labels = listOf("1/16", "1/8", "1 beat", "2", "4", "8")
                ChipRow(labels, divs.indexOf(config.beatDiv).coerceAtLeast(0)) {
                    onChange(config.copy(beatDiv = divs[it]))
                }
            } else {
                LabeledSlider("Rate (Hz)", config.rateHz, 0.02f..8f) { onChange(config.copy(rateHz = it)) }
            }
            LabeledSlider("Depth", config.depth, 0f..1f) { onChange(config.copy(depth = it)) }
        }
    }
    if (showTargetPicker) {
        AlertDialog(
            onDismissRequest = { showTargetPicker = false },
            title = { Text("LFO ${index + 1} target") },
            text = {
                Column(modifier = Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                    LfoTarget.entries.forEach { t ->
                        // Chain targets only make sense pointing forward.
                        val isChain = t.name.startsWith("LFO")
                        val valid =
                            !isChain ||
                                (t.name.startsWith("LFO2") && index < 1) ||
                                (t.name.startsWith("LFO3") && index < 2)
                        if (valid) {
                            TextButton(onClick = {
                                onChange(config.copy(target = t))
                                showTargetPicker = false
                            }) {
                                Text(if (t == config.target) "\u25b6 ${t.label}" else t.label)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTargetPicker = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun ChipRow(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val (locked, toggle) = LocalParamLocks.current
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$label ${"%.2f".format(value)}", style = MaterialTheme.typography.labelSmall)
            Text(
                if (label in locked) "locked" else "lock",
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (label in locked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
                modifier = Modifier.clickable { toggle(label) },
            )
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AdsrCard(
    index: Int,
    config: dev.musicviz.render.AdsrConfig,
    onChange: (dev.musicviz.render.AdsrConfig) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        CheckRow("Envelope ${index + 1} on", config.enabled) { onChange(config.copy(enabled = it)) }
        if (!config.enabled) return@Column
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Targets:", style = MaterialTheme.typography.labelSmall)
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            config.targets.forEach { t ->
                AssistChip(
                    onClick = { onChange(config.copy(targets = config.targets - t)) },
                    label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
            Box {
                AssistChip(
                    onClick = { showAdd = true },
                    label = { Text("+", style = MaterialTheme.typography.labelSmall) },
                )
                DropdownMenu(expanded = showAdd, onDismissRequest = { showAdd = false }) {
                    dev.musicviz.render.LfoTarget.entries
                        .filter { it != dev.musicviz.render.LfoTarget.NONE && it !in config.targets }
                        .forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.label) },
                                onClick = {
                                    onChange(config.copy(targets = config.targets + t))
                                    showAdd = false
                                },
                            )
                        }
                }
            }
        }
        LabeledSlider("Attack", config.attack, 0.005f..1f) { onChange(config.copy(attack = it)) }
        LabeledSlider("Decay", config.decay, 0.01f..1.5f) { onChange(config.copy(decay = it)) }
        LabeledSlider("Sustain", config.sustain, 0f..1f) { onChange(config.copy(sustain = it)) }
        LabeledSlider("Release", config.release, 0.02f..2f) { onChange(config.copy(release = it)) }
        LabeledSlider("Amount", config.amount, 0f..1.5f) { onChange(config.copy(amount = it)) }
    }
}
