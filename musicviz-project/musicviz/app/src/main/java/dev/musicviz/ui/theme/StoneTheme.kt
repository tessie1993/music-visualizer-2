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
import dev.musicviz.ui.ThemeContrast

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

    // Writing roles are authored against the UNDIMMED stone, so each one is
    // re-checked against the surface it is actually painted on. Undimmed that
    // is a no-op - every shipped pack clears its bars as authored - and it
    // only engages once the dim has moved the ground out from under a colour.
    val onBackground = readableOn(p.onBackground, background, ThemeContrast.BODY_CONTRAST_MIN)
    val onSurface = readableOn(p.onSurface, surface, ThemeContrast.BODY_CONTRAST_MIN)
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
                onSurfaceVariant = readableOn(p.muted, surfaceHigh, ThemeContrast.HINT_CONTRAST_MIN),
                surfaceContainer = surface,
                surfaceContainerHigh = surfaceHigh,
                primaryContainer = surfaceHigh,
                onPrimaryContainer = readableOn(p.accent, surfaceHigh, ThemeContrast.HINT_CONTRAST_MIN),
                secondaryContainer = surfaceHigh,
                onSecondaryContainer = readableOn(p.onSurface, surfaceHigh, ThemeContrast.BODY_CONTRAST_MIN),
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
                onSurfaceVariant = readableOn(p.muted, surfaceHigh, ThemeContrast.HINT_CONTRAST_MIN),
                surfaceContainer = surface,
                surfaceContainerHigh = surfaceHigh,
                primaryContainer = surfaceHigh,
                onPrimaryContainer = readableOn(p.accent, surfaceHigh, ThemeContrast.HINT_CONTRAST_MIN),
                secondaryContainer = surfaceHigh,
                onSecondaryContainer = readableOn(p.onSurface, surfaceHigh, ThemeContrast.BODY_CONTRAST_MIN),
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

/**
 * The pack's authored writing colour for [surface], pulled toward black or
 * white only as far as it must be to stay readable on it.
 *
 * The packs author each writing role against the stone as photographed, and
 * publish the contrast figures for it - so at the shipped setting this
 * returns [authored] untouched and the design is exactly what the pack says.
 * Background dim (0..0.6) is the one thing that moves the ground afterwards:
 * it darkens background and surfaces while the authored writing stays put,
 * and Clear Quartz crosses from light to dark around a third of the way along
 * the slider. Past that the panels are near-black and near-black writing goes
 * with them - a blank screen, from a slider that only claimed to dim.
 *
 * Two things this deliberately does NOT do:
 *
 *  - switch on the surface's luminance crossing 0.5. The two candidate tones
 *    do not straddle the surface symmetrically, so a midpoint switch puts the
 *    WORST contrast either side of itself; the direction is chosen by which
 *    extreme the surface is further from, which is the same question asked
 *    correctly.
 *  - pull a fixed amount. The pull deepens only until [minRatio] is met, so a
 *    role that has just crossed its bar keeps almost all of its authored
 *    colour instead of being flattened to plain white.
 */
private fun readableOn(
    authored: Color,
    surface: Color,
    minRatio: Float,
): Color {
    if (contrastRatio(authored, surface) >= minRatio) return authored
    val end = if (contrastRatio(Color.Black, surface) >= contrastRatio(Color.White, surface)) Color.Black else Color.White
    var t = 0f
    var tone = authored
    while (t < 1f && contrastRatio(tone, surface) < minRatio) {
        t = minOf(1f, t + 0.08f)
        tone = Color(dev.musicviz.ui.ColorDerive.lerpArgb(authored.toArgbInt(), end.toArgbInt(), t))
    }
    return tone
}

/** WCAG contrast ratio between two opaque colours. */
private fun contrastRatio(
    a: Color,
    b: Color,
): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

private fun Color.toArgbInt(): Int =
    ((alpha * 255f + 0.5f).toInt() shl 24) or
        ((red * 255f + 0.5f).toInt() shl 16) or
        ((green * 255f + 0.5f).toInt() shl 8) or
        (blue * 255f + 0.5f).toInt()
