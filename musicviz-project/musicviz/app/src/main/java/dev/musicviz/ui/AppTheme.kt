package dev.musicviz.ui

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.musicviz.analysis.FeatureExtractor

/** Four anchor colors per theme; every other Material role is derived. */
private data class Anchors(
    val primary: Int,
    val secondary: Int,
    val background: Int,
    val surface: Int,
    val light: Boolean = false,
)

/**
 * Selectable app themes. Each maps to a Material 3 [ColorScheme]. The
 * visualizer canvas fills the screen behind the controls, so themes mostly
 * affect the control surfaces, dialogs, sliders and accent colors.
 *
 * Each theme defines only four anchor colors; containers, surfaces, outline
 * and tertiary are derived in [colorScheme] by lerping the anchors toward
 * background/white so every variant keeps its character without hand-picking
 * dozens of values.
 */
enum class AppTheme(
    val label: String,
) {
    // Crystal collection - one theme per design-system mockup. The four hero
    // stones (matched to the reference gemstone photography) lead the list;
    // Lapis is the app default. Persistence is by enum NAME, so this order is
    // presentation only and safe to change.
    LAPIS("Lapis"),
    SUGILITE("Sugilite"),
    ROSE_QUARTZ("Rose Quartz"),
    AMETHYST("Amethyst"),

    // Remaining crystal minerals.
    MALACHITE("Malachite"),
    CLEAR_QUARTZ("Clear Quartz"),
    KYANITE("Kyanite"),
    ONYX("Onyx"),

    // Legacy accent themes.
    MIDNIGHT("Midnight"),
    NEON("Neon"),
    SUNSET("Sunset"),
    FOREST("Forest"),
    MONO("Mono"),
    OCEAN("Ocean"),
    VIOLET("Violet"),
    EMBER("Ember"),
    CANDY("Candy"),
    SLATE("Slate"),
    ROSE("Rose"),
    MINT("Mint"),
    COBALT("Cobalt"),
    SAND("Sand"),
    GRAPE("Grape"),
    INK("Ink"),

    // Light-surface themes.
    LIGHT("Light"),
    PAPER("Paper"),
    ;

    /**
     * True for the light-surface themes (Light, Paper, Rose Quartz). A font
     * colour override on them must pass the [resolvedFontColor] contrast
     * check (most of the curated swatches are pale and would blank the UI on
     * a near-white surface), so [colorScheme] and [fontColorActive] gate on
     * this one predicate rather than each testing the enum separately.
     */
    val isLight: Boolean
        get() = anchors().light

    /**
     * The font colour that will actually be painted for [fontColorArgb], or
     * null when the automatic theme colours stay in force.
     *
     * Dark themes accept any override. Light themes accept it only when it
     * passes a simple contrast check against the background they are ACTUALLY
     * painting - a relative-luminance difference of at least
     * [LIGHT_CONTRAST_MIN] - and silently ignore it otherwise: a pale override
     * (white, ivory, rose, …) on a near-white surface would make the whole UI
     * invisible, which is a worse failure than not honouring the preference.
     *
     * [backgroundDim] is that "actually painting" part, and it is why this
     * takes the appearance preference at all: [colorScheme] dims background
     * and surfaces by it, so a light theme at Background dim 60% is painting a
     * DARK surface. Gating against the undimmed anchor there rejected every
     * pale swatch on a screen those swatches were the only readable choice
     * for, and the Settings picker (which greys out what this rejects) left
     * the user with a row of dead swatches and near-black text.
     */
    fun resolvedFontColor(
        fontColorArgb: Int?,
        backgroundDim: Float = 0f,
    ): Int? {
        if (fontColorArgb == null) return null
        val a = anchors()
        if (!a.light) return fontColorArgb
        val painted = Color(ColorDerive.dim(a.background, backgroundDim))
        val diff = kotlin.math.abs(Color(fontColorArgb).luminance() - painted.luminance())
        return if (diff >= LIGHT_CONTRAST_MIN) fontColorArgb else null
    }

    /** True when the font colour override actually takes effect for this theme. */
    fun fontColorActive(
        fontColorArgb: Int?,
        backgroundDim: Float = 0f,
    ): Boolean = resolvedFontColor(fontColorArgb, backgroundDim) != null

    private fun anchors(): Anchors =
        when (this) {
            // Crystal collection anchors are lifted from the theme mockups:
            // primary/secondary from the palette accents, background/surface
            // from the darkest stone + glass panel colors.
            LAPIS -> Anchors(0xFF2A63FF.toInt(), 0xFFD6B15A.toInt(), 0xFF050A1E.toInt(), 0xFF1A2340.toInt())
            // Sugilite slab: violet-periwinkle marble with pink veins tracing
            // the fractures - softer than the old neon violet/magenta pair.
            SUGILITE -> Anchors(0xFF9B7BE8.toInt(), 0xFFE87BB0.toInt(), 0xFF120B20.toInt(), 0xFF241940.toInt())
            // Rose quartz reads LIGHT in the reference photo: blush marble
            // background, soft rose surface, deep plum accents (the standard
            // light-branch derivation turns the primary into the dark text),
            // and a pale-gold secondary for the occasional vein.
            ROSE_QUARTZ -> Anchors(0xFF9C4460.toInt(), 0xFFB98A3E.toInt(), 0xFFF3DCE2.toInt(), 0xFFFBEEF2.toInt(), light = true)
            AMETHYST -> Anchors(0xFFB58BFB.toInt(), 0xFFDB8AFE.toInt(), 0xFF0D0612.toInt(), 0xFF1E1235.toInt())
            // The four remaining minerals sit on the texture brief's
            // dark-tuned ramps: background = the ramp's deepest stop so the
            // procedural stone marks read against their intended base.
            MALACHITE -> Anchors(0xFF00D1B2.toInt(), 0xFFA4E6D8.toInt(), 0xFF06231A.toInt(), 0xFF0B3D2E.toInt())
            CLEAR_QUARTZ -> Anchors(0xFFCFE6FF.toInt(), 0xFFBECDDE.toInt(), 0xFF080C12.toInt(), 0xFF2B3342.toInt())
            KYANITE -> Anchors(0xFF3D7BFF.toInt(), 0xFF7CABFF.toInt(), 0xFF0A1526.toInt(), 0xFF152339.toInt())
            ONYX -> Anchors(0xFF6FA8FF.toInt(), 0xFFA7B7D1.toInt(), 0xFF050607.toInt(), 0xFF16181B.toInt())
            MIDNIGHT -> Anchors(0xFF7C9CFF.toInt(), 0xFF9DA8C7.toInt(), 0xFF05060B.toInt(), 0xFF0F1320.toInt())
            NEON -> Anchors(0xFF00E5FF.toInt(), 0xFFFF3DDA.toInt(), 0xFF04060A.toInt(), 0xFF10131C.toInt())
            SUNSET -> Anchors(0xFFFF9E57.toInt(), 0xFFFF5E7E.toInt(), 0xFF120A0A.toInt(), 0xFF201412.toInt())
            FOREST -> Anchors(0xFF67D982.toInt(), 0xFFB6D96A.toInt(), 0xFF060D0A.toInt(), 0xFF101B15.toInt())
            MONO -> Anchors(0xFFE0E0E0.toInt(), 0xFFBDBDBD.toInt(), 0xFF000000.toInt(), 0xFF141414.toInt())
            OCEAN -> Anchors(0xFF4DD6C1.toInt(), 0xFF5AA7E8.toInt(), 0xFF03090C.toInt(), 0xFF0B1A20.toInt())
            VIOLET -> Anchors(0xFFB388FF.toInt(), 0xFFEA80FC.toInt(), 0xFF0A0612.toInt(), 0xFF171024.toInt())
            EMBER -> Anchors(0xFFFF6E40.toInt(), 0xFFFFC13D.toInt(), 0xFF0F0605.toInt(), 0xFF1E100C.toInt())
            CANDY -> Anchors(0xFFFF80AB.toInt(), 0xFF82B1FF.toInt(), 0xFF0C060B.toInt(), 0xFF1D1120.toInt())
            SLATE -> Anchors(0xFF90A4AE.toInt(), 0xFF78909C.toInt(), 0xFF090B0D.toInt(), 0xFF14181B.toInt())
            ROSE -> Anchors(0xFFFF7BA9.toInt(), 0xFFFFB7CE.toInt(), 0xFF120609.toInt(), 0xFF221016.toInt())
            MINT -> Anchors(0xFF5FE8B8.toInt(), 0xFFA8F2D5.toInt(), 0xFF04100B.toInt(), 0xFF0E1F17.toInt())
            COBALT -> Anchors(0xFF4D7CFE.toInt(), 0xFF8FB0FF.toInt(), 0xFF040816.toInt(), 0xFF0D1530.toInt())
            SAND -> Anchors(0xFFE8C572.toInt(), 0xFFD9B08C.toInt(), 0xFF12100A.toInt(), 0xFF211D12.toInt())
            GRAPE -> Anchors(0xFFB07BFF.toInt(), 0xFFD3B4FF.toInt(), 0xFF0C0614.toInt(), 0xFF1A1026.toInt())
            INK -> Anchors(0xFF9FB6C6.toInt(), 0xFF6E8494.toInt(), 0xFF000305.toInt(), 0xFF0A0F14.toInt())
            LIGHT -> Anchors(0xFF3355DD.toInt(), 0xFF5C6BC0.toInt(), 0xFFF6F7FB.toInt(), 0xFFFFFFFF.toInt(), light = true)
            PAPER -> Anchors(0xFF8D6E63.toInt(), 0xFFA1887F.toInt(), 0xFFFAF6EF.toInt(), 0xFFFFFDF8.toInt(), light = true)
        }

    /**
     * Builds the full color scheme. [accentIntensity] (0.5..1.5) scales the
     * saturation of primary/secondary/tertiary; [backgroundDim] (0..0.6)
     * darkens background and surfaces. Both default to identity.
     *
     * [fontColorArgb] (the Appearance "Font color" option; null = automatic)
     * repaints every body/label text role in that colour. On the light
     * themes it is honoured only when it survives [resolvedFontColor]'s
     * contrast check - pale text on a near-white surface would make the
     * whole UI invisible.
     */
    fun colorScheme(
        accentIntensity: Float = 1f,
        backgroundDim: Float = 0f,
        fontColorArgb: Int? = null,
    ): ColorScheme {
        val a = anchors()
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val primary = ColorDerive.scaleSaturation(a.primary, accentIntensity)
        val secondary = ColorDerive.scaleSaturation(a.secondary, accentIntensity)
        val tertiary = ColorDerive.lerpArgb(primary, secondary, 0.5f)
        val background = ColorDerive.dim(a.background, backgroundDim)
        val surface = ColorDerive.dim(a.surface, backgroundDim)
        val surfaceVariant = ColorDerive.lerpArgb(surface, primary, 0.06f)
        val base =
            if (a.light) {
                lightColorScheme(
                    primary = Color(primary),
                    secondary = Color(secondary),
                    tertiary = Color(tertiary),
                    background = Color(background),
                    surface = Color(surface),
                    surfaceVariant = Color(surfaceVariant),
                    surfaceContainer = Color(ColorDerive.lerpArgb(surface, primary, 0.04f)),
                    surfaceContainerHigh = Color(ColorDerive.lerpArgb(surface, primary, 0.08f)),
                    primaryContainer = Color(ColorDerive.lerpArgb(primary, white, 0.8f)),
                    onPrimaryContainer = Color(ColorDerive.lerpArgb(primary, black, 0.55f)),
                    secondaryContainer = Color(ColorDerive.lerpArgb(secondary, white, 0.8f)),
                    outline = Color(ColorDerive.lerpArgb(secondary, surface, 0.35f)),
                    // Body text keeps the stone's character instead of the
                    // Material near-black: deep plum on Rose Quartz, deep
                    // navy on Light, deep umber on Paper - and the pale side
                    // of the same hues once the dim has taken the surface
                    // dark under them. Undimmed these pick the dark tone, so
                    // the light themes look exactly as they always did.
                    onBackground = Color(readableTone(primary, background)),
                    onSurface = Color(readableTone(primary, surface)),
                    onSurfaceVariant = Color(readableTone(primary, surfaceVariant, 0.45f, HINT_CONTRAST_MIN)),
                )
            } else {
                darkColorScheme(
                    primary = Color(primary),
                    secondary = Color(secondary),
                    tertiary = Color(tertiary),
                    background = Color(background),
                    surface = Color(surface),
                    surfaceVariant = Color(ColorDerive.lerpArgb(surface, primary, 0.10f)),
                    surfaceContainer = Color(ColorDerive.lerpArgb(surface, white, 0.04f)),
                    surfaceContainerHigh = Color(ColorDerive.lerpArgb(surface, white, 0.08f)),
                    primaryContainer = Color(ColorDerive.lerpArgb(primary, background, 0.65f)),
                    onPrimaryContainer = Color(ColorDerive.lerpArgb(primary, white, 0.75f)),
                    secondaryContainer = Color(ColorDerive.lerpArgb(secondary, background, 0.65f)),
                    outline = Color(ColorDerive.lerpArgb(secondary, surface, 0.45f)),
                )
            }
        // resolvedFontColor is the single gate: dark themes always honour the
        // override, light themes only when it can be read on the surface they
        // are painting - which is the DIMMED one, so the dim goes with it.
        val resolved = resolvedFontColor(fontColorArgb, backgroundDim)
        return if (resolved != null) base.tintedText(Color(resolved)) else base
    }

    companion object {
        /**
         * Minimum relative-luminance difference between a font colour
         * override and a LIGHT theme's background for the override to be
         * honoured. 0.5 keeps every pale swatch (including pyrite gold, the
         * darkest of the curated set) off the near-white surfaces while
         * still admitting genuinely dark overrides.
         *
         * Measured against the surface actually being PAINTED, so the same
         * one number covers a light theme at every Background dim setting:
         * near-white it rejects the pale swatches, dimmed to near-black it
         * rejects the dark ones instead, and around the crossover - where
         * nothing separates well from a mid-grey - it rejects whatever fails
         * to.
         */
        const val LIGHT_CONTRAST_MIN = 0.5f

        /** WCAG AA for body text; what the derived `on*` writing roles hold to. */
        const val BODY_CONTRAST_MIN = 4.5f

        /** WCAG AA for large text and UI parts; the bar for the muted hint role. */
        const val HINT_CONTRAST_MIN = 3.0f
    }
}

