package dev.musicviz.ui

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
     */
    fun colorScheme(
        accentIntensity: Float = 1f,
        backgroundDim: Float = 0f,
    ): ColorScheme {
        val a = anchors()
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val primary = ColorDerive.scaleSaturation(a.primary, accentIntensity)
        val secondary = ColorDerive.scaleSaturation(a.secondary, accentIntensity)
        val tertiary = ColorDerive.lerpArgb(primary, secondary, 0.5f)
        val background = ColorDerive.dim(a.background, backgroundDim)
        val surface = ColorDerive.dim(a.surface, backgroundDim)
        return if (a.light) {
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
    }
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
    val beatThresholdSigma: Float = 2.5f,
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
)

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
            beatThresholdSigma = prefs.getFloat("beat_sigma", 2.5f),
            presetMirrorUri = prefs.getString("preset_mirror_uri", null),
            morphBeats = prefs.getInt("morph_beats", 4),
            accentIntensity = prefs.getFloat(KEY_ACCENT, 1f),
            backgroundDim = prefs.getFloat(KEY_DIM, 0f),
            compactPlayer = prefs.getBoolean(KEY_COMPACT, false),
            followSystemDark = prefs.getBoolean(KEY_FOLLOW_DARK, false),
            clearVisualsMenu = prefs.getBoolean(KEY_CLEAR_VIZ_MENU, false),
        )

    fun saveGui(gui: GuiPrefs) {
        prefs
            .edit()
            .putString(KEY_POS, gui.playerPosition.name)
            .putString(KEY_CORNER, gui.cornerStyle.name)
            .putFloat(KEY_OPACITY, gui.barOpacity)
            .putFloat("beat_sigma", gui.beatThresholdSigma)
            .putString("preset_mirror_uri", gui.presetMirrorUri)
            .putInt("morph_beats", gui.morphBeats)
            .putFloat(KEY_ACCENT, gui.accentIntensity)
            .putFloat(KEY_DIM, gui.backgroundDim)
            .putBoolean(KEY_COMPACT, gui.compactPlayer)
            .putBoolean(KEY_FOLLOW_DARK, gui.followSystemDark)
            .putBoolean(KEY_CLEAR_VIZ_MENU, gui.clearVisualsMenu)
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
    }
}
