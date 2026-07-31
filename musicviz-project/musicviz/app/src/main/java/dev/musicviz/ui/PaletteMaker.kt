package dev.musicviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.musicviz.render.scene.SceneParams

/*
 * Customize -> Color: the gradient maker and the saved-palette library.
 *
 * Saved palettes are appended to the same chip row as the built-ins, so
 * picking one is the same gesture as picking "Neon". Selecting a built-in
 * clears the slot's override (back to SceneParams.UNSET_OVERRIDE) and
 * selecting a saved palette writes base/span into the override fields, which
 * every scene family already reads through paletteBase/paletteRange.
 */

/** Observable snapshot of [PaletteStore] so the chip rows refresh after a save or delete. */
@Stable
internal class SavedPalettes(
    private val store: PaletteStore,
) {
    var items: List<CustomPalette> by mutableStateOf(store.list())
        private set

    fun save(palette: CustomPalette): CustomPalette {
        val stored = store.save(palette)
        items = store.list()
        return stored
    }

    fun delete(id: String) {
        store.delete(id)
        items = store.list()
    }
}

@Composable
internal fun rememberSavedPalettes(): SavedPalettes {
    val context = LocalContext.current
    return remember(context) { SavedPalettes(PaletteStore(context)) }
}

/**
 * Chip index for a palette slot: the built-ins occupy 0 until
 * `SceneParams.PALETTES.size`, saved palettes follow. Returns -1 (nothing
 * highlighted) when the slot runs a one-off gradient or points at an id that
 * is no longer on disk.
 */
internal fun paletteChipIndex(
    p: SceneParams,
    saved: List<CustomPalette>,
    second: Boolean,
): Int {
    val custom = if (second) p.usesCustomPalette2 else p.usesCustomPalette
    if (!custom) return if (second) p.palette2 else p.palette
    val id = if (second) p.customPalette2Id else p.customPaletteId
    val index = saved.indexOfFirst { it.id == id }
    return if (index < 0) -1 else SceneParams.PALETTES.size + index
}

/**
 * Params after chip [index] is tapped. Picking a built-in must DROP the slot's
 * override rather than merely renumber it - an active override outranks the
 * PALETTES table, so a plain `copy(palette = index)` would leave the custom
 * hues on screen while the UI highlighted a built-in chip.
 */
internal fun paletteChipSelected(
    p: SceneParams,
    saved: List<CustomPalette>,
    index: Int,
    second: Boolean,
): SceneParams {
    val builtInCount = SceneParams.PALETTES.size
    return if (index < builtInCount) {
        PaletteStore.clear(if (second) p.copy(palette2 = index) else p.copy(palette = index), second)
    } else {
        PaletteStore.applyPalette(p, saved[index - builtInCount], second)
    }
}

/** Built-in + saved palette chips for one slot. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaletteSlotSelector(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
    palettes: SavedPalettes,
    second: Boolean = false,
) {
    val saved = palettes.items
    val labels = SceneParams.PALETTES.map { it.first } + saved.map { it.name }
    val selected = paletteChipIndex(p, saved, second)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            FilterChip(
                selected = index == selected,
                onClick = { onChange(paletteChipSelected(p, saved, index, second)) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
    GradientPreview(
        baseHue = if (second) p.palette2Base else p.paletteBase,
        hueSpan = if (second) p.palette2Range else p.paletteRange,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Horizontal swatch of the hue gradient a (base, span) pair produces. */
@Composable
internal fun GradientPreview(
    baseHue: Float,
    hueSpan: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(gradientColors(baseHue, hueSpan))),
    )
}

/**
 * The maker itself: two sliders (where the gradient starts, how far it
 * sweeps), a live swatch, audition-without-saving, and the saved library with
 * edit/delete. Deleting the palette a slot is using keeps the hues on screen
 * and only drops the dangling id - see [PaletteStore.forgetDeleted].
 */
@Composable
internal fun PaletteMakerCard(
    p: SceneParams,
    onChange: (SceneParams) -> Unit,
    palettes: SavedPalettes,
) {
    var baseHue by remember { mutableFloatStateOf(p.paletteBase) }
    var hueSpan by remember { mutableFloatStateOf(p.paletteRange) }
    var name by remember { mutableStateOf("") }
    Column {
        Text(
            "Build a gradient: the base hue is where it starts, the span is " +
                "how far around the colour wheel it sweeps (0 = one flat " +
                "colour). Apply auditions it on the scene; Save adds it to the " +
                "palette row above and to every preset you save afterwards.",
            style = MaterialTheme.typography.labelSmall,
        )
        GradientPreview(baseHue, hueSpan, modifier = Modifier.fillMaxWidth())
        Text("Base hue ${"%.2f".format(baseHue)}", style = MaterialTheme.typography.labelSmall)
        CrystalSlider(
            value = baseHue,
            onValueChange = { baseHue = it },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Hue span ${"%.2f".format(hueSpan)}", style = MaterialTheme.typography.labelSmall)
        CrystalSlider(
            value = hueSpan,
            onValueChange = { hueSpan = it },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onChange(PaletteStore.applyGradient(p, baseHue, hueSpan)) }) {
                Text("Apply gradient")
            }
            OutlinedButton(onClick = {
                baseHue = p.paletteBase
                hueSpan = p.paletteRange
            }) {
                Text("From current")
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Palette name") },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        OutlinedButton(
            onClick = {
                val stored = palettes.save(PaletteStore.create(name, baseHue, hueSpan))
                name = ""
                onChange(PaletteStore.applyPalette(p, stored))
            },
            enabled = name.isNotBlank(),
        ) {
            Text("Save palette")
        }
        Text("Saved palettes", style = MaterialTheme.typography.labelSmall)
        if (palettes.items.isEmpty()) {
            Text("None yet - build a gradient above and save it.", style = MaterialTheme.typography.labelSmall)
        }
        palettes.items.forEach { palette ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(palette.name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                GradientPreview(palette.baseHue, palette.hueSpan, modifier = Modifier.width(64.dp))
                TextButton(onClick = {
                    baseHue = palette.baseHue
                    hueSpan = palette.hueSpan
                    name = palette.name
                }) {
                    Text("Edit", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = {
                    palettes.delete(palette.id)
                    onChange(PaletteStore.forgetDeleted(p, palette.id))
                }) {
                    Text("Delete", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Colour stops for the preview swatch; hues come from [PaletteStore.sampleHue] so previews match. */
private fun gradientColors(
    baseHue: Float,
    hueSpan: Float,
): List<Color> =
    (0 until PaletteStore.PREVIEW_STOPS).map { i ->
        val (r, g, b) = PaletteStore.hueRgb(PaletteStore.sampleHue(baseHue, hueSpan, i))
        Color(red = r, green = g, blue = b)
    }
