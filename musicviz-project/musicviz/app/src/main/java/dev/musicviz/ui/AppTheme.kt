package dev.musicviz.ui

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Selectable app themes. Each maps to a Material 3 [ColorScheme]. The
 * visualizer canvas fills the screen behind the controls, so themes mostly
 * affect the control surfaces, dialogs, sliders and accent colors.
 */
enum class AppTheme(
    val label: String,
) {
    // Crystal themes: built 1:1 from the MusicViz design-system sheets
    // (palette hexes taken straight from each sheet's COLOR PALETTE block).
    ROSE_QUARTZ("Rose Quartz"),
    SUGILITE("Sugilite"),
    LAPIS("Lapis Lazuli"),
    MALACHITE("Malachite"),
    KYANITE("Kyanite"),
    AMETHYST("Amethyst"),
    ONYX("Onyx"),
    CLEAR_QUARTZ("Clear Quartz"),

    // Original themes (the pre-crystal UI style, kept selectable).
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

    fun colorScheme(): ColorScheme =
        when (this) {
            ROSE_QUARTZ ->
                darkColorScheme(
                    primary = Color(0xFFF8CCD6),
                    onPrimary = Color(0xFF2A1D23),
                    secondary = Color(0xFFFFCBA8),
                    onSecondary = Color(0xFF2A1D23),
                    tertiary = Color(0xFFC9B6E0),
                    background = Color(0xFF1B121A),
                    surface = Color(0xFF241820),
                    surfaceVariant = Color(0xFF2A1D23),
                    secondaryContainer = Color(0xFF3A2630),
                    onSecondaryContainer = Color(0xFFF8CCD6),
                    onBackground = Color(0xFFF7E6EA),
                    onSurface = Color(0xFFF7E6EA),
                    onSurfaceVariant = Color(0xFFD7B9C2),
                )
            SUGILITE ->
                darkColorScheme(
                    primary = Color(0xFFA855F7),
                    onPrimary = Color(0xFFFFFFFF),
                    secondary = Color(0xFFFF5CF7),
                    onSecondary = Color(0xFF120B1A),
                    tertiary = Color(0xFFD786FF),
                    background = Color(0xFF120B1A),
                    surface = Color(0xFF1C1128),
                    surfaceVariant = Color(0xFF271737),
                    secondaryContainer = Color(0xFF3A1F55),
                    onSecondaryContainer = Color(0xFFE4C6FF),
                    onBackground = Color(0xFFEFE6FA),
                    onSurface = Color(0xFFEFE6FA),
                    onSurfaceVariant = Color(0xFFC3AEDB),
                )
            LAPIS ->
                darkColorScheme(
                    primary = Color(0xFF2A63FF),
                    onPrimary = Color(0xFFECECFA),
                    secondary = Color(0xFFD6B15A),
                    onSecondary = Color(0xFF1A2340),
                    tertiary = Color(0xFF8CA6FF),
                    background = Color(0xFF0A1128),
                    surface = Color(0xFF141D38),
                    surfaceVariant = Color(0xFF1A2340),
                    secondaryContainer = Color(0xFF23305A),
                    onSecondaryContainer = Color(0xFFD6B15A),
                    onBackground = Color(0xFFECECFA),
                    onSurface = Color(0xFFECECFA),
                    onSurfaceVariant = Color(0xFFAAB4D6),
                )
            MALACHITE ->
                darkColorScheme(
                    primary = Color(0xFF00D1B2),
                    onPrimary = Color(0xFF050A09),
                    secondary = Color(0xFF2CFFE1),
                    onSecondary = Color(0xFF050A09),
                    tertiary = Color(0xFFA4E6D8),
                    background = Color(0xFF050A09),
                    surface = Color(0xFF0E1514),
                    surfaceVariant = Color(0xFF1C2B28),
                    secondaryContainer = Color(0xFF14352F),
                    onSecondaryContainer = Color(0xFF2CFFE1),
                    onBackground = Color(0xFFE8F3F1),
                    onSurface = Color(0xFFE8F3F1),
                    onSurfaceVariant = Color(0xFFA0BCB5),
                )
            KYANITE ->
                darkColorScheme(
                    primary = Color(0xFF3D7BFF),
                    onPrimary = Color(0xFFF2F6FF),
                    secondary = Color(0xFF7CA8FF),
                    onSecondary = Color(0xFF070E17),
                    tertiary = Color(0xFFD0E6FF),
                    background = Color(0xFF070E17),
                    surface = Color(0xFF111B2A),
                    surfaceVariant = Color(0xFF152339),
                    secondaryContainer = Color(0xFF1D2E44),
                    onSecondaryContainer = Color(0xFFD0E6FF),
                    onBackground = Color(0xFFF2F6FF),
                    onSurface = Color(0xFFF2F6FF),
                    onSurfaceVariant = Color(0xFF9AA4B2),
                )
            AMETHYST ->
                darkColorScheme(
                    primary = Color(0xFFB58DFB),
                    onPrimary = Color(0xFF0D0612),
                    secondary = Color(0xFFD8BAFE),
                    onSecondary = Color(0xFF0D0612),
                    tertiary = Color(0xFF8E61AB),
                    background = Color(0xFF0D0612),
                    surface = Color(0xFF180E24),
                    surfaceVariant = Color(0xFF221334),
                    secondaryContainer = Color(0xFF2E1065),
                    onSecondaryContainer = Color(0xFFD8BAFE),
                    onBackground = Color(0xFFF2F0FF),
                    onSurface = Color(0xFFF2F0FF),
                    onSurfaceVariant = Color(0xFFC4B8FD),
                )
            ONYX ->
                darkColorScheme(
                    primary = Color(0xFF6FA8FF),
                    onPrimary = Color(0xFF080D12),
                    secondary = Color(0xFFA7B7D1),
                    onSecondary = Color(0xFF080D12),
                    tertiary = Color(0xFFE6E9ED),
                    background = Color(0xFF080D12),
                    surface = Color(0xFF11161B),
                    surfaceVariant = Color(0xFF171D26),
                    secondaryContainer = Color(0xFF202833),
                    onSecondaryContainer = Color(0xFFE6E9ED),
                    onBackground = Color(0xFFE6E9ED),
                    onSurface = Color(0xFFE6E9ED),
                    onSurfaceVariant = Color(0xFFA7B7D1),
                )
            CLEAR_QUARTZ ->
                darkColorScheme(
                    primary = Color(0xFFCFE6FF),
                    onPrimary = Color(0xFF283342),
                    secondary = Color(0xFFBECDDE),
                    onSecondary = Color(0xFF283342),
                    tertiary = Color(0xFF96A6B8),
                    background = Color(0xFF222B38),
                    surface = Color(0xFF2E3947),
                    surfaceVariant = Color(0xFF3A4655),
                    secondaryContainer = Color(0xFF465364),
                    onSecondaryContainer = Color(0xFFF7FAFF),
                    onBackground = Color(0xFFF7FAFF),
                    onSurface = Color(0xFFF7FAFF),
                    onSurfaceVariant = Color(0xFFBECDDE),
                )
            MIDNIGHT ->
                darkColorScheme(
                    primary = Color(0xFF7C9CFF),
                    secondary = Color(0xFF9DA8C7),
                    background = Color(0xFF05060B),
                    surface = Color(0xFF0F1320),
                )
            NEON ->
                darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFFFF3DDA),
                    background = Color(0xFF04060A),
                    surface = Color(0xFF10131C),
                )
            SUNSET ->
                darkColorScheme(
                    primary = Color(0xFFFF9E57),
                    secondary = Color(0xFFFF5E7E),
                    background = Color(0xFF120A0A),
                    surface = Color(0xFF201412),
                )
            FOREST ->
                darkColorScheme(
                    primary = Color(0xFF67D982),
                    secondary = Color(0xFFB6D96A),
                    background = Color(0xFF060D0A),
                    surface = Color(0xFF101B15),
                )
            MONO ->
                darkColorScheme(
                    primary = Color(0xFFE0E0E0),
                    secondary = Color(0xFFBDBDBD),
                    background = Color(0xFF000000),
                    surface = Color(0xFF141414),
                )
            OCEAN ->
                darkColorScheme(
                    primary = Color(0xFF4DD6C1),
                    secondary = Color(0xFF5AA7E8),
                    background = Color(0xFF03090C),
                    surface = Color(0xFF0B1A20),
                )
            VIOLET ->
                darkColorScheme(
                    primary = Color(0xFFB388FF),
                    secondary = Color(0xFFEA80FC),
                    background = Color(0xFF0A0612),
                    surface = Color(0xFF171024),
                )
            EMBER ->
                darkColorScheme(
                    primary = Color(0xFFFF6E40),
                    secondary = Color(0xFFFFC13D),
                    background = Color(0xFF0F0605),
                    surface = Color(0xFF1E100C),
                )
            CANDY ->
                darkColorScheme(
                    primary = Color(0xFFFF80AB),
                    secondary = Color(0xFF82B1FF),
                    background = Color(0xFF0C060B),
                    surface = Color(0xFF1D1120),
                )
            SLATE ->
                darkColorScheme(
                    primary = Color(0xFF90A4AE),
                    secondary = Color(0xFF78909C),
                    background = Color(0xFF090B0D),
                    surface = Color(0xFF14181B),
                )
            ROSE ->
                darkColorScheme(
                    primary = Color(0xFFFF7BA9),
                    secondary = Color(0xFFFFB7CE),
                    background = Color(0xFF120609),
                    surface = Color(0xFF221016),
                )
            MINT ->
                darkColorScheme(
                    primary = Color(0xFF5FE8B8),
                    secondary = Color(0xFFA8F2D5),
                    background = Color(0xFF04100B),
                    surface = Color(0xFF0E1F17),
                )
            COBALT ->
                darkColorScheme(
                    primary = Color(0xFF4D7CFE),
                    secondary = Color(0xFF8FB0FF),
                    background = Color(0xFF040816),
                    surface = Color(0xFF0D1530),
                )
            SAND ->
                darkColorScheme(
                    primary = Color(0xFFE8C572),
                    secondary = Color(0xFFD9B08C),
                    background = Color(0xFF12100A),
                    surface = Color(0xFF211D12),
                )
            GRAPE ->
                darkColorScheme(
                    primary = Color(0xFFB07BFF),
                    secondary = Color(0xFFD3B4FF),
                    background = Color(0xFF0C0614),
                    surface = Color(0xFF1A1026),
                )
            INK ->
                darkColorScheme(
                    primary = Color(0xFF9FB6C6),
                    secondary = Color(0xFF6E8494),
                    background = Color(0xFF000305),
                    surface = Color(0xFF0A0F14),
                )
            LIGHT ->
                lightColorScheme(
                    primary = Color(0xFF3355DD),
                    secondary = Color(0xFF5C6BC0),
                    background = Color(0xFFF6F7FB),
                    surface = Color(0xFFFFFFFF),
                )
            PAPER ->
                lightColorScheme(
                    primary = Color(0xFF8D6E63),
                    secondary = Color(0xFFA1887F),
                    background = Color(0xFFFAF6EF),
                    surface = Color(0xFFFFFDF8),
                )
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
    ;

    /** Radius scale for MaterialTheme.shapes (the crystal sheets use an
     *  8-32px radius scale; SHARP and PILL bracket it). */
    fun shapes(): androidx.compose.material3.Shapes {
        fun s(dp: Int) =
            androidx.compose.foundation.shape
                .RoundedCornerShape(dp.dp)
        return when (this) {
            SHARP ->
                androidx.compose.material3.Shapes(
                    extraSmall = s(2),
                    small = s(4),
                    medium = s(6),
                    large = s(8),
                    extraLarge = s(12),
                )
            ROUNDED ->
                androidx.compose.material3.Shapes(
                    extraSmall = s(8),
                    small = s(12),
                    medium = s(16),
                    large = s(20),
                    extraLarge = s(28),
                )
            PILL ->
                androidx.compose.material3.Shapes(
                    extraSmall = s(12),
                    small = s(18),
                    medium = s(24),
                    large = s(30),
                    extraLarge = s(40),
                )
        }
    }
}

