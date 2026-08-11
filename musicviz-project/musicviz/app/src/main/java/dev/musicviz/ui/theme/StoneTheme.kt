package dev.musicviz.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.musicviz.R
import dev.musicviz.ui.ThemeContrast.BODY_CONTRAST_MIN
import dev.musicviz.ui.ThemeContrast.HINT_CONTRAST_MIN

/**
 * The active crystal pack. Provided by `CrystalMaterialTheme` at the app
 * root; every stone-painted component reads its art, palette and motion from
 * here rather than from per-call parameters.
 */
val LocalThemePack = staticCompositionLocalOf { ThemePackCatalog.all.first() }

/**
 * Mali carries all UI copy - the packs bundle it in four weights and name it
 * as the `uiFamily` token.
 */
val MaliFamily =
    FontFamily(
        Font(R.font.mali_regular, FontWeight.Normal),
        Font(R.font.mali_medium, FontWeight.Medium),
        Font(R.font.mali_semibold, FontWeight.SemiBold),
        Font(R.font.mali_bold, FontWeight.Bold),
    )

/**
 * Mystery Quest is the display face. The pack contract reserves it for the
 * product mark and short high-level headings; body copy never uses it.
 */
val MysteryQuestFamily = FontFamily(Font(R.font.mystery_quest_regular, FontWeight.Normal))

/**
 * Typography from the packs' shared `typography` tokens (`scaleSp`:
 * display 42, headline 30, title 22, body 16, label 14, caption 12).
 *
 * [textScale] is the user's Appearance setting; it multiplies size and line
 * height together so scaled text keeps its rhythm.
 */
fun stoneTypography(textScale: Float = 1f): Typography {
    fun style(
        family: FontFamily,
        size: Int,
        weight: FontWeight,
        lineHeight: Float = size * 1.35f,
    ) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = (size * textScale).sp,
        lineHeight = (lineHeight * textScale).sp,
    )
    return Typography(
        displayLarge = style(MysteryQuestFamily, 42, FontWeight.Normal),
        displayMedium = style(MysteryQuestFamily, 36, FontWeight.Normal),
        displaySmall = style(MysteryQuestFamily, 30, FontWeight.Normal),
        headlineLarge = style(MysteryQuestFamily, 30, FontWeight.Normal),
        headlineMedium = style(MaliFamily, 26, FontWeight.SemiBold),
        headlineSmall = style(MaliFamily, 24, FontWeight.SemiBold),
        titleLarge = style(MaliFamily, 22, FontWeight.SemiBold),
        titleMedium = style(MaliFamily, 18, FontWeight.Medium),
        titleSmall = style(MaliFamily, 16, FontWeight.Medium),
        bodyLarge = style(MaliFamily, 16, FontWeight.Normal),
        bodyMedium = style(MaliFamily, 15, FontWeight.Normal),
        bodySmall = style(MaliFamily, 13, FontWeight.Normal),
        labelLarge = style(MaliFamily, 14, FontWeight.Medium),
        labelMedium = style(MaliFamily, 13, FontWeight.Medium),
        labelSmall = style(MaliFamily, 12, FontWeight.Medium),
    )
}

/**
 * Material [ColorScheme] for a pack, mapping the thirteen authored roles onto
 * Material's slots directly - no anchor lerping. The packs author their
 * palettes against the stone photography and publish the contrast numbers,
 * so derivation would only drift from the design.
 *
 * [accentIntensity] and [backgroundDim] remain the user's Appearance
 * settings and post-process the authored roles; [fontColorOverride] repaints
 * the writing roles when the shell has already resolved it as readable.
 */
