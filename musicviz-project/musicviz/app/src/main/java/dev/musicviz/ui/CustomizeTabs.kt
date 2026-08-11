package dev.musicviz.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.musicviz.analysis.IntelligenceMode
import dev.musicviz.render.BlendMode
import dev.musicviz.render.EnvBand
import dev.musicviz.render.LfoConfig
import dev.musicviz.render.LfoTarget
import dev.musicviz.render.LfoWave
import dev.musicviz.render.scene.CymaticsMath
import dev.musicviz.render.scene.HyperspaceMath
import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.SceneParams
import dev.musicviz.render.scene.VisualStyleCatalog

/*
 * The scene customization panel's TABS. They are hosted by
 * `VisualsHub.CustomizePanel` (Visuals > Customize), which owns the tab row,
 * the Randomize button and the lock set.
 *
 * The tabs themselves are `render.scene.CustomizeTab` - the hub builds its tab
 * row from that enum and `ParamRandomizer` keys its roll by the same entries,
 * so "Randomize <tab>" rolls exactly the controls the tab below it renders.
 *
 * Tabs group controls the way a VJ thinks about them: Motion (how it moves),
 * Shape (geometry and distortion), Behavior (audio reactivity), Color
 * (palettes and grading), FX (screen effects, settings fade, ADSR envelopes
 * and the assignable LFO automations), Fluid (the fluid family plus the
 * all-styles FlowField / water-ripple overlays) and GLSL (raw shader editing
 * on shader scenes). Sections inside each tab carry small headers so long
 * lists stay scannable. All changes apply live.
 */

/**
 * Lock set + toggle for the "Randomize unlocked" button, provided by the
 * hosting screen. Keys are the CONTROL LABEL strings rendered by
 * [LabeledSlider] / [LabeledIntSlider] / [CheckRow] / [LockableChipLabel] -
 * see `ParamRandomizer`, whose keys must match them verbatim.
 */
val LocalParamLocks =
    androidx.compose.runtime.compositionLocalOf<Pair<Set<String>, (String) -> Unit>> { emptySet<String>() to {} }

/**
 * Group heading inside a Customize tab, in the shell's own section language
 * (gem marker, tracked caps, luminous hairline) instead of the bare divider +
 * title it used to be - so a tab reads as a short list of named groups rather
 * than as one undifferentiated wall of sliders.
 */
@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CrystalGem(MaterialTheme.colorScheme.primary, size = 5.dp)
        Text(
            title.uppercase(),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
            color = accentTextColor(),
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).luminousHairline(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
}

/**
 * Explanatory paragraph under a section header. One helper so every hint in
 * the panel is styled and coloured the same way, and so hints read as
 * secondary to the controls they describe rather than competing with them.
 */
@Composable
private fun ControlHint(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The lock affordance shared by every control in the panel: a chip that holds
 * this control against "Randomize unlocked".
 *
 * [label] IS the lock key - `ParamRandomizer`'s keys are control-label strings
 * - so this exists once rather than once per control shape. It used to be
 * copy-pasted into `LabeledSlider`, `LabeledIntSlider` and
 * `LockableChipLabel`: three places that had to keep agreeing about what a
 * lock looks like and, more importantly, about what it keys on.
 *
 * A label the randomizer never rolls renders NO chip at all: locks exist for
 * "Randomize unlocked" alone, so a chip on Fade time or an ADSR/LFO card
 * slider persisted a key nothing honoured - a lock that guarded nothing.
 * `ParamRandomizer.LOCKABLE_LABELS` is the single source of truth (derived
 * from the keys its roll actually uses), so coverage can't drift from here.
 */
@Composable
private fun LockChip(label: String) {
    if (label !in ParamRandomizer.LOCKABLE_LABELS) return
    val (locked, toggle) = LocalParamLocks.current
    val on = label in locked
    val text = if (on) "\uD83D\uDD12 locked" else "lock"
    // 48dp is the Android floor for touch targets, and this panel is worked
    // mid-performance where a missed tap lands on a neighbouring control - but
    // a 48dp pill in every label row would triple the height of the control
    // stack. So the row is told only the drawn text's size, and the clickable
    // square overflows it, centred: full-sized target, unchanged layout. The
    // square is a sibling PLACED BEFORE each control's own slider in the
    // column, so the slider still wins hit testing where the two overlap.
    Layout(
        content = {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (on) accentTextColor() else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                // The overlay below is this chip's one accessible node.
                modifier = Modifier.clearAndSetSemantics {},
            )
            Box(
                modifier =
                    Modifier
                        .semantics { contentDescription = if (on) "$label locked" else "Lock $label" }
                        .clickable(onClickLabel = if (on) "unlock" else "lock") { toggle(label) },
            )
        },
        modifier = Modifier.padding(start = 8.dp),
    ) { measurables, constraints ->
        val pill = measurables[0].measure(constraints)
        val minPx = 48.dp.roundToPx()
        val touch = measurables[1].measure(Constraints.fixed(maxOf(pill.width, minPx), maxOf(pill.height, minPx)))
        layout(pill.width, pill.height) {
            pill.place(0, 0)
            touch.place((pill.width - touch.width) / 2, (pill.height - touch.height) / 2)
        }
    }
}

/** Label row shared by the labelled controls: name on the left, lock right. */
@Composable
private fun ControlLabelRow(
    text: String,
    lockKey: String,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, style = MaterialTheme.typography.labelSmall)
        LockChip(lockKey)
    }
}

/** How many library transition chips are laid out at once (see BehaviorTab). */
private const val TRANSITION_CHIP_LIMIT = 40

@Composable
internal fun MotionTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
) {
    Column {
        SectionHeader("Movement")
        LabeledSlider("Speed", p.speed, 0.05f..4f) { onChange(p.copy(speed = it)) }
        // The same honesty the Behavior tab's Turbulence/Density hints give:
        // this tab is handed no scene predicate to gate on, so the hint names
        // the styles that ignore the control instead of hiding it.
        ControlHint(
            "The scene clock. Beam draws the signal and MilkDrop presets pace " +
                "themselves, so those two ignore it; every other style moves " +
                "at this speed.",
        )
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
        ControlHint(
            "A dive that never arrives: the shader looks, MilkDrop and the " +
                "particle styles ride it, with Dive speed setting the rate. " +
                "Fluid, Water, Cymatics, Beam and Hyperspace ignore it.",
        )
        CheckRow("Endless zoom", p.endlessZoom) { onChange(p.copy(endlessZoom = it)) }
        if (p.endlessZoom) {
            LabeledSlider("Dive speed", p.endlessZoomSpeed, 0.05f..1.2f) { onChange(p.copy(endlessZoomSpeed = it)) }
        }
    }
}

/**
 * Geometry and distortion. Everything in Distortion / Symmetry / Stylize
 * except Morph reaches every style through the composite pass (uPostWarp,
 * uPostRipple, uPostTwist, uPostKaleido, uPostSymmetry, uPostTile,
 * uPostPixelate, uPostPosterize); [isShaderLookScene] gates the one control
 * that does not - see `VisualsHub.isShaderLookSceneId`.
 *
 * The Particles section is different: nothing in it has a composite mirror, so
 * it takes a gate of its own - [isPointSpriteScene]
 * (`VisualsHub.isPointSpriteSceneId`), the styles that draw a particle sprite
 * at all: the `ParticleSceneBase` family plus FLUID and CURLFLOW, whose GPU
 * layer now shades through the same `lib_particle_shade.glsl`. The header rides
 * the same gate, so it disappears with its controls instead of leaving an
 * empty "Particles" heading on shader / MilkDrop / Water styles.
 *
 * [particleLayerOff] is not a gate: it is true only on FLUID with the point
 * layer switched off in the Fluid tab, a state the user can undo, so the
 * slider stays put and says why it is idle rather than vanishing.
 */
