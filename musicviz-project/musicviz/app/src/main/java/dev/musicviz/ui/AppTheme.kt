package dev.musicviz.ui

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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
    // Crystal collection - one theme per design-system mockup. Lapis is the
    // app default.
    LAPIS("Lapis"),
    MALACHITE("Malachite"),
    CLEAR_QUARTZ("Clear Quartz"),
    ROSE_QUARTZ("Rose Quartz"),
    SUGILITE("Sugilite"),
    AMETHYST("Amethyst"),
    KYANITE("Kyanite"),
    ONYX("Onyx"),
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
    LIGHT("Light"),
    PAPER("Paper"),
    ;

    /**
     * True for the two light-surface themes. "White font" is a no-op on them
     * (white text on a near-white surface would blank the UI), so
     * [colorScheme] and [whiteFontActive] gate on this one predicate rather
     * than each testing the enum separately.
     */
    val isLight: Boolean
        get() = anchors().light

    /** True when [whiteFont] actually takes effect for this theme. */
    fun whiteFontActive(whiteFont: Boolean): Boolean = whiteFont && !isLight

    private fun anchors(): Anchors =
        when (this) {
            // Crystal collection anchors are lifted from the theme mockups:
            // primary/secondary from the palette accents, background/surface
            // from the darkest stone + glass panel colors.
            LAPIS -> Anchors(0xFF2A63FF.toInt(), 0xFFD6B15A.toInt(), 0xFF050A1E.toInt(), 0xFF1A2340.toInt())
            MALACHITE -> Anchors(0xFF00D1B2.toInt(), 0xFFA4E6D8.toInt(), 0xFF050A09.toInt(), 0xFF0E1514.toInt())
            CLEAR_QUARTZ -> Anchors(0xFFCFE6FF.toInt(), 0xFFBECDDE.toInt(), 0xFF10141D.toInt(), 0xFF2B3342.toInt())
            ROSE_QUARTZ -> Anchors(0xFFF8CCD6.toInt(), 0xFFFFCBA8.toInt(), 0xFF140C12.toInt(), 0xFF2A1D23.toInt())
            SUGILITE -> Anchors(0xFF8C40FF.toInt(), 0xFFFF5CF7.toInt(), 0xFF0B0612.toInt(), 0xFF1E1430.toInt())
            AMETHYST -> Anchors(0xFFB58BFB.toInt(), 0xFFDB8AFE.toInt(), 0xFF0D0612.toInt(), 0xFF1E1235.toInt())
            KYANITE -> Anchors(0xFF3D7BFF.toInt(), 0xFF7CABFF.toInt(), 0xFF070E17.toInt(), 0xFF152339.toInt())
            ONYX -> Anchors(0xFF6FA8FF.toInt(), 0xFFA7B7D1.toInt(), 0xFF0B0D12.toInt(), 0xFF171D26.toInt())
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
     * [whiteFont] forces every body/label text role to pure white. It is
     * deliberately ignored on the light themes (Light, Paper): white text on
     * a near-white surface would make the whole UI invisible.
     */
    fun colorScheme(
        accentIntensity: Float = 1f,
        backgroundDim: Float = 0f,
        whiteFont: Boolean = false,
    ): ColorScheme {
        val a = anchors()
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val primary = ColorDerive.scaleSaturation(a.primary, accentIntensity)
        val secondary = ColorDerive.scaleSaturation(a.secondary, accentIntensity)
        val tertiary = ColorDerive.lerpArgb(primary, secondary, 0.5f)
        val background = ColorDerive.dim(a.background, backgroundDim)
        val surface = ColorDerive.dim(a.surface, backgroundDim)
        val base =
            if (a.light) {
                lightColorScheme(
                    primary = Color(primary),
                    secondary = Color(secondary),
                    tertiary = Color(tertiary),
                    background = Color(background),
                    surface = Color(surface),
                    surfaceVariant = Color(ColorDerive.lerpArgb(surface, primary, 0.06f)),
                    surfaceContainer = Color(ColorDerive.lerpArgb(surface, primary, 0.04f)),
                    surfaceContainerHigh = Color(ColorDerive.lerpArgb(surface, primary, 0.08f)),
                    primaryContainer = Color(ColorDerive.lerpArgb(primary, white, 0.8f)),
                    onPrimaryContainer = Color(ColorDerive.lerpArgb(primary, black, 0.55f)),
                    secondaryContainer = Color(ColorDerive.lerpArgb(secondary, white, 0.8f)),
                    outline = Color(ColorDerive.lerpArgb(secondary, surface, 0.35f)),
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
        // Light themes opt out: white body text on a near-white surface would
        // make the whole UI unreadable.
        return if (whiteFont && !a.light) base.whiteText() else base
    }
}

/**
 * Repaints the text roles pure white for the "White font" appearance option.
 * [AppTheme.colorScheme] applies this to dark schemes only.
 *
 * The three surface roles were not enough: a `Text` painted with an `on*`
 * CONTAINER role - every chip and every filled selection in the shell -
 * stayed theme-coloured with the switch on, which is the "not all writing
 * turns white" report. All four container roles are derived toward the dark
 * background in [AppTheme.colorScheme] (`lerpArgb(x, background, 0.65f)`), so
 * white reads on every one of them.
 *
 * `onPrimary`/`onSecondary`/`onTertiary` are deliberately NOT in this list.
 * They sit on the SATURATED fill of a primary button, and several themes
 * (Clear Quartz, Rose Quartz, Mono) anchor a near-white primary - white on
 * white. Those surfaces are handled by [LocalWhiteFont]-aware call sites
 * instead, which can pick a readable colour per fill.
 *
 * Accent and surface roles themselves are untouched, so gems, sliders,
 * borders and glows keep the theme's identity - only writing changes.
 */
private fun ColorScheme.whiteText(): ColorScheme =
    copy(
        onBackground = Color.White,
        onSurface = Color.White,
        onSurfaceVariant = Color.White,
        onPrimaryContainer = Color.White,
        onSecondaryContainer = Color.White,
        onTertiaryContainer = Color.White,
        onErrorContainer = Color.White,
    )

/**
 * True when [whiteText] produced this scheme, i.e. the Appearance option
 * "White font" is in force.
 *
 * Derived rather than plumbed as a CompositionLocal because the scheme IS the
 * signal: [whiteText] is the only producer of a pure-white `onSurface`, and
 * `WhiteFontThemeTest` pins that no theme resolves one on its own (with the
 * option off, and on the light themes, which opt out entirely). One
 * `MaterialTheme.colorScheme` read is all any call site needs, so no screen
 * has to be handed the GuiPrefs it would otherwise never look at.
 */
val ColorScheme.whiteFontOn: Boolean
    get() = onSurface == Color.White

/**
 * Colour for accent-tinted WRITING - section headers, selected list rows, the
 * lock chip, tab titles. Pure white under "White font", the theme's primary
 * otherwise.
 *
 * A [ColorScheme] can only repaint the roles Material resolves for you. Every
 * heading and selected row in this app names its colour EXPLICITLY
 * (`color = MaterialTheme.colorScheme.primary`), and those calls are invisible
 * to [whiteText] - which is exactly why turning the switch on used to leave
 * the section titles, tab labels, folder headers and "‹ Back" affordances
 * tinted while the body text went white.
 *
 * Deliberately text-only: icons, gems, hairlines, slider tracks and glows keep
 * `colorScheme.primary`, because "white font" is about the legibility of
 * writing, not about draining the colour out of the whole shell.
 */
@Composable
fun accentTextColor(): Color = MaterialTheme.colorScheme.let { if (it.whiteFontOn) Color.White else it.primary }

/**
 * Colour for writing on a SATURATED accent fill (filled buttons, the play
 * gem). White text is unreadable on the near-white primaries some themes
 * anchor (Clear Quartz, Rose Quartz, Mono), so this picks white or black by
 * the fill's own luminance instead of forcing either - the one place "white
 * font" yields to legibility rather than the other way round.
 */
@Composable
fun onAccentTextColor(): Color {
    val cs = MaterialTheme.colorScheme
    if (!cs.whiteFontOn) return cs.onPrimary
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
    /** Forces body/label text to pure white (dark themes only). Off keeps the
     *  theme-derived text colors, so existing users see no change. */
    val whiteFont: Boolean = false,
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
) {
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

    fun loadGui(): GuiPrefs =
        GuiPrefs(
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
            whiteFont = prefs.getBoolean(KEY_WHITE_FONT, false),
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
        )

    fun saveGui(gui: GuiPrefs) {
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
            .putBoolean(KEY_WHITE_FONT, gui.whiteFont)
            .putBoolean(KEY_SAFE_VISUALS, gui.safeVisuals)
            .putFloat(KEY_MAX_FLASH_HZ, gui.maxFlashHz)
            .putFloat(KEY_MAX_FLASH_DEPTH, gui.maxFlashDepth)
            .putBoolean(KEY_ALLOW_INVERSION, gui.allowInversion)
            .putBoolean(KEY_REDUCED_MOTION, gui.reducedMotion)
            .putBoolean(KEY_TOUCH_SMEAR, gui.touchSmear)
            .putFloat(KEY_TOUCH_SMEAR_STRENGTH, gui.touchSmearStrength)
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
        const val KEY_WHITE_FONT = "gui_white_font"
        const val KEY_BEAT_INTERVAL = "beat_min_interval_ms"
        const val KEY_SAFE_VISUALS = "gui_safe_visuals"
        const val KEY_MAX_FLASH_HZ = "gui_max_flash_hz"
        const val KEY_MAX_FLASH_DEPTH = "gui_max_flash_depth"
        const val KEY_ALLOW_INVERSION = "gui_allow_inversion"
        const val KEY_REDUCED_MOTION = "gui_reduced_motion"
        const val KEY_TOUCH_SMEAR = "gui_touch_smear"
        const val KEY_TOUCH_SMEAR_STRENGTH = "gui_touch_smear_strength"
    }
}