fun ThemePack.colorScheme(
    accentIntensity: Float = 1f,
    backgroundDim: Float = 0f,
    fontColorOverride: Color? = null,
): ColorScheme {
    val p = palette

    fun Color.intensity(): Color = Color(dev.musicviz.ui.ColorDerive.scaleSaturation(toArgbInt(), accentIntensity))

    fun Color.dimmed(): Color = Color(dev.musicviz.ui.ColorDerive.dim(toArgbInt(), backgroundDim))
    val primary = p.primary.intensity()
    val secondary = p.secondary.intensity()
    val background = p.background.dimmed()
    val surface = p.surface.dimmed()
    val surfaceHigh = p.surfaceHigh.dimmed()
    // Background dim darkens the surfaces under the authored writing colours.
    // A light pack crosses from light to dark partway up the slider, and its
    // authored near-black ink would then sit on a near-black panel - so every
    // writing role is re-anchored against the surface it actually paints on.
    // Undimmed (and on dark packs, where dim only widens the gap) the authored
    // colour already clears the bar and passes through untouched.
    val onBackground = readableOn(p.onBackground, background, BODY_CONTRAST_MIN)
    val onSurface = readableOn(p.onSurface, surface, BODY_CONTRAST_MIN)
    val onSurfaceVariant = readableOn(p.muted, surfaceHigh, HINT_CONTRAST_MIN)
    val base =
        if (isLight) {
            lightColorScheme(
                primary = primary,
                onPrimary = p.onSurface,
                secondary = secondary,
                tertiary = p.accent.intensity(),
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceHigh,
                onSurfaceVariant = onSurfaceVariant,
                surfaceContainer = surface,
                surfaceContainerHigh = surfaceHigh,
                primaryContainer = surfaceHigh,
                onPrimaryContainer = readableOn(p.accent, surfaceHigh, HINT_CONTRAST_MIN),
                secondaryContainer = surfaceHigh,
                onSecondaryContainer = onSurface,
                outline = p.outline,
                error = p.danger,
            )
        } else {
            darkColorScheme(
                primary = primary,
                onPrimary = p.background,
                secondary = secondary,
                tertiary = p.accent.intensity(),
                background = background,
                onBackground = onBackground,
                surface = surface,
                onSurface = onSurface,
                surfaceVariant = surfaceHigh,
                onSurfaceVariant = onSurfaceVariant,
                surfaceContainer = surface,
                surfaceContainerHigh = surfaceHigh,
                primaryContainer = surfaceHigh,
                onPrimaryContainer = readableOn(p.accent, surfaceHigh, HINT_CONTRAST_MIN),
                secondaryContainer = surfaceHigh,
                onSecondaryContainer = onSurface,
                outline = p.outline,
                error = p.danger,
            )
        }
    return if (fontColorOverride != null) {
        base.copy(
            onBackground = fontColorOverride,
            onSurface = fontColorOverride,
            onSurfaceVariant = fontColorOverride,
            onPrimaryContainer = fontColorOverride,
            onSecondaryContainer = fontColorOverride,
            onTertiaryContainer = fontColorOverride,
            onErrorContainer = fontColorOverride,
        )
    } else {
        base
    }
}

/** WCAG contrast ratio between two opaque colours. */
private fun contrast(
    a: Color,
    b: Color,
): Float {
    val hi = maxOf(a.luminance(), b.luminance())
    val lo = minOf(a.luminance(), b.luminance())
    return (hi + 0.05f) / (lo + 0.05f)
}

/**
 * [authored] if it already clears [minRatio] against [surface]; otherwise the
 * authored colour pulled toward whichever pole (white or black) opposes the
 * surface, by the smallest step that clears the bar. This is what flips a
 * light pack's ink pale once background dim has pushed its panels dark.
 */
private fun readableOn(
    authored: Color,
    surface: Color,
    minRatio: Float,
): Color {
    if (contrast(authored, surface) >= minRatio) return authored
    val pole = if (surface.luminance() < 0.5f) Color.White else Color.Black
    var t = 0.1f
    while (t < 1f) {
        val candidate = lerp(authored, pole, t)
        if (contrast(candidate, surface) >= minRatio) return candidate
        t += 0.1f
    }
    return pole
}

private fun lerp(
    a: Color,
    b: Color,
    t: Float,
): Color =
    Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
    )

private fun Color.toArgbInt(): Int =
    ((alpha * 255f + 0.5f).toInt() shl 24) or
        ((red * 255f + 0.5f).toInt() shl 16) or
        ((green * 255f + 0.5f).toInt() shl 8) or
        (blue * 255f + 0.5f).toInt()