@Composable
internal fun ShapeTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
    isShaderLookScene: Boolean,
    isPointSpriteScene: Boolean,
    particleLayerOff: Boolean = false,
    isBeamScene: Boolean = false,
) {
    Column {
        if (isBeamScene) {
            SectionHeader("Beam")
            ControlHint(
                "The trace is drawn as a real beam: brightness comes from how " +
                    "long the beam dwells, so it glows where the signal turns " +
                    "and dims through fast sweeps. Trail length sets the " +
                    "phosphor afterglow.",
            )
            CheckRow("XY plot", p.beamXy) { onChange(p.copy(beamXy = it)) }
            LabeledSlider("Beam width", p.beamWidth, 0.2f..4f) { onChange(p.copy(beamWidth = it)) }
            LabeledSlider("Beam brightness", p.beamIntensity, 0f..3f) { onChange(p.copy(beamIntensity = it)) }
            LabeledSlider("Beam tail", p.beamTail, 0f..1f) { onChange(p.copy(beamTail = it)) }
        }
        SectionHeader("Distortion")
        LabeledSlider("Domain warp", p.warp, 0f..1f) { onChange(p.copy(warp = it)) }
        LabeledSlider("Ripple", p.ripple, 0f..1f) { onChange(p.copy(ripple = it)) }
        if (isShaderLookScene) {
            // `morph` only exists as ShaderScene's uMorph (the polar-blend in
            // each scene fragment shader). The composite has no morph stage,
            // so on particles / MilkDrop / the fluid family this slider moved
            // nothing at all - hidden rather than shown-and-dead.
            LabeledSlider("Morph", p.morph, 0f..1f) { onChange(p.copy(morph = it)) }
        }
        LabeledSlider("Twist", p.twist, -1f..1f) { onChange(p.copy(twist = it)) }
        SectionHeader("Symmetry & tiling")
        // Turning the toggle on repairs a fold count below 2, which no chip
        // below can produce and every gate treats as "off": a preset saved
        // while the two disagreed would otherwise keep the checkbox ticked and
        // the effect switched off, with nothing on screen to say why.
        CheckRow("Kaleidoscope", p.kaleidoscope) { on ->
            val folds = if (on && p.symmetry < 2) SceneParams.DEFAULT_SYMMETRY_FOLDS else p.symmetry
            onChange(p.copy(kaleidoscope = on, symmetry = folds))
        }
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
        if (isPointSpriteScene) {
            SectionHeader("Particles")
            // uShape reaches both families: ParticleSceneBase.draw uploads it
            // for the CPU styles, FluidParticles.draw for the fluid layer, and
            // lib_particle_common.glsl's ptShapeField is the single reader.
            LockableChipLabel("Particle shape")
            ChipRow(SceneParams.PARTICLE_SHAPES, p.particleShape) { onChange(p.copy(particleShape = it)) }
            LabeledSlider("Particle size", p.particleSize, 0.3f..2.5f) { onChange(p.copy(particleSize = it)) }
            if (particleLayerOff) {
                ControlHint(
                    "The fluid particle layer is off (Fluid tab), so size has no " +
                        "sprites to scale until you switch it back on.",
                )
            }
        }
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
    transitionId: String? = null,
    transitionDurationSec: Float = 0.8f,
    onTransitionId: (String) -> Unit = {},
    onTransitionDuration: (Float) -> Unit = {},
) {
    Column {
        if (transitionId != null) {
            SectionHeader("Scene transition")
            val ctx = androidx.compose.ui.platform.LocalContext.current
            // Parsed once and remembered: this is a ~150 KB asset read, and it
            // must not happen on a recomposition.
            val library = remember { dev.musicviz.render.TransitionCatalog.library(ctx) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dev.musicviz.render.TransitionCatalog.BUILT_IN_IDS.forEach { id ->
                    FilterChip(
                        selected = transitionId == id,
                        onClick = { onTransitionId(id) },
                        label = { Text(id) },
                    )
                }
            }
            if (library.isNotEmpty()) {
                ControlHint(
                    "${library.size} more from the gl-transitions library. Each one blends the " +
                        "outgoing and incoming scenes with the full FX chain already applied, " +
                        "so nothing pops off for the length of a switch.",
                )
                var query by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search transitions", style = MaterialTheme.typography.labelSmall) },
                )
                val shown =
                    remember(query, library) {
                        if (query.isBlank()) library else library.filter { it.name.contains(query, ignoreCase = true) }
                    }
                // A plain wrapped row rather than a lazy list: this sits inside
                // the Customize tab's own vertical scroll, and nesting a
                // scrollable in a scrollable is what makes a list refuse to
                // fling. Capped so a blank query does not lay out 123 chips.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    shown.take(TRANSITION_CHIP_LIMIT).forEach { def ->
                        FilterChip(
                            selected = transitionId == def.name,
                            onClick = { onTransitionId(def.name) },
                            label = { Text(def.name, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                if (shown.size > TRANSITION_CHIP_LIMIT) {
                    ControlHint("${shown.size - TRANSITION_CHIP_LIMIT} more - narrow the search to reach them.")
                }
            }
            Text("Duration ${"%.1f".format(transitionDurationSec)}s", style = MaterialTheme.typography.labelMedium)
            // Must match setTransitionDuration's 0.3-5 s clamp: below 0.3 the
            // thumb snapped back mid-drag, and 3-5 s was unreachable.
            CrystalSlider(value = transitionDurationSec, onValueChange = onTransitionDuration, valueRange = 0.3f..5f)
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
        // Neither of these has a composite mirror, and this tab is handed no
        // scene predicate to gate on - so they say which styles read them, the
        // way "Trails (particle scenes)" below and ColorTab's "MilkDrop
        // palette tint" already do. A hint rather than a renamed label on
        // purpose: lock chips and `ParamRandomizer` keys are both the label
        // string, so renaming one silently drops every lock a user has saved
        // under the old name.
        LabeledSlider("Turbulence", p.turbulence, 0f..1.5f) { onChange(p.copy(turbulence = it)) }
        ControlHint(
            "A force inside the scene, not a screen effect: the shader styles, " +
                "the particle styles and Curl Flow read it. MilkDrop, Fluid, " +
                "Water, Cymatics, Beam and Hyperspace run their own and ignore it.",
        )
        LabeledSlider("Density", p.density, 0.1f..1f) { onChange(p.copy(density = it)) }
        ControlHint("Thins the population: the particle styles and Fluid's dye. Nothing else reads it.")
        CheckRow("Mirror", p.mirror) { onChange(p.copy(mirror = it)) }
        CheckRow("Trails (particle scenes)", p.trails) { onChange(p.copy(trails = it)) }
        if (p.trails) {
            LabeledSlider("Trail length", p.trailLength, 0.05f..0.98f) { onChange(p.copy(trailLength = it)) }
            LabeledSlider("Trail zoom (echo in/out)", p.trailZoom, -0.5f..0.5f) { onChange(p.copy(trailZoom = it)) }
            LabeledSlider("Trail warp (liquid echo)", p.trailWarp, 0f..1f) { onChange(p.copy(trailWarp = it)) }
        }
        SectionHeader("Reactivity envelope")
        // "Reactivity attack/decay", not the bare words: lock keys are label
        // strings, and the FX tab's ADSR cards render their own attack/decay
        // sliders - the shared label made one lock chip mark two unrelated
        // live controls. Same fix as "Depth" -> "LFO depth". Neither of these
        // is a `ParamRandomizer` key, so no roll changes hands with the rename.
        LabeledSlider("Reactivity attack", attack, 0.05f..1f) { onReactivityChange(it, decay) }
        LabeledSlider("Reactivity decay", decay, 0.02f..0.6f) { onReactivityChange(attack, it) }
        SectionHeader("Scene intelligence")
        ChipRow(IntelligenceMode.entries.map { it.name.lowercase() }, IntelligenceMode.entries.indexOf(intelligenceMode)) {
            onIntelligenceModeChange(IntelligenceMode.entries[it])
        }
    }
}

/**
 * Palettes and grading. The grade, hue and effect rows all ride the composite
 * pass, so they work on every style; the second palette slot and Duotone are
 * ShaderScene-only and are gated by [isShaderLookScene] - see
 * `VisualsHub.isShaderLookSceneId`.
 *
 * "MilkDrop palette tint" is the reverse case: a control that only the
 * MILKDROP style reads, shown everywhere with the style in its label, because
 * this tab is handed no MilkDrop predicate to gate on.
 */
@Composable
internal fun ColorTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
    isShaderLookScene: Boolean,
    onTakeArtworkPalette: (() -> Unit)? = null,
    artworkNote: String? = null,
) {
    val palettes = rememberSavedPalettes()
    Column {
        SectionHeader("Palettes")
        if (onTakeArtworkPalette != null) {
            // Sits above the palette chips because it is a way of CHOOSING one,
            // not a separate effect: it writes the same base/span override the
            // gradient maker below does, so the result is an ordinary custom
            // palette the user can then edit or save.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CrystalButton(compact = true, filled = false, onClick = onTakeArtworkPalette) {
                    Text("Take the colours from the artwork")
                }
            }
            artworkNote?.let { ControlHint(it) }
        }
        LockableChipLabel("Palette")
        PaletteSlotSelector(p, onChange, palettes)
        if (isShaderLookScene) {
            // Cyclic scientific colour maps. Offered only where they are read
            // (ShaderScene uploads uPalLut), and as a separate row rather than
            // more entries in the palette chips above, because they are not
            // the same KIND of thing: the built-ins are a hue and a span the
            // user can move, these are measured ramps that are either on or
            // off.
            LockableChipLabel("Colour map")
            ControlHint(
                "Perceptually even, and cyclic - the two ends join, so a wrap " +
                    "has no seam. A hue ramp swings in lightness instead, " +
                    "which paints bands into smooth fields that the music " +
                    "never played.",
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FilterChip(
                    selected = p.paletteLut < 0,
                    onClick = { onChange(p.copy(paletteLut = SceneParams.NO_PALETTE_LUT)) },
                    label = { Text("off", style = MaterialTheme.typography.labelSmall) },
                )
                SceneParams.CYCLIC_PALETTES.forEachIndexed { index, name ->
                    FilterChip(
                        selected = p.paletteLut == index,
                        onClick = { onChange(p.copy(paletteLut = index)) },
                        label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
        if (isShaderLookScene) {
            // Slot 2 and the blend are one control, not two: only ShaderScene
            // uploads uPal2Base/uPal2Range and mixes them with uPaletteMix.
            // Showing the blend without the slot would leave a slider with
            // nothing to blend, so they are hidden together.
            LabeledSlider("Palette blend", p.paletteMix, 0f..1f) { onChange(p.copy(paletteMix = it)) }
            if (p.paletteMix > 0.001f) {
                LockableChipLabel("Palette 2")
                PaletteSlotSelector(p, onChange, palettes, second = true)
            }
        }
        // MilkDrop is the one style that authors its own colours, so the
        // palette reaches it as an opt-in blend rather than as the colour
        // itself (ProjectMScene's post pass, uPalTint). The label names the
        // style - as "Trails (particle scenes)" and "Glow (fluid)" do -
        // because ColorTab has no MilkDrop predicate to gate on.
        ControlHint(
            "MilkDrop presets paint their own colours. This steers them toward the " +
                "palette above (0 = the preset untouched); every other style renders " +
                "the palette directly and ignores it.",
        )
        LabeledSlider("MilkDrop palette tint", p.milkdropPaletteTint, 0f..1f) {
            onChange(p.copy(milkdropPaletteTint = it))
        }
        SectionHeader("Gradient & palette maker")
        PaletteMakerCard(p, onChange, palettes)
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
        if (isShaderLookScene) {
            // uDuotone recolours the shader's own luminance through pal();
            // the composite (uPostSolarize / uPostInvert and friends) has no
            // duotone stage, so the toggle is inert on every other style.
            CheckRow("Duotone", p.duotone) { onChange(p.copy(duotone = it)) }
        }
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
        ControlHint(
            "Changes to sliders and preset loads glide to their new values over this time.",
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
        LayersSection()
        SectionHeader("Envelopes (ADSR)")
        ControlHint(
            "Two beat-triggered envelopes. Each can drive SEVERAL parameters " +
                "(or an LFO's rate/depth) at once - tap + to add targets, tap a " +
                "target chip to remove it.",
        )
        for (i in 0 until dev.musicviz.render.AdsrEngine.COUNT) {
            AdsrCard(
                index = i,
                config = adsr.getOrElse(i) { dev.musicviz.render.AdsrConfig() },
                onChange = { onAdsrChange(i, it) },
            )
        }
        SectionHeader("LFO automations")
        ControlHint(
            "Assign an oscillator to any parameter. LFO 1 can drive LFO 2/3's " +
                "rate or depth for chained motion. Rates can lock to the detected BPM.",
        )
        for (i in 0 until 3) {
            LfoCard(index = i, config = lfos.getOrElse(i) { LfoConfig() }, onChange = { onLfoChange(i, it) })
        }
    }
}

/**
 * FX -> Layers: a SECOND style rendered under the active one every frame,
 * combined by one of the [BlendMode] functions at the chosen mix.
 *
 * The layer fields are renderer state, not [SceneParams] entries (see
 * `VisualizerRenderer.layerSceneId` for why), so this section does not ride
 * the `p`/`onChange` path the rest of the panel uses: it reads and writes
 * [LayersBus], and the shell-level engine bindings (`EnginePlumbing`) push the
 * bus to the renderer from wherever the user goes next. That is also why none
 * of these controls carries a lock chip - lock keys exist for `ParamRandomizer`
 * rolls, and the randomizer only rolls [SceneParams] (the same shape as the
 * Behavior tab's transition Duration slider).
 */
@Composable
private fun LayersSection() {
    val layers by LayersBus.state.collectAsState()
    val layerScenes by LayersBus.availableScenes.collectAsState()
    val activeScene by LayersBus.activeSceneId.collectAsState()
    SectionHeader("Layers (second style)")
    ControlHint(
        "Renders a second style every frame and blends it under the active " +
            "one - a whole extra scene, so it costs frames. Screen only: a " +
            "video export carries the active style alone.",
    )
    CheckRow("Layers enabled", layers.enabled) { on ->
        // First enable with nothing picked starts on the first style that is
        // not the active one, so the switch visibly does something instead of
        // arming a layer with no scene behind it.
        val scene = layers.sceneId ?: layerScenes.firstOrNull { it != activeScene }
        LayersBus.state.value = layers.copy(enabled = on, sceneId = scene)
    }
    if (!layers.enabled) return
    var showLayerPicker by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { showLayerPicker = true }) {
            Text("Layer style: ${layers.sceneId?.let { sceneDisplayLabel(it) } ?: "none"}")
        }
        DropdownMenu(expanded = showLayerPicker, onDismissRequest = { showLayerPicker = false }) {
            // Minus the active scene: the renderer ignores a layer naming it
            // (a style blended with itself is just that style at a different
            // exposure), so offering it would be a picker entry that does
            // nothing.
            layerScenes.filter { it != activeScene }.forEach { id ->
                DropdownMenuItem(
                    text = { Text(sceneDisplayLabel(id)) },
                    onClick = {
                        LayersBus.state.value = layers.copy(sceneId = id)
                        showLayerPicker = false
                    },
                )
            }
        }
    }
    if (layers.sceneId != null && layers.sceneId == activeScene) {
        // The pick was valid when it was made; the ACTIVE style moved onto it
        // afterwards. Said rather than auto-corrected: silently reassigning
        // the layer would change the look for a reason the user cannot see.
        ControlHint("That style is now the active one, so the layer is idle - pick another.")
    }
    Text("Blend", style = MaterialTheme.typography.labelSmall)
    ChipRow(BlendMode.entries.map { it.name.lowercase() }, BlendMode.entries.indexOf(layers.blend)) {
        LayersBus.state.value = layers.copy(blend = BlendMode.entries[it])
    }
    Text("Layer mix ${"%.2f".format(layers.mix)}", style = MaterialTheme.typography.labelMedium)
    // 0..1 is exactly what the renderer accepts: VisualSafety.layerMix coerces
    // into that range before its ADD/DIFFERENCE clamping, so the whole slider
    // is live and nothing beyond it would be.
    CrystalSlider(
        value = layers.mix,
        onValueChange = { LayersBus.state.value = layers.copy(mix = it) },
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth(),
    )
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
            // "LFO depth", not "Depth": lock keys are label strings and the
            // Water section's "Depth" (waterDepth) IS a randomizer key, so the
            // bare label made locking this modulation slider silently freeze
            // the water depth roll as well.
            LabeledSlider("LFO depth", config.depth, 0f..1f) { onChange(config.copy(depth = it)) }
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
    // False renders the chips visibly inert instead of hiding them - for a
    // selector another control currently owns (the Act chips outside Hold),
    // where the choice should stay visible but not pretend to be live.
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selectedIndex,
                enabled = enabled,
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
    Column(Modifier.padding(vertical = 2.dp)) {
        ControlLabelRow("$label ${"%.2f".format(value)}", label)
        CrystalSlider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * Header for a chip selector, carrying the same lock chip [LabeledSlider]
 * renders. [label] IS the lock key (see `ParamRandomizer`, whose keys are
 * control-label strings), so a chip-driven param - Palette, Palette 2,
 * Particle shape, Beat pattern, Path - can be held against "Randomize
 * unlocked" like every slider and checkbox. Chip rows used to render a plain
 * [Text] (or no label at all), which made those five the only rolled params a
 * user could not protect: every roll re-picked the palette they had chosen.
 */
@Composable
private fun LockableChipLabel(label: String) = ControlLabelRow(label, label)

/**
 * Checkbox + label, carrying the same [LockChip] every other control shape
 * renders. Fifteen randomizer keys are CheckRow labels (Endless zoom, XY
 * plot, Kaleidoscope, Mirror, the fluid toggles...), and this row used to be
 * the one lockable shape with no lock affordance at all - the panel claimed
 * lock coverage the UI could not deliver, and every roll flipped toggles the
 * user had no way to hold. The chip rides the row's right edge; the Checkbox
 * already gives the row its 48dp touch height, and the chip's own overflowing
 * 48dp square (see [LockChip]) stays inside it, so nothing grows. Labels the
 * randomizer never rolls render no chip, exactly as sliders behave.
 */
@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        LockChip(label)
    }
}

/**
 * Customize -> Fluid tab (F5). Sections are gated by what the ACTIVE style
 * actually reads, not by style name, because the fluid styles overlap only
 * partially:
 * - [isFluidScene]: FLUID alone - the Navier-Stokes solver, the ink/dye and
 *   look passes, and the force/dye injection shader editors (the section-13
 *   extension points).
 * - [isEmitterScene]: FLUID + WATER - the shared FluidEmitters splat
 *   schedule and the FluidQuality tiers. WaterScene reuses both verbatim
 *   (see WaterScene.applyQualityTier and its "Emitter schedule reused
 *   verbatim" block), so hiding these on Water left it running on whatever
 *   values the params happened to hold.
 * - [isJourneyScene]: FLUID + CURLFLOW + WATER - the shared spawn/catch
 *   progression. Every control in it has a WaterScene reader
 *   (`WaterScene.kt` choreography/emitters block); `fluidParticleLife` did
 *   not, so it moved down to the particle-layer section.
 * - [isParticleLayerScene]: FLUID + CURLFLOW - the shared FluidParticles
 *   lifecycle layer, i.e. the styles that read `fluidParticleDrag` and
 *   `fluidParticleLife`. CurlFlow IS that layer, so hiding the drag slider
 *   behind the FLUID-only section (and behind `fluidParticlesEnabled`, which
 *   CurlFlow never reads) made a control the style genuinely consumes
 *   unreachable.
 * - [isWaterScene]: WATER alone - the heightfield surface. Wave speed and
 *   damping are the exception inside it: they are ALSO the all-styles ripple
 *   overlay's physics, so the overlay section renders them for every other
 *   style (`!isWaterScene`) rather than leaving that overlay uncontrollable.
 * For every other style only the FlowField / water-ripple overlay sections
 * appear - the same tab is the one home for "fluid principles" regardless
 * of style, mirroring how the GLSL tab scopes to shader scenes.
 */
@Composable
internal fun FluidTab(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
    isFluidScene: Boolean,
    isJourneyScene: Boolean = isFluidScene,
    isWaterScene: Boolean = false,
    isEmitterScene: Boolean = isFluidScene,
    isParticleLayerScene: Boolean = isFluidScene,
    injectionError: String? = null,
    onApplyInjectionShaders: (String?, String?) -> Unit = { _, _ -> },
) {
    Column {
        if (isWaterScene) {
            SectionHeader("Water")
            ControlHint(
                "Heightfield water: beats drop expanding rings, stirrers " +
                    "carve wakes, the journey decides where they land.",
            )
            // Wave speed and Damping are WaterScene's own wave physics
            // (WaterScene.kt "sim.waveSpeed / sim.damping") AND the shared
            // ripple overlay's, so they are shown here on WATER and in the
            // overlay section on every other style - one param, one place per
            // style, never two live copies of the same slider.
            LabeledSlider("Wave speed", p.waterWaveSpeed, 0.2f..2f) { onChange(p.copy(waterWaveSpeed = it)) }
            LabeledSlider("Damping", p.waterDamping, 0.9f..0.999f) { onChange(p.copy(waterDamping = it)) }
            LabeledSlider("Ripple strength", p.waterRippleStrength, 0f..2f) { onChange(p.copy(waterRippleStrength = it)) }
            LabeledSlider("Depth", p.waterDepth, 0f..1f) { onChange(p.copy(waterDepth = it)) }
            LabeledSlider("Specular", p.waterSpecular, 0f..1f) { onChange(p.copy(waterSpecular = it)) }
            LabeledSlider("Flow drift", p.waterFlow, 0f..1f) { onChange(p.copy(waterFlow = it)) }
            SectionHeader("Liquid")
            ControlHint(
                "Every splash stains a colour film that the surface then carries: it runs " +
                    "down the flanks of the ripples, spirals through the stirrer wakes and " +
                    "drains into the catch points, refracted by the same waves that move it. " +
                    "Turn it down for a plain depth-graded pool.",
            )
            LabeledSlider("Liquid", p.waterLiquid, 0f..1f) { onChange(p.copy(waterLiquid = it)) }
            if (p.waterLiquid > 0.001f) {
                LabeledSlider("Liquid flow", p.waterLiquidFlow, 0f..4f) { onChange(p.copy(waterLiquidFlow = it)) }
                LabeledSlider("Liquid fade", p.waterLiquidFade, 0f..2f) { onChange(p.copy(waterLiquidFade = it)) }
            }
        }
        if (isJourneyScene) {
            SectionHeader("Journey (spawn & catch progression)")
            ControlHint(
                "Spawn points birth particles and dye; catch points pull them " +
                    "in and recycle them. Both travel through the track: song " +
                    "progress reshapes the layout, sections re-seat it, beats " +
                    "advance the bloom.",
            )
            LockableChipLabel("Path")
            ChipRow(SceneParams.FLUID_PATHS, p.fluidSpawnPath.coerceIn(0, SceneParams.FLUID_PATHS.size - 1)) {
                onChange(p.copy(fluidSpawnPath = it))
            }
            LabeledIntSlider("Spawn points", p.fluidSpawnPoints, 1..8) { onChange(p.copy(fluidSpawnPoints = it)) }
            LabeledSlider("Progression", p.fluidSpawnProgress, 0f..1f) { onChange(p.copy(fluidSpawnProgress = it)) }
            LabeledIntSlider("Catch points", p.fluidCatchPoints, 0..4) { onChange(p.copy(fluidCatchPoints = it)) }
            if (p.fluidCatchPoints > 0) {
                LabeledSlider("Catch pull", p.fluidCatchPull, 0f..3f) { onChange(p.copy(fluidCatchPull = it)) }
                LabeledSlider("Catch radius", p.fluidCatchRadius, 0.03f..0.3f) { onChange(p.copy(fluidCatchRadius = it)) }
            }
        }
        if (isEmitterScene) {
            // Quality tiers: FluidScene sizes its sim/dye/particle buffers
            // from them, WaterScene its ripple grid (WaterScene.gridResFor).
            SectionHeader("Quality")
            ChipRow(
                dev.musicviz.render.fluid.FluidQuality.LABELS,
                p.fluidQuality.coerceIn(0, dev.musicviz.render.fluid.FluidQuality.LABELS.size - 1),
            ) { onChange(p.copy(fluidQuality = it)) }
            CheckRow("Auto quality (downgrade on sustained low FPS)", p.fluidAutoQuality) {
                onChange(p.copy(fluidAutoQuality = it))
            }
            if (isFluidScene) {
                // Pressure-projection sweeps: WATER's wave equation has no
                // pressure solve, so the knob would do nothing there.
                LabeledIntSlider("Solver iterations", p.fluidIterations, 8..40) { onChange(p.copy(fluidIterations = it)) }
            }
        }
        if (isFluidScene) {
            SectionHeader("Character")
            LabeledSlider("Fluid curl", p.fluidCurl, 0f..50f) { onChange(p.copy(fluidCurl = it)) }
            LabeledSlider("Motion fade", p.fluidVelocityDissipation, 0f..4f) { onChange(p.copy(fluidVelocityDissipation = it)) }
            LabeledSlider("Fluid fade", p.fluidDensityDissipation, 0f..4f) { onChange(p.copy(fluidDensityDissipation = it)) }
            LabeledSlider("Chromatic aging", p.fluidChromaticAging, 0f..1f) { onChange(p.copy(fluidChromaticAging = it)) }
            LabeledSlider("Pressure", p.fluidPressure, 0f..1f) { onChange(p.copy(fluidPressure = it)) }
        }
        if (isEmitterScene) {
            // The splat schedule is shared: FluidScene injects the splats as
            // velocity/dye, WaterScene converts each one into a drop.
            SectionHeader("Emitters")
            LockableChipLabel("Beat pattern")
            ChipRow(SceneParams.FLUID_PATTERNS, p.fluidBeatPattern.coerceIn(0, 3)) {
                onChange(p.copy(fluidBeatPattern = it))
            }
            LabeledIntSlider("Beat splats", p.fluidBeatSplats, 0..8) { onChange(p.copy(fluidBeatSplats = it)) }
            LabeledIntSlider("Stirrers", p.fluidStirrers, 0..4) { onChange(p.copy(fluidStirrers = it)) }
            LabeledSlider("Stirrer speed", p.fluidStirrerSpeed, 0f..2f) { onChange(p.copy(fluidStirrerSpeed = it)) }
            LabeledSlider("Fluid splat radius", p.fluidSplatRadius, 0.02f..0.4f) { onChange(p.copy(fluidSplatRadius = it)) }
            // Lives with the radius it modulates; both feed FluidEmitters,
            // which WATER drives too (emitters.radiusPulse).
            LabeledSlider("Radius on beat", p.fluidRadiusPulse, 0f..1f) { onChange(p.copy(fluidRadiusPulse = it)) }
            LabeledSlider("Fluid splat force", p.fluidSplatForce, 0f..3f) { onChange(p.copy(fluidSplatForce = it)) }
            CheckRow("Bass pump", p.fluidBassPump) { onChange(p.copy(fluidBassPump = it)) }
            CheckRow("Treble sparkle", p.fluidSparkle) { onChange(p.copy(fluidSparkle = it)) }
            if (isFluidScene) {
                // Tints the dye a splat carries; WATER discards splat colour
                // (it keeps only position/radius/velocity), so FLUID only.
                LabeledSlider("Palette cycle", p.fluidPaletteCycleSpeed, 0f..2f) { onChange(p.copy(fluidPaletteCycleSpeed = it)) }
            }
        }
        if (isParticleLayerScene) {
            SectionHeader("Particles")
            if (isFluidScene) {
                // The on/off switch is FluidScene's - CURLFLOW *is* the
                // particle layer, so there is nothing to switch off there
                // (it never reads fluidParticlesEnabled).
                CheckRow("Particle layer", p.fluidParticlesEnabled) { onChange(p.copy(fluidParticlesEnabled = it)) }
            }
            // Drag and life are read wherever the lifecycle layer actually
            // steps: FluidScene behind its toggle, CurlFlowScene
            // unconditionally - the two are set on consecutive lines in both
            // (FluidScene "particles.drag/life", CurlFlowScene the same). Life
            // used to sit in the Journey section, which also covers WATER, and
            // WaterScene has no particle layer to age at all, so the slider
            // read nothing there.
            if (!isFluidScene || p.fluidParticlesEnabled) {
                LabeledSlider("Particle drag", p.fluidParticleDrag, 0.02f..1f) { onChange(p.copy(fluidParticleDrag = it)) }
                LabeledSlider("Particle life (s)", p.fluidParticleLife, 1f..20f) { onChange(p.copy(fluidParticleLife = it)) }
            }
            if (isFluidScene && p.fluidParticlesEnabled) {
                // CURLFLOW colours its points from the beat pulse and the
                // composite exposure instead, so this one stays FLUID-only.
                LabeledSlider("Particle brightness", p.fluidParticleBrightness, 0f..2f) {
                    onChange(p.copy(fluidParticleBrightness = it))
                }
            }
        }
        if (isFluidScene) {
            CheckRow("Ink layer", p.fluidDyeEnabled) { onChange(p.copy(fluidDyeEnabled = it)) }
            SectionHeader("Look")
            CheckRow("Shading (embossed ink)", p.fluidShading) { onChange(p.copy(fluidShading = it)) }
            // "Glow (fluid)" is the style's internal HDR bloom - a different
            // knob from the composite Bloom in the Color/FX tabs.
            CheckRow("Glow (fluid)", p.fluidBloom) { onChange(p.copy(fluidBloom = it)) }
            if (p.fluidBloom) {
                LabeledSlider("Fluid glow", p.fluidBloomIntensity, 0.1f..2f) { onChange(p.copy(fluidBloomIntensity = it)) }
                LabeledSlider("Glow threshold", p.fluidBloomThreshold, 0f..1f) { onChange(p.copy(fluidBloomThreshold = it)) }
            }
            CheckRow("Sunrays", p.fluidSunrays) { onChange(p.copy(fluidSunrays = it)) }
            if (p.fluidSunrays) {
                LabeledSlider("Sunrays weight", p.fluidSunraysWeight, 0.3f..1f) { onChange(p.copy(fluidSunraysWeight = it)) }
            }
            SectionHeader("Audio routing")
            LabeledSlider("Curl from mids", p.fluidCurlAudio, 0f..1f) { onChange(p.copy(fluidCurlAudio = it)) }
            LabeledSlider("Glow from loudness", p.fluidBloomAudio, 0f..1f) { onChange(p.copy(fluidBloomAudio = it)) }
            LabeledSlider("Fade when quiet", p.fluidFadeAudio, 0f..1f) { onChange(p.copy(fluidFadeAudio = it)) }
        }
        SectionHeader("FlowField (all styles)")
        ControlHint(
            "A shared fluid velocity field that bends ANY style: fluidWarp " +
                "distorts the composite output (particles, shaders, MilkDrop - " +
                "and exports), particle scenes can ride the field, and shader " +
                "scenes see it as uFlow.",
        )
        CheckRow("FlowField enabled", p.flowEnabled) { onChange(p.copy(flowEnabled = it)) }
        if (p.flowEnabled) {
            LabeledSlider("Flow strength", p.flowStrength, 0f..1f) { onChange(p.copy(flowStrength = it)) }
            LabeledSlider("Flow force", p.flowForce, 0f..3f) { onChange(p.copy(flowForce = it)) }
            LabeledSlider("Flow curl", p.flowCurl, 0f..50f) { onChange(p.copy(flowCurl = it)) }
            CheckRow("Particles ride the field", p.flowAdvectParticles) { onChange(p.copy(flowAdvectParticles = it)) }
        }
        SectionHeader("Water ripples (all styles)")
        ControlHint(
            "The water heightfield rides on top of ANY style: beats drop " +
                "rings that refract the image (particles, shaders, MilkDrop - " +
                "and exports), treble sprinkles small drops, and glint adds a " +
                "specular sparkle on the crests. Speed and damping are the same " +
                "wave physics the water style runs - one pair of sliders drives " +
                "both. The water style's own surface already refracts, so the " +
                "overlay stays off there.",
        )
        CheckRow("Water ripples enabled", p.rippleOverlayEnabled) { onChange(p.copy(rippleOverlayEnabled = it)) }
        if (p.rippleOverlayEnabled) {
            if (!isWaterScene) {
                // The overlay's heightfield runs on waterWaveSpeed /
                // waterDamping exactly as the WATER style's own surface does
                // (VisualizerRenderer "ripple.waveSpeed = 1.2f * ...", and
                // VideoExporter's mirror of it), so these two are the overlay's
                // wave physics on every style - not Water-only params. They sat
                // in the WATER-only section, which left the rings on plasma /
                // MilkDrop / particles propagating at whatever the params
                // happened to hold, with no reachable control (the randomizer
                // still rolls both). On WATER they appear once, up in the Water
                // section, where they are that style's own physics.
                LabeledSlider("Wave speed", p.waterWaveSpeed, 0.2f..2f) { onChange(p.copy(waterWaveSpeed = it)) }
                LabeledSlider("Damping", p.waterDamping, 0.9f..0.999f) { onChange(p.copy(waterDamping = it)) }
            }
            // NOT "Ripple strength": that is the Water section's own slider
            // (waterRippleStrength, 0..2). Lock keys are label strings, so
            // sharing the label made one lock chip freeze both params and one
            // roll write both - two different controls behind one switch.
            LabeledSlider("Ripple overlay strength", p.rippleOverlayStrength, 0f..1f) {
                onChange(p.copy(rippleOverlayStrength = it))
            }
            LabeledSlider("Ripple glint", p.rippleOverlaySpecular, 0f..1f) { onChange(p.copy(rippleOverlaySpecular = it)) }
        }
        if (isFluidScene) {
            SectionHeader("Injection shaders (advanced)")
            ControlHint(
                "The force and dye injection passes are user-replaceable GLSL. " +
                    "Both start from the built-in capsule splat (uMode 0 = " +
                    "velocity, 1 = dye); a failed compile keeps the last good " +
                    "program. Extra uniforms: uDt, uDx, uTime, uBass, uMid, " +
                    "uTreble, uEnergy, uBeat. Clear the text to restore built-ins.",
            )
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val template =
                remember {
                    runCatching {
                        ctx.resources
                            .openRawResource(dev.musicviz.R.raw.fluid_splat_frag)
                            .bufferedReader()
                            .use { it.readText() }
                    }.getOrDefault("")
                }
            var forceSrc by remember { mutableStateOf(template) }
            var dyeSrc by remember { mutableStateOf(template) }
            var editorsUsed by remember { mutableStateOf(false) }
            Text("Force shader", style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(
                value = forceSrc,
                onValueChange = {
                    forceSrc = it
                    editorsUsed = true
                },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Text("Dye shader", style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(
                value = dyeSrc,
                onValueChange = {
                    dyeSrc = it
                    editorsUsed = true
                },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            if (injectionError != null) {
                Text(injectionError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    // Unedited/blank editors mean "use the built-in pass".
                    val f = forceSrc.takeIf { editorsUsed && it.isNotBlank() && it != template }
                    val d = dyeSrc.takeIf { editorsUsed && it.isNotBlank() && it != template }
                    onApplyInjectionShaders(f, d)
                }) { Text("Apply shaders") }
                TextButton(onClick = {
                    forceSrc = template
                    dyeSrc = template
                    onApplyInjectionShaders(null, null)
                }) { Text("Reset to built-in") }
            }
        }
    }
}

/**
 * Customize -> Cymatics tab: the standing-wave field of the sound.
 *
 * Shown only while that style is active (`VisualsHub.isCymaticsSceneId`),
 * unlike the Fluid tab: every control here is read by exactly one scene, and a
 * whole tab of sliders that move nothing is worse than a tab that comes and
 * goes with the style it belongs to.
 *
 * Three groups, and they answer three different questions. WAVE is what the
 * field hears - which geometry it rings in, what pitch answers the coarsest
 * figure ("Fundamental"), how many standing waves are superposed, how long
 * they ring, and whether the field follows raw band energy or the spectral
 * peaks ("Tonal focus", see [CymaticsMath]). FIELD is how much of it is on
 * screen and how fast it moves. LOOK is how it is drawn: from bare nodal
 * filigree on dark cells to a fully filled iridescent surface.
 */
@Composable
internal fun CymaticsTab(
    p: SceneParams,
    // The active style's scene id, when the host provides it (null keeps the
    // pre-plumbing behavior at every existing call site). Eight of the eleven
    // Cymatics substyles pin the geometry via the catalog's geometryOverride,
    // and on those the Geometry chips were pure no-ops - so with the id in
    // hand the pin is SAID instead of rendered dead. Sits before the callback
    // so trailing-lambda call shapes keep compiling.
    activeSceneId: String? = null,
    onChange: (SceneParams) -> Unit,
) {
    Column {
        SectionHeader("Wave")
        ControlHint(
            "The standing-wave field of whatever is playing - or of the mic, " +
                "on live input - drawn fullscreen. A pure tone gives one clean " +
                "symmetric figure, a chord the superposition of its notes'.",
        )
        // Resolved through the catalog's id lookup so an unknown or
        // mid-rework id (or an override index the geometry list has outgrown)
        // degrades to the chips rather than to a wrong hint.
        val forcedGeometry =
            activeSceneId
                ?.let { VisualStyleCatalog.cymatics(it)?.geometryOverride }
                ?.let { SceneParams.CYMATICS_GEOMETRIES.getOrNull(it) }
        if (forcedGeometry != null) {
            ControlHint("Geometry set by this style: $forcedGeometry.")
        } else {
            LockableChipLabel("Geometry")
            ChipRow(
                SceneParams.CYMATICS_GEOMETRIES,
                p.cymaticsGeometry,
            ) { onChange(p.copy(cymaticsGeometry = it)) }
        }
        LabeledSlider(
            "Fundamental (Hz)",
            p.cymaticsFundamental,
            CymaticsMath.MIN_FUNDAMENTAL_HZ..CymaticsMath.MAX_FUNDAMENTAL_HZ,
        ) { onChange(p.copy(cymaticsFundamental = it)) }
        LabeledIntSlider("Standing waves", p.cymaticsModes, 1..CymaticsMath.MAX_RENDERED_MODES) {
            onChange(p.copy(cymaticsModes = it))
        }
        LabeledSlider("Tonal focus", p.cymaticsFocus, 0f..1f) { onChange(p.copy(cymaticsFocus = it)) }
        LabeledSlider("Plate ring", p.cymaticsRing, 0f..1f) { onChange(p.copy(cymaticsRing = it)) }

        SectionHeader("Field")
        ControlHint("How much of the wave field fills the screen, and how it moves through it.")
        LabeledSlider("Field scale", p.cymaticsScale, 0.5f..8f) { onChange(p.copy(cymaticsScale = it)) }
        LabeledSlider("Wave flow", p.cymaticsFlow, 0f..1f) { onChange(p.copy(cymaticsFlow = it)) }
        LabeledSlider("Field swirl", p.cymaticsSwirl, -1f..1f) { onChange(p.copy(cymaticsSwirl = it)) }

        SectionHeader("Look")
        ControlHint("Fill runs from bare nodal filigree on dark cells to a fully filled iridescent surface.")
        LabeledSlider("Fill", p.cymaticsFill, 0f..1f) { onChange(p.copy(cymaticsFill = it)) }
        LabeledSlider("Nodal lines", p.cymaticsLine, 0f..2f) { onChange(p.copy(cymaticsLine = it)) }
        LabeledSlider("Nodal glow", p.cymaticsGlow, 0f..2f) { onChange(p.copy(cymaticsGlow = it)) }
        LabeledSlider("Iridescence", p.cymaticsIridescence, 0f..1f) { onChange(p.copy(cymaticsIridescence = it)) }
        LabeledSlider("Caustic sheen", p.cymaticsCaustic, 0f..1.5f) { onChange(p.copy(cymaticsCaustic = it)) }
    }
}

/**
 * Customize -> Hyperspace tab: a room of living 3D fractals.
 *
 * Shown only while that style is active (`VisualsHub.isHyperspaceSceneId`),
 * for the same reason the Cymatics tab is: every control here is read by
 * exactly one scene.
 *
 * Four groups answering four questions. JOURNEY is the story - whether the
 * music chooses the act, one act is held, or the five cycle on a timer. LIFE
 * is what the bodies are and how they behave: how many, which fractal, how
 * long each lives, how fast each turns on its own axis and drifts on its own
 * orbit. LOOK is how they are drawn. QUALITY is the one control that costs
 * frames, kept apart so it reads as the performance setting it is.
 */
@Composable
internal fun HyperspaceTab(
    p: SceneParams,
    // The active style's scene id, when the host provides it (null keeps the
    // pre-plumbing behavior at every existing call site). Five of the eleven
    // Hyperspace substyles force the species via the catalog's forcedSpecies,
    // and on those the Fractal chips were pure no-ops - so with the id in
    // hand the pin is SAID instead of rendered dead. Sits before the callback
    // so trailing-lambda call shapes keep compiling.
    activeSceneId: String? = null,
    onChange: (SceneParams) -> Unit,
) {
    Column {
        SectionHeader("Journey")
        ControlHint(
            "Five acts, walked over a track: Threshold, Chrysanthemum, Magic eye, " +
                "Waiting room, Breakthrough. On Music, loud passages take it deeper " +
                "and quiet ones bring it back; Hold pins one act, Cycle walks them on " +
                "a timer.",
        )
        LockableChipLabel("Journey")
        ChipRow(
            SceneParams.HYPERSPACE_JOURNEYS,
            p.hyperJourney,
        ) { onChange(p.copy(hyperJourney = it)) }
        LockableChipLabel("Act")
        // Only Hold reads hyperAct (Music follows the track's energy, Cycle
        // always opens on the first act and walks from there), so outside
        // Hold the chips are shown inert instead of pretending to be live.
        ChipRow(
            HyperspaceMath.ACT_NAMES,
            p.hyperAct,
            enabled = p.hyperJourney == HyperspaceMath.JOURNEY_HOLD,
        ) { onChange(p.copy(hyperAct = it)) }
        if (p.hyperJourney != HyperspaceMath.JOURNEY_HOLD) {
            ControlHint("Act is live on Hold only - Music and Cycle choose the act themselves.")
        }
        LabeledSlider("Act length (s)", p.hyperCycleSeconds, 5f..180f) {
            onChange(p.copy(hyperCycleSeconds = it))
        }

        SectionHeader("Life")
        ControlHint(
            "Every body is its own fractal on its own clock - it buds in on a hit, " +
                "turns on its own axis, drifts on its own orbit, and dissolves. " +
                "Mixed gives a room of all six at once.",
        )
        // Same shape as CymaticsTab's forced geometry: resolved through the
        // catalog's id lookup, so an unknown or mid-rework id (or a species
        // index the list has outgrown) degrades to the chips.
        val forcedSpecies =
            activeSceneId
                ?.let { VisualStyleCatalog.hyperspace(it)?.forcedSpecies }
                ?.let { SceneParams.HYPERSPACE_SPECIES.getOrNull(it) }
        if (forcedSpecies != null) {
            ControlHint("Fractal set by this style: $forcedSpecies.")
        } else {
            LockableChipLabel("Fractal")
            ChipRow(
                SceneParams.HYPERSPACE_SPECIES,
                p.hyperSpecies,
            ) { onChange(p.copy(hyperSpecies = it)) }
        }
        LabeledSlider("Bodies", p.hyperBodies, 0.2f..2f) { onChange(p.copy(hyperBodies = it)) }
        LabeledSlider("Body life (s)", p.hyperLifetime, 3f..45f) { onChange(p.copy(hyperLifetime = it)) }
        LabeledSlider("Body spin", p.hyperSpin, 0f..3f) { onChange(p.copy(hyperSpin = it)) }
        LabeledSlider("Orbit drift", p.hyperOrbit, 0f..3f) { onChange(p.copy(hyperOrbit = it)) }
        LabeledSlider("Camera drift", p.hyperCamera, 0f..3f) { onChange(p.copy(hyperCamera = it)) }
        LabeledSlider("Fold", p.hyperFold, 0f..1f) { onChange(p.copy(hyperFold = it)) }

        SectionHeader("Look")
        ControlHint("Filigree is the fabric behind everything; Colour banding is the nested shells within a body.")
        LabeledSlider("Body glow", p.hyperGlow, 0f..2f) { onChange(p.copy(hyperGlow = it)) }
        LabeledSlider("Neon rim", p.hyperNeon, 0f..2f) { onChange(p.copy(hyperNeon = it)) }
        LabeledSlider("Filigree", p.hyperField, 0f..2f) { onChange(p.copy(hyperField = it)) }
        LabeledSlider("Haze", p.hyperHaze, 0f..2f) { onChange(p.copy(hyperHaze = it)) }
        LabeledSlider("Colour banding", p.hyperTrap, 0f..1.5f) { onChange(p.copy(hyperTrap = it)) }
        LabeledIntSlider("Mirror folds", p.hyperMirrorFolds, 2..16) {
            onChange(p.copy(hyperMirrorFolds = it))
        }

        // The melt is HYPERSPACE's medium, and this is the only place it can
        // be reached: every field below is `hyper*` and HyperspaceScene is the
        // only reader. The group spent two commits inside FluidTab's emitter
        // block, which renders on FLUID and WATER - so the sliders showed up on
        // the two styles that ignore them and never on the one they move.
        SectionHeader("Melt")
        ControlHint(
            "A fluid simulation runs underneath the fractals. The bodies stir it " +
                "as they drift, the music and your finger stir it, and it stirs " +
                "them back - Melt is how far it can pull the geometry out of " +
                "shape. Drag on the visualizer to mold it by hand.",
        )
        LabeledSlider("Melt", p.hyperMelt, 0f..2f) { onChange(p.copy(hyperMelt = it)) }
        LabeledSlider("Ink stain", p.hyperStain, 0f..1.5f) { onChange(p.copy(hyperStain = it)) }
        LabeledSlider("Liquid light", p.hyperLiquid, 0f..1.5f) { onChange(p.copy(hyperLiquid = it)) }
        LabeledSlider("Ridges", p.hyperRidges, 0f..1f) { onChange(p.copy(hyperRidges = it)) }
        LabeledSlider("Stir", p.hyperStir, 0f..3f) { onChange(p.copy(hyperStir = it)) }
        LabeledSlider("Vorticity", p.hyperSwirl, 0f..50f) { onChange(p.copy(hyperSwirl = it)) }
        LabeledSlider("Flow fade", p.hyperFlowFade, 0f..4f) { onChange(p.copy(hyperFlowFade = it)) }

        SectionHeader("Quality")
        ControlHint(
            "How many march steps and fractal iterations each pixel gets. " +
                "This is the frame-rate control: turn it down on a slower phone, " +
                "up for finer detail. Melt costs frames too - it adds two texture " +
                "reads to every step and makes the ray take smaller ones.",
        )
        LabeledSlider("Detail", p.hyperDetail, 0.25f..1.5f) { onChange(p.copy(hyperDetail = it)) }
    }
}

@Composable
private fun LabeledIntSlider(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        ControlLabelRow("$label $value", label)
        CrystalSlider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
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
        // Two groups, because the card asks two different questions. Shape is
        // the envelope's own curve plus how far its output swings; Sustain
        // gate is what decides when the envelope is ALLOWED to hold. Eleven
        // controls in one undivided column is the wall of sliders the tab
        // headers exist to prevent, so the card borrows the same idea a size
        // down.
        CardSubHeader("Shape")
        // "Env attack/decay": these used to be plain "Attack"/"Decay" and
        // collided with the Behavior tab's reactivity envelope, which is a
        // different live control with a different range. Lock keys are label
        // strings, so the collision rendered a lock chip on a slider the user
        // never touched (harmless beyond that - neither label is a randomizer
        // key). The two cards still share these keys with each other, exactly
        // as Sustain/Release/Amount do; that is one group, one meaning.
        LabeledSlider("Env attack", config.attack, 0.005f..1f) { onChange(config.copy(attack = it)) }
        LabeledSlider("Env decay", config.decay, 0.01f..1.5f) { onChange(config.copy(decay = it)) }
        LabeledSlider("Sustain", config.sustain, 0f..1f) { onChange(config.copy(sustain = it)) }
        LabeledSlider("Release", config.release, 0.02f..2f) { onChange(config.copy(release = it)) }
        LabeledSlider("Amount", config.amount, 0f..1.5f) { onChange(config.copy(amount = it)) }
        // The other half of the design decision the engine already implements:
        // the beat triggers the attack, but BAND ENERGY holds the sustain.
        // AdsrEngine has read all four of these since the envelopes landed and
        // no control ever wrote them, so every envelope in the app was gated on
        // bass above 0.25 with a fixed sustain and retrigger on - the one
        // combination the defaults happen to spell.
        CardSubHeader("Sustain gate")
        ControlHint(
            "The envelope holds while the chosen band stays above the gate and " +
                "releases when it drops below - so the same beat can open a long " +
                "swell in a loud chorus and a short blip in a quiet verse.",
        )
        LockableChipLabel("Gate band")
        ChipRow(EnvBand.entries.map { it.label }, EnvBand.entries.indexOf(config.band)) {
            onChange(config.copy(band = EnvBand.entries[it]))
        }
        // 0.05 is the engine's own floor for this value (it divides by the
        // threshold when the sustain tracks energy and clamps the divisor
        // there); 1 is a full-scale band, i.e. "only the very loudest moments
        // hold this envelope open". Bands can read up to 1.5, but a gate a real
        // signal can never cross is a broken envelope, not a setting.
        LabeledSlider("Gate level", config.gateThreshold, 0.05f..1f) {
            onChange(config.copy(gateThreshold = it))
        }
        CheckRow("Sustain follows band energy", config.sustainTrack) { onChange(config.copy(sustainTrack = it)) }
        CheckRow("Retrigger on every beat", config.retrigger) { onChange(config.copy(retrigger = it)) }
    }
}

/**
 * Group heading INSIDE a card, one step quieter than [SectionHeader]. The
 * tab-level header - gem, luminous hairline, tracked caps - would give a group
 * within a single envelope the same visual weight as "Envelopes (ADSR)" itself
 * and read as a new section of the tab rather than as part of the card above
 * it, so cards that outgrow a single list get this instead.
 */
@Composable
private fun CardSubHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = accentTextColor(),
    )
}
