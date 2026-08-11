package dev.musicviz.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How an icon sits in the stone. The packs ship both finishes for all 24
 * silhouettes: [ETCHED] is cut into the surface, so the shadow falls inside
 * the groove and the stone's glow catches its upper lip; [RELIEF] stands
 * proud, so the glow pools beneath the form and a white highlight rides the
 * top edge.
 */
enum class StoneIconFinish { ETCHED, RELIEF }

/** Sizing shared by every pack icon. */
object StoneIconDefaults {
    /** Material's icon size, which the packs' 104-unit canvas is drawn to fit. */
    val Size: Dp = 24.dp
}

/**
 * A pack icon, drawn the way the packs draw it: the same silhouette stacked
 * three times - a dropped shadow, a lifted highlight, and the live ink on top.
 *
 * This is the icon counterpart of [StoneSurfaceArt], and it is deliberately
 * geometry-plus-tokens rather than imported art. The 24 silhouettes are
 * identical in every crystal pack; only the two engraving colours change, and
 * those already arrive in each pack's palette. So a new crystal gets its icon
 * set the moment its tokens land - nothing to re-import, nothing to redraw.
 *
 * Only the ink layer takes [tint]. That is what the pack contract means by a
 * "live icon": the engraving belongs to the stone, but the reading surface of
 * the glyph follows the theme's content colour and any font-colour override,
 * exactly as text does. Callers dim a disabled icon by passing a [tint] with
 * reduced alpha; the engraving fades with it rather than floating free.
 */
@Composable
fun StoneIconArt(
    icon: StoneIcon,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    finish: StoneIconFinish = StoneIconFinish.ETCHED,
    tint: Color = LocalContentColor.current,
) {
    val palette = LocalThemePack.current.palette
    val glyph = remember(icon) { parseGlyph(icon) }

    // Filled parts (play, next, previous) are ink in every layer - the packs
    // give those a soft bloom rather than an engraved edge - so only the
    // stroked parts take the engraving colours.
    val ink = tint.copy(alpha = 1f)
    val inkAlpha = tint.alpha
    val shadow =
        when (finish) {
            StoneIconFinish.ETCHED -> palette.backgroundDeep
            StoneIconFinish.RELIEF -> palette.glow
        }
    val highlight =
        when (finish) {
            StoneIconFinish.ETCHED -> palette.glow
            StoneIconFinish.RELIEF -> Color.White
        }

    val described =
        if (contentDescription == null) {
            Modifier
        } else {
            Modifier.semantics { this.contentDescription = contentDescription }
        }

    Canvas(modifier.then(Modifier.size(StoneIconDefaults.Size)).then(described)) {
        // The packs author on a 104-unit square; scaling the whole draw keeps
        // stroke weights and offsets in the proportions they were drawn at.
        val factor = size.minDimension / STONE_ICON_VIEWPORT
        scale(factor, pivot = Offset.Zero) {
            drawGlyphLayer(glyph, ink, StoneIconLayer(shadow, SHADOW_WIDTH, SHADOW_ALPHA * inkAlpha, 1.5f, 2f))
            drawGlyphLayer(glyph, ink, StoneIconLayer(highlight, HIGHLIGHT_WIDTH, HIGHLIGHT_ALPHA * inkAlpha, -1f, -1f))
            drawGlyphLayer(glyph, ink, StoneIconLayer(ink, INK_WIDTH, inkAlpha, 0f, 0f))
        }
    }
}

/** One of the three passes: its stroke colour, weight, opacity and offset. */
private class StoneIconLayer(
    val stroke: Color,
    val width: Float,
    val alpha: Float,
    val dx: Float,
    val dy: Float,
)

/** An icon's path data, parsed once per icon rather than per frame. */
private class ParsedGlyph(
    val stroked: List<Path>,
    val filled: List<Path>,
)

private fun parseGlyph(icon: StoneIcon): ParsedGlyph {
    val art = requireNotNull(StoneIconGeometry[icon]) { "no shipped geometry for icon $icon" }

    fun parse(data: List<String>) = data.map { PathParser().parsePathString(it).toPath() }
    return ParsedGlyph(stroked = parse(art.stroked), filled = parse(art.filled))
}

private fun DrawScope.drawGlyphLayer(
    glyph: ParsedGlyph,
    fill: Color,
    layer: StoneIconLayer,
) {
    translate(layer.dx, layer.dy) {
        val stroke = Stroke(width = layer.width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (path in glyph.stroked) {
            drawPath(path, layer.stroke, alpha = layer.alpha, style = stroke)
        }
        for (path in glyph.filled) {
            drawPath(path, fill, alpha = layer.alpha, style = Fill)
        }
    }
}

// Stroke weights and opacities, transcribed from the packs' icon SVGs. Every
// pack authors them identically; only the colours above are per-crystal.
private const val SHADOW_WIDTH = 7f
private const val SHADOW_ALPHA = 0.48f
private const val HIGHLIGHT_WIDTH = 5f
private const val HIGHLIGHT_ALPHA = 0.34f
private const val INK_WIDTH = 5.5f