/**
 * Selectable UI font/text color. AUTO keeps each theme's own text colors;
 * a fixed choice overrides the on-surface text slots of any theme.
 */
enum class FontColor(
    val label: String,
    val tint: Color?,
) {
    AUTO("Auto", null),
    FROST("Frost", Color(0xFFF2F6FF)),
    SILVER("Silver", Color(0xFFC9D2DE)),
    GOLD("Gold", Color(0xFFE8C97E)),
    ROSE("Rose", Color(0xFFF8CCD6)),
    CYAN("Cyan", Color(0xFF9FE8FF)),
    VIOLET("Violet", Color(0xFFD0B8FF)),
    MINT("Mint", Color(0xFF9FF0D2)),
    ;

    /** Applies the override to a theme's scheme (AUTO = untouched). */
    fun apply(base: ColorScheme): ColorScheme {
        val c = tint ?: return base
        return base.copy(
            onBackground = c,
            onSurface = c,
            onSurfaceVariant = c.copy(alpha = 0.78f),
            onSecondaryContainer = c,
        )
    }
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
    /** UI text color override; AUTO follows the theme. */
    val fontColor: FontColor = FontColor.AUTO,
)

/** Persists the chosen [AppTheme] in shared preferences. */
class ThemeStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("musicviz-prefs", Context.MODE_PRIVATE)

    // First-run default is the Kyanite crystal theme (the design-sheet look);
    // anyone who already picked a theme keeps their stored choice.
    fun load(): AppTheme =
        runCatching { AppTheme.valueOf(prefs.getString(KEY, AppTheme.KYANITE.name)!!) }
            .getOrDefault(AppTheme.KYANITE)

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
            fontColor =
                runCatching { FontColor.valueOf(prefs.getString("font_color", FontColor.AUTO.name)!!) }
                    .getOrDefault(FontColor.AUTO),
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
            .putString("font_color", gui.fontColor.name)
            .apply()
    }

    private companion object {
        const val KEY = "app_theme"
        const val KEY_POS = "gui_player_pos"
        const val KEY_CORNER = "gui_corner"
        const val KEY_OPACITY = "gui_opacity"
    }
}
