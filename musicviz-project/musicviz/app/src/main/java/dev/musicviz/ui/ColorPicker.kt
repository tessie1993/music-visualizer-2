package dev.musicviz.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * A curated starting set for the swatch grid, as opaque ARGB.
 *
 * Two clusters on purpose. The first eleven are light enough to read on the
 * twenty-four dark themes; the last five are dark enough to read on Light and
 * Paper. Nothing here is mid-grey, because mid-grey is the one band that fails
 * [TextContrast.MIN_LEGIBLE] on BOTH kinds of theme, and a swatch that never
 * works anywhere is just a trap.
 *
 * The grid is a starting point, not the range - the HSV sliders and the hex
 * field below it reach every colour, and the contrast readout judges whatever
 * comes out of them the same way.
 */
private val SWATCHES =
    intArrayOf(
        0xFFFFFFFF.toInt(), // White - what the old "White font" switch gave.
        0xFFF5F0E6.toInt(), // Bone
        0xFFFFE0B2.toInt(), // Sand
        0xFFFFCDD2.toInt(), // Blush
        0xFFF8BBD0.toInt(), // Rose
        0xFFE1BEE7.toInt(), // Lilac
        0xFFC5CAE9.toInt(), // Periwinkle
        0xFFB3E5FC.toInt(), // Sky
        0xFFB2DFDB.toInt(), // Seafoam
        0xFFDCEDC8.toInt(), // Meadow
        0xFFFFF59D.toInt(), // Lemon
        0xFF000000.toInt(), // Black
        0xFF212121.toInt(), // Charcoal
        0xFF1A237E.toInt(), // Indigo
        0xFF004D40.toInt(), // Pine
        0xFF4A148C.toInt(), // Aubergine
    )

/**
 * The Appearance row for the app-wide text colour: a switch that turns the
 * option on and off, and - while it is on - a swatch that opens [ColorPicker].
 *
 * Takes the whole [GuiPrefs] and hands back a modified copy, matching how
 * every other Appearance control in `AppShell.SettingsScreen` talks to the
 * view model. It deliberately does NOT take the [AppTheme]: the backdrops it
 * has to judge contrast against are the live `MaterialTheme.colorScheme`,
 * already carrying the user's accent-intensity and background-dim, and reading
 * them from there is both shorter and impossible to get out of step with what
 * is actually on screen.
 */