/** WCAG contrast ratio between two opaque colours. */
private fun contrastRatio(
    a: Int,
    b: Int,
): Float {
    val la = Color(a).luminance()
    val lb = Color(b).luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * The light branch's writing colour for one role: [primary] pulled at least
 * [amount] of the way toward black or toward white, so that it reads on the
 * [on] surface the role is actually painted on.
 *
 * A fixed direction cannot work, because the surface is not fixed. Background
 * dim (0..0.6) darkens background and surfaces, and Rose Quartz crosses from
 * light to dark around a quarter of the way along it; derived toward black
 * regardless, the light themes painted near-black writing on near-black
 * panels for the whole top half of the slider.
 *
 * Two things this deliberately does NOT do:
 *
 *  - switch on the surface's luminance crossing 0.5. The two candidate tones
 *    do not straddle the surface symmetrically, so a midpoint switch puts the
 *    WORST contrast either side of itself; the direction is chosen by which
 *    extreme (pure black, pure white) the surface is further from, which is
 *    the same question asked correctly.
 *  - keep [amount] fixed. Around the crossover a 68%-of-the-way tone cannot
 *    reach [minRatio] in either direction, so the pull deepens - and only
 *    there - until it does or it runs out. Everywhere else (which includes
 *    every undimmed light theme) the first candidate already clears the bar
 *    and the stone keeps exactly the colour it always had.
 */
private fun readableTone(
    primary: Int,
    on: Int,
    amount: Float = 0.68f,
    minRatio: Float = AppTheme.BODY_CONTRAST_MIN,
): Int {
    val black = 0xFF000000.toInt()
    val white = 0xFFFFFFFF.toInt()
    val end = if (contrastRatio(black, on) >= contrastRatio(white, on)) black else white
    var t = amount
    var tone = ColorDerive.lerpArgb(primary, end, t)
    while (t < 1f && contrastRatio(tone, on) < minRatio) {
        t = minOf(1f, t + 0.08f)
        tone = ColorDerive.lerpArgb(primary, end, t)
    }
    return tone
}

/**
 * Repaints the text roles in [color] for the Appearance "Font color" option.
 * [AppTheme.colorScheme] applies this only after [AppTheme.resolvedFontColor]
 * accepted the override.
 *
 * The three surface roles were not enough: a `Text` painted with an `on*`
 * CONTAINER role - every chip and every filled selection in the shell -
 * stayed theme-coloured with the option on, which was the "not all writing
 * turns white" report against the old white-font switch. All four container
 * roles are derived toward the dark background in [AppTheme.colorScheme]
 * (`lerpArgb(x, background, 0.65f)`), so a light override reads on every one
 * of them.
 *
 * `onPrimary`/`onSecondary`/`onTertiary` are deliberately NOT in this list.
 * They sit on the SATURATED fill of a primary button, and several themes
 * (Clear Quartz, Mono) anchor a near-white primary - pale on pale. Those
 * surfaces are handled by [onAccentTextColor]-aware call sites instead,
 * which pick a readable colour per fill.
 *
 * Accent and surface roles themselves are untouched, so gems, sliders,
 * borders and glows keep the theme's identity - only writing changes.
 */
private fun ColorScheme.tintedText(color: Color): ColorScheme =
    copy(
        onBackground = color,
        onSurface = color,
        onSurfaceVariant = color,
        onPrimaryContainer = color,
        onSecondaryContainer = color,
        onTertiaryContainer = color,
        onErrorContainer = color,
    )

/**
 * The font colour override in force, or null for automatic theme colours.
 * [CrystalMaterialTheme] provides the RESOLVED override (after the light
 * theme contrast gate in [AppTheme.resolvedFontColor]), so a value here is
 * always readable on the current surfaces. Screens shown outside the crystal
 * shell fall back to null, i.e. automatic colours.
 */
internal val LocalFontColor = staticCompositionLocalOf<Color?> { null }

/**
 * Colour for accent-tinted WRITING - section headers, selected list rows, the
 * lock chip, tab titles. The font colour override when one is set, the
 * theme's primary otherwise.
 *
 * A [ColorScheme] can only repaint the roles Material resolves for you. Every
 * heading and selected row in this app names its colour EXPLICITLY
 * (`color = MaterialTheme.colorScheme.primary`), and those calls are invisible
 * to [tintedText] - which is exactly why turning the old white-font switch on
 * used to leave the section titles, tab labels, folder headers and "‹ Back"
 * affordances tinted while the body text went white.
 *
 * Deliberately text-only: icons, gems, hairlines, slider tracks and glows keep
 * `colorScheme.primary`, because the font colour option is about the
 * legibility of writing, not about draining the colour out of the shell.
 */
@Composable
fun accentTextColor(): Color = LocalFontColor.current ?: MaterialTheme.colorScheme.primary

/**
 * Colour for writing on a SATURATED accent fill (filled buttons, the play
 * gem). A light override colour is unreadable on the near-white primaries
 * some themes anchor (Clear Quartz, Mono), so with an override in force this
 * picks white or black by the fill's own luminance instead of forcing the
 * override - the one place the font colour option yields to legibility
 * rather than the other way round.
 */
@Composable
fun onAccentTextColor(): Color {
    val cs = MaterialTheme.colorScheme
    if (LocalFontColor.current == null) return cs.onPrimary
    return if (cs.primary.luminance() > 0.55f) Color.Black else Color.White
}

/** Where the music player bar sits on screen; controls sit on the other side. */
enum class PlayerPosition(
    val label: String,
) {
    TOP("Top"),
    BOTTOM("Bottom"),
}

/** Corner styling for the floating control surfaces. */
enum class CornerStyle(
    val label: String,
) {
    SHARP("Sharp"),
    ROUNDED("Rounded"),
    PILL("Pill"),
}

/** Maps the corner style to Material [Shapes] applied at the theme root. */
fun CornerStyle.shapes(): Shapes =
    when (this) {
        CornerStyle.SHARP ->
            Shapes(
                extraSmall = RoundedCornerShape(0.dp),
                small = RoundedCornerShape(0.dp),
                medium = RoundedCornerShape(0.dp),
                large = RoundedCornerShape(0.dp),
                extraLarge = RoundedCornerShape(0.dp),
            )
        CornerStyle.ROUNDED -> Shapes()
        CornerStyle.PILL ->
            Shapes(
                extraSmall = RoundedCornerShape(12.dp),
                small = RoundedCornerShape(16.dp),
                medium = RoundedCornerShape(24.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(32.dp),
            )
    }

/** GUI layout/appearance preferences beyond the color theme. */
data class GuiPrefs(
    val playerPosition: PlayerPosition = PlayerPosition.BOTTOM,
    val cornerStyle: CornerStyle = CornerStyle.ROUNDED,
    val barOpacity: Float = 0.72f,
    /** Beat-detection threshold in sigmas; higher = less sensitive. */
    val beatThresholdSigma: Float = FeatureExtractor.SIGMA_DEFAULT,
    /** Minimum gap between beat flags in ms; higher = fewer flashes on slow tracks. */
    val beatMinIntervalMs: Float = FeatureExtractor.INTERVAL_MS_DEFAULT,
    /** SAF tree the user picked as their preset folder; presets are mirrored
     *  there on save so their own sorting is visible in any file manager. */
    val presetMirrorUri: String? = null,
    /** Preset morphing: params interpolate over this many beats on apply (0 = snap). */
    val morphBeats: Int = 4,
    /** Scales primary/secondary saturation (0.5 muted .. 1.5 vivid). */
    val accentIntensity: Float = 1f,
    /** Darkens background/surface colors (0 = off .. 0.6). */
    val backgroundDim: Float = 0f,
    /** Slimmer mini-player bar. */
    val compactPlayer: Boolean = false,
    /** When the system is in light mode, switch to the LIGHT theme automatically. */
    val followSystemDark: Boolean = false,
    /** Visuals hub renders as a text-only clear overlay on the live canvas,
     *  so adjustments are visible on the visuals while being adjusted. */
    val clearVisualsMenu: Boolean = false,
    /** LEGACY "White font" switch. Superseded by [fontColorArgb]; still a
     *  constructor field so the old Appearance switch keeps compiling until
     *  the font-colour picker replaces it. True behaves like a white
     *  [fontColorArgb] (see [fontColorOverride]); it is no longer persisted
     *  under its own key. */
    val whiteFont: Boolean = false,
    /** App-wide font colour override (ARGB), or null for automatic
     *  theme-derived text colors. Applied through
     *  [AppTheme.resolvedFontColor], so light themes ignore values that
     *  cannot be read on their surfaces. */
    val fontColorArgb: Int? = null,
    /** Multiplies every font size in the crystal typography, 0.85..1.3. */
    val textScale: Float = 1f,
    /** "Safe visuals": caps how fast and how deeply the whole frame may flash.
     *  Off by default, like every other optional visual change here, so saved
     *  presets keep looking the way the user left them. */
    val safeVisuals: Boolean = false,
    /** Flashes per second ceiling while [safeVisuals] is on. */
    val maxFlashHz: Float = dev.musicviz.render.VisualSafety.WCAG_FLASHES_PER_SECOND,
    /** Largest full-screen luminance swing a flash may make, 0..1. */
    val maxFlashDepth: Float = 0.25f,
    /** Keep full-frame invert/solarize available inside [safeVisuals]. */
    val allowInversion: Boolean = false,
    /** Scales speed/shake/drift-style motion. Independent of [safeVisuals]:
     *  this is a vestibular comfort setting, that one is about seizures. */
    val reducedMotion: Boolean = false,
    /** "Live input": drive the visuals from the microphone instead of from a
     *  track. Deliberately NOT persisted as on - an app that opens the
     *  microphone the moment it launches, because of a switch left on weeks
     *  ago, is not something a user has consented to. [ThemeStore.loadGui]
     *  always reads this back as false; the switch is a per-session decision. */
    val micReactive: Boolean = false,
    /** Let a finger drag push the visuals around (Now Playing canvas). */
    val touchSmear: Boolean = false,
    /** How hard a drag displaces the surface, 0.2..2. */
    val touchSmearStrength: Float = 1f,
    /** Colour the visuals from the track's detected musical key. Drives the
     *  Hue shift slider, so it is visible and undoable rather than a hidden
     *  second colour source; turning it off restores the value it replaced. */
    val keyColor: Boolean = false,
    /** Send the visuals to a connected TV/projector and keep the phone as the
     *  control surface. Ignored when nothing is connected. */
    val secondScreen: Boolean = true,
    /** Pinch to zoom and twist to spin on the fullscreen canvas. On by
     *  default: both are standard gestures, and both land on ordinary sliders
     *  that undo them, so there is nothing to get stuck in. */
    val touchTransform: Boolean = true,
) {
    /**
     * The font colour override actually in force: [fontColorArgb] when set,
     * else white while the legacy [whiteFont] switch is still on. Everything
     * that paints text ([CrystalMaterialTheme], [ThemeStore.saveGui]) reads
     * THIS rather than either raw field, so the two inputs can never
     * disagree about what the user sees.
     */
    val fontColorOverride: Int?
        get() = fontColorArgb ?: FontColorChoice.WHITE_ARGB.takeIf { whiteFont }

    /**
     * [beatMinIntervalMs] after the Safe-visuals floor.
     *
     * EVERY consumer that feeds the beat detector must use this rather than
     * the raw slider value - the live engine, the offline analyzer, the cache
     * re-decision and the export. They all have to agree on one number or the
     * cached and exported beat grids would differ from what playback showed,
     * which is the invariant the whole analysis cache is built around.
     */
    val effectiveBeatMinIntervalMs: Float
        get() =
            dev.musicviz.render.VisualSafety
                .beatMinIntervalMs(beatMinIntervalMs, safety)

    /** The engine-facing view of the safety settings above. */
    val safety: dev.musicviz.render.VisualSafety.SafetyConfig
        get() =
            dev.musicviz.render.VisualSafety.SafetyConfig(
                enabled = safeVisuals,
                maxFlashHz = maxFlashHz,
                maxFlashDepth = maxFlashDepth,
                allowInversion = allowInversion,
                reducedMotion = reducedMotion,
            )

    companion object {
        /** Bounds for [textScale]; enforced on load and by the slider. */
        const val TEXT_SCALE_MIN = 0.85f
        const val TEXT_SCALE_MAX = 1.3f
    }
}

/** Persists the chosen [AppTheme] in shared preferences. */
class ThemeStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("musicviz-prefs", Context.MODE_PRIVATE)

    fun load(): AppTheme =
        runCatching { AppTheme.valueOf(prefs.getString(KEY, AppTheme.LAPIS.name)!!) }
            .getOrDefault(AppTheme.LAPIS)

    fun save(theme: AppTheme) {
        prefs.edit().putString(KEY, theme.name).apply()
    }

    fun loadGui(): GuiPrefs {
        // Font colour, with one-time migration off the legacy white-font
        // Boolean: an absent new key plus legacy true loads as a white
        // override; the next saveGui writes the new key and retires the
        // legacy one.
        val fontColor =
            when {
                prefs.contains(KEY_FONT_COLOR) -> prefs.getInt(KEY_FONT_COLOR, 0)
                prefs.getBoolean(KEY_WHITE_FONT, false) -> FontColorChoice.WHITE_ARGB
                else -> null
            }
        return GuiPrefs(
            playerPosition =
                runCatching { PlayerPosition.valueOf(prefs.getString(KEY_POS, PlayerPosition.BOTTOM.name)!!) }
                    .getOrDefault(PlayerPosition.BOTTOM),
            cornerStyle =
                runCatching { CornerStyle.valueOf(prefs.getString(KEY_CORNER, CornerStyle.ROUNDED.name)!!) }
                    .getOrDefault(CornerStyle.ROUNDED),
            barOpacity = prefs.getFloat(KEY_OPACITY, 0.72f),
            // Coerced on read: values persisted before the range was widened
            // are still valid, but a stored value must never fall outside the
            // slider's range or Compose would clamp the thumb and the shown
            // number would disagree with what the engine is using.
            beatThresholdSigma =
                prefs
                    .getFloat("beat_sigma", FeatureExtractor.SIGMA_DEFAULT)
                    .coerceIn(FeatureExtractor.SIGMA_MIN, FeatureExtractor.SIGMA_MAX),
            beatMinIntervalMs =
                prefs
                    .getFloat(KEY_BEAT_INTERVAL, FeatureExtractor.INTERVAL_MS_DEFAULT)
                    .coerceIn(FeatureExtractor.INTERVAL_MS_MIN, FeatureExtractor.INTERVAL_MS_MAX),
            presetMirrorUri = prefs.getString("preset_mirror_uri", null),
            morphBeats = prefs.getInt("morph_beats", 4),
            accentIntensity = prefs.getFloat(KEY_ACCENT, 1f),
            backgroundDim = prefs.getFloat(KEY_DIM, 0f),
            compactPlayer = prefs.getBoolean(KEY_COMPACT, false),
            followSystemDark = prefs.getBoolean(KEY_FOLLOW_DARK, false),
            clearVisualsMenu = prefs.getBoolean(KEY_CLEAR_VIZ_MENU, false),
            fontColorArgb = fontColor,
            // whiteFont is deliberately NOT loaded: after migration the new
            // field carries the value, and "Auto" must be reachable by
            // clearing fontColorArgb alone.
            textScale =
                prefs
                    .getFloat(KEY_TEXT_SCALE, 1f)
                    .coerceIn(GuiPrefs.TEXT_SCALE_MIN, GuiPrefs.TEXT_SCALE_MAX),
            safeVisuals = prefs.getBoolean(KEY_SAFE_VISUALS, false),
            // Coerced on read for the same reason as the beat settings above:
            // a stored value outside the slider's range would leave the thumb
            // and the number disagreeing with what the renderer is using.
            maxFlashHz =
                prefs
                    .getFloat(KEY_MAX_FLASH_HZ, dev.musicviz.render.VisualSafety.WCAG_FLASHES_PER_SECOND)
                    .coerceIn(1f, dev.musicviz.render.VisualSafety.DEFAULT_STROBE_HZ),
            maxFlashDepth = prefs.getFloat(KEY_MAX_FLASH_DEPTH, 0.25f).coerceIn(0f, 1f),
            allowInversion = prefs.getBoolean(KEY_ALLOW_INVERSION, false),
            reducedMotion = prefs.getBoolean(KEY_REDUCED_MOTION, false),
            // micReactive is intentionally absent: see the field's docs. The
            // microphone is opened only by an explicit switch in this session.
            touchSmear = prefs.getBoolean(KEY_TOUCH_SMEAR, false),
            touchSmearStrength = prefs.getFloat(KEY_TOUCH_SMEAR_STRENGTH, 1f).coerceIn(0.2f, 2f),
            touchTransform = prefs.getBoolean(KEY_TOUCH_TRANSFORM, true),
            keyColor = prefs.getBoolean(KEY_KEY_COLOR, false),
            secondScreen = prefs.getBoolean(KEY_SECOND_SCREEN, true),
        )
    }

    fun saveGui(gui: GuiPrefs) {
        val fontColor = gui.fontColorOverride
        prefs
            .edit()
            .putString(KEY_POS, gui.playerPosition.name)
            .putString(KEY_CORNER, gui.cornerStyle.name)
            .putFloat(KEY_OPACITY, gui.barOpacity)
            .putFloat("beat_sigma", gui.beatThresholdSigma)
            .putFloat(KEY_BEAT_INTERVAL, gui.beatMinIntervalMs)
            .putString("preset_mirror_uri", gui.presetMirrorUri)
            .putInt("morph_beats", gui.morphBeats)
            .putFloat(KEY_ACCENT, gui.accentIntensity)
            .putFloat(KEY_DIM, gui.backgroundDim)
            .putBoolean(KEY_COMPACT, gui.compactPlayer)
            .putBoolean(KEY_FOLLOW_DARK, gui.followSystemDark)
            .putBoolean(KEY_CLEAR_VIZ_MENU, gui.clearVisualsMenu)
            // The resolved override is what gets persisted (so the legacy
            // whiteFont switch, while it still exists, also lands in the new
            // key), and the legacy key is retired on every save - absent
            // plus absent new key means "automatic", and a stale legacy true
            // must never re-trigger the migration after the user picks Auto.
            .apply { if (fontColor != null) putInt(KEY_FONT_COLOR, fontColor) else remove(KEY_FONT_COLOR) }
            .remove(KEY_WHITE_FONT)
            .putFloat(KEY_TEXT_SCALE, gui.textScale)
            .putBoolean(KEY_SAFE_VISUALS, gui.safeVisuals)
            .putFloat(KEY_MAX_FLASH_HZ, gui.maxFlashHz)
            .putFloat(KEY_MAX_FLASH_DEPTH, gui.maxFlashDepth)
            .putBoolean(KEY_ALLOW_INVERSION, gui.allowInversion)
            .putBoolean(KEY_REDUCED_MOTION, gui.reducedMotion)
            .putBoolean(KEY_TOUCH_SMEAR, gui.touchSmear)
            .putFloat(KEY_TOUCH_SMEAR_STRENGTH, gui.touchSmearStrength)
            .putBoolean(KEY_TOUCH_TRANSFORM, gui.touchTransform)
            .putBoolean(KEY_KEY_COLOR, gui.keyColor)
            .putBoolean(KEY_SECOND_SCREEN, gui.secondScreen)
            .apply()
    }

    private companion object {
        const val KEY = "app_theme"
        const val KEY_POS = "gui_player_pos"
        const val KEY_CORNER = "gui_corner"
        const val KEY_OPACITY = "gui_opacity"
        const val KEY_ACCENT = "gui_accent_intensity"
        const val KEY_DIM = "gui_background_dim"
        const val KEY_COMPACT = "gui_compact_player"
        const val KEY_FOLLOW_DARK = "gui_follow_system_dark"
        const val KEY_CLEAR_VIZ_MENU = "gui_clear_visuals_menu"

        // Legacy white-font Boolean; read once for migration, never written.
        const val KEY_WHITE_FONT = "gui_white_font"
        const val KEY_FONT_COLOR = "gui_font_color"
        const val KEY_TEXT_SCALE = "gui_text_scale"
        const val KEY_BEAT_INTERVAL = "beat_min_interval_ms"
        const val KEY_SAFE_VISUALS = "gui_safe_visuals"
        const val KEY_MAX_FLASH_HZ = "gui_max_flash_hz"
        const val KEY_MAX_FLASH_DEPTH = "gui_max_flash_depth"
        const val KEY_ALLOW_INVERSION = "gui_allow_inversion"
        const val KEY_REDUCED_MOTION = "gui_reduced_motion"
        const val KEY_TOUCH_SMEAR = "gui_touch_smear"
        const val KEY_TOUCH_SMEAR_STRENGTH = "gui_touch_smear_strength"
        const val KEY_TOUCH_TRANSFORM = "gui_touch_transform"
        const val KEY_KEY_COLOR = "gui_key_color"
        const val KEY_SECOND_SCREEN = "gui_second_screen"
    }
}