@Composable
fun TextColorSetting(
    gui: GuiPrefs,
    onChange: (GuiPrefs) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    // The LIVE scheme, not the theme's: its `on*` roles may already carry the
    // chosen colour, but the backdrop roles `textBackdrops` reads never do -
    // `withTextColor` repaints text roles only - so this measures against
    // exactly what will be drawn, with the accent-intensity and background-dim
    // sliders folded in for free.
    val backdrops = MaterialTheme.colorScheme.textBackdrops()
    val chosen = gui.textColor
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Custom text colour", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (chosen != null) {
                Swatch(
                    argb = chosen,
                    selected = true,
                    modifier = Modifier.padding(end = 10.dp),
                    onClick = { picking = true },
                )
            }
            Switch(
                checked = chosen != null,
                // Turning it on lands on white, which is what this option used
                // to be - so the switch alone reproduces the old feature and
                // the picker is the part that is new.
                onCheckedChange = { on -> onChange(gui.copy(textColor = if (on) TextColorPref.WHITE else null)) },
            )
        }
        Text(
            when {
                chosen == null ->
                    "Paints labels and body text one colour of your choosing, instead of the theme's."
                !TextContrast.isLegible(chosen, backdrops) ->
                    "Not applied on this theme: ${ratioText(chosen, backdrops)} contrast is too low to read. " +
                        "Tap the swatch to pick another colour."
                TextContrast.worstRatio(chosen, backdrops) < TextContrast.AA_TEXT ->
                    "Applied, but only ${ratioText(chosen, backdrops)} against the busiest surfaces — " +
                        "under the ${fmt(TextContrast.AA_TEXT)}:1 readable-text guideline."
                else -> "Tap the swatch to change it. Contrast here is ${ratioText(chosen, backdrops)}."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (picking && chosen != null) {
        ColorPicker(
            initial = chosen,
            backdrops = backdrops,
            onDismiss = { picking = false },
            onPick = {
                onChange(gui.copy(textColor = it))
                picking = false
            },
        )
    }
}

/**
 * Hand-rolled colour picker: swatch grid, HSV sliders, hex field, and a live
 * contrast verdict against the surfaces the colour will actually be written
 * on.
 *
 * Hand-rolled because there is no picker dependency in this project and adding
 * one for three sliders would be the larger cost. The sliders are the ordinary
 * [CrystalSlider] the rest of Settings uses rather than a custom saturation-
 * value square, which keeps this file free of gesture and canvas code and
 * makes every control here reachable by a screen reader for nothing.
 *
 * HSV rather than RGB sliders because the useful move in this dialog is
 * "same colour, lighter" - which is one slider in HSV and three in RGB.
 */
@Composable
fun ColorPicker(
    initial: Int,
    backdrops: IntArray,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var color by remember { mutableStateOf(initial or TextContrast.OPAQUE_ALPHA) }
    // The HSV sliders own their own state rather than round-tripping through
    // [color] every frame: hue and saturation are undefined for white and
    // black, so a round trip would swing the hue slider to 0 the moment the
    // user dragged value to either end and silently lose the hue they were
    // working on.
    val seed = remember { TextContrast.argbToHsv(initial) }
    var hue by remember { mutableStateOf(seed[0]) }
    var saturation by remember { mutableStateOf(seed[1]) }
    var value by remember { mutableStateOf(seed[2]) }
    // Likewise the hex field: it is only re-seeded when something else moves,
    // so a half-typed "1A2" is not overwritten between keystrokes.
    var hex by remember { mutableStateOf(TextContrast.toHex(initial)) }

    fun setFromHsv() {
        color = TextContrast.hsvToArgb(hue, saturation, value)
        hex = TextContrast.toHex(color)
    }

    fun setFromArgb(argb: Int) {
        color = argb or TextContrast.OPAQUE_ALPHA
        val hsv = TextContrast.argbToHsv(color)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        hex = TextContrast.toHex(color)
    }

    val worst = TextContrast.worstRatio(color, backdrops)
    val legible = worst >= TextContrast.MIN_LEGIBLE
    val fix = if (legible) null else TextContrast.nearestLegible(color, backdrops)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Text colour") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ColorPreview(color, worst, legible)
                CrystalOverline("Swatches")
                // Chunked into a Column of Rows rather than a LazyVerticalGrid:
                // a lazy grid inside an AlertDialog's scrolling body nests two
                // scroll containers, which Compose refuses at runtime.
                SWATCHES.toList().chunked(SWATCHES_PER_ROW).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { swatch ->
                            Swatch(swatch, selected = swatch == color) { setFromArgb(swatch) }
                        }
                    }
                }
                CrystalOverline("Adjust")
                LabeledColorSlider("Hue", "${(hue * 360f).toInt()}°", hue) {
                    hue = it
                    setFromHsv()
                }
                LabeledColorSlider("Saturation", "${(saturation * 100f).toInt()}%", saturation) {
                    saturation = it
                    setFromHsv()
                }
                LabeledColorSlider("Brightness", "${(value * 100f).toInt()}%", value) {
                    value = it
                    setFromHsv()
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { typed ->
                        hex = typed.take(HEX_FIELD_MAX)
                        TextContrast.parseHex(hex)?.let { parsed ->
                            color = parsed
                            val hsv = TextContrast.argbToHsv(parsed)
                            hue = hsv[0]
                            saturation = hsv[1]
                            value = hsv[2]
                        }
                    },
                    label = { Text("Hex (#RRGGBB)") },
                    singleLine = true,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            capitalization = KeyboardCapitalization.Characters,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (fix != null) {
                    TextButton(onClick = { setFromArgb(fix) }) {
                        Text("Nudge to the nearest readable shade")
                    }
                }
            }
        },
        confirmButton = {
            // Deliberately NOT disabled when the colour fails the floor. The
            // user is allowed to keep an unreadable colour - the theme simply
            // will not paint with it until they switch to a theme it works on,
            // and the row underneath the switch says exactly that. Blocking the
            // button instead would strand anyone who wanted to set a colour for
            // a theme they are about to move to.
            TextButton(onClick = { onPick(color) }) { Text("Use this colour") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Sample writing in the candidate colour on the two backdrops it will most
 * often land on - a plain panel and a filled chip - plus the measured ratio
 * against the worst of ALL of them. Showing it on the real surfaces is the
 * point: a swatch on its own tells you nothing about whether you will be able
 * to read with it.
 */
@Composable
private fun ColorPreview(
    color: Int,
    worst: Float,
    legible: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val text = Color(color)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewPanel("Body text", text, cs.surface, Modifier.weight(1f))
            PreviewPanel("On a chip", text, cs.primaryContainer, Modifier.weight(1f))
        }
        Text(
            when {
                !legible ->
                    "${ratioFmt(worst)} contrast — below the ${fmt(TextContrast.MIN_LEGIBLE)}:1 floor, " +
                        "so this colour will not be applied."
                worst < TextContrast.AA_TEXT ->
                    "${ratioFmt(worst)} contrast — usable, but under the " +
                        "${fmt(TextContrast.AA_TEXT)}:1 guideline for body text."
                else -> "${ratioFmt(worst)} contrast — comfortably readable."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (legible) cs.onSurfaceVariant else cs.error,
        )
    }
}

@Composable
private fun PreviewPanel(
    label: String,
    text: Color,
    backdrop: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .height(46.dp)
            .background(backdrop, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = text)
    }
}

@Composable
private fun Swatch(
    argb: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(32.dp)
            .background(Color(argb), RoundedCornerShape(8.dp))
            .border(
                if (selected) 2.dp else 1.dp,
                // Outlined against the dialog's own text colour, so a white
                // swatch on a light theme is still a visible square.
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                RoundedCornerShape(8.dp),
                // A bare coloured square says nothing to a screen reader, and
                // an accessibility feature that is itself inaccessible would
                // be a poor joke - so the hex code is the label.
            ).clickable(onClickLabel = "Colour #${TextContrast.toHex(argb)}", onClick = onClick),
    )
}

@Composable
private fun LabeledColorSlider(
    label: String,
    readout: String,
    value: Float,
    onChange: (Float) -> Unit,
) {
    Column {
        Text("$label  $readout", style = MaterialTheme.typography.labelMedium)
        CrystalSlider(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth())
    }
}

private fun ratioText(
    color: Int,
    backdrops: IntArray,
): String = ratioFmt(TextContrast.worstRatio(color, backdrops))

private fun ratioFmt(ratio: Float): String = "${fmt(ratio)}:1"

private fun fmt(value: Float): String = "%.1f".format(value)

/** Four per row keeps the grid inside a narrow dialog without scrolling. */
private const val SWATCHES_PER_ROW = 4

/** "#" plus six digits; the field shows the digits only. */
private const val HEX_FIELD_MAX = 7
