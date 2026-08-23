package dev.geode.ui

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.geode.R
import dev.geode.analysis.BeatTuning
import dev.geode.ui.theme.ThemePack
import dev.geode.ui.theme.ThemePackCatalog

object ThemeContrast {
    const val LIGHT_CONTRAST_MIN = 0.5f

    const val BODY_CONTRAST_MIN = 4.5f

    const val HINT_CONTRAST_MIN = 3.0f
}

fun ThemePack.resolvedFontColor(
    fontColorArgb: Int?,
    backgroundDim: Float = 0f,
): Int? {
    if (fontColorArgb == null || !isLight) return fontColorArgb
    val painted = Color(ColorDerive.dim(palette.background.toArgbInt(), backgroundDim))
    val diff = kotlin.math.abs(Color(fontColorArgb).luminance() - painted.luminance())
    return if (diff >= ThemeContrast.LIGHT_CONTRAST_MIN) fontColorArgb else null
}

fun ThemePack.fontColorActive(
    fontColorArgb: Int?,
    backgroundDim: Float = 0f,
): Boolean = resolvedFontColor(fontColorArgb, backgroundDim) != null

private fun Color.toArgbInt(): Int =
    ((alpha * 255f + 0.5f).toInt() shl 24) or
        ((red * 255f + 0.5f).toInt() shl 16) or
        ((green * 255f + 0.5f).toInt() shl 8) or
        (blue * 255f + 0.5f).toInt()

internal val LocalFontColor = staticCompositionLocalOf<Color?> { null }

@Composable
fun accentTextColor(): Color = LocalFontColor.current ?: MaterialTheme.colorScheme.primary

@Composable
fun onAccentTextColor(): Color {
    val cs = MaterialTheme.colorScheme
    if (LocalFontColor.current == null) return cs.onPrimary
    return if (cs.primary.luminance() > 0.55f) Color.Black else Color.White
}

enum class PlayerPosition(
    @StringRes val labelRes: Int,
) {
    TOP(R.string.look_position_top),
    BOTTOM(R.string.look_position_bottom),
}

enum class CornerStyle(
    @StringRes val labelRes: Int,
) {
    SHARP(R.string.look_corner_sharp),
    ROUNDED(R.string.look_corner_rounded),
    PILL(R.string.look_corner_pill),
}

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

data class GuiPrefs(
    val playerPosition: PlayerPosition = PlayerPosition.BOTTOM,
    val cornerStyle: CornerStyle = CornerStyle.ROUNDED,
    val barOpacity: Float = 0.72f,
    val beatSensitivity: Float = BeatTuning.SENSITIVITY_DEFAULT,
    val beatMinIntervalMs: Float = BeatTuning.INTERVAL_MS_DEFAULT,
    val presetMirrorUri: String? = null,
    val morphBeats: Int = 4,
    val accentIntensity: Float = 1f,
    val backgroundDim: Float = 0f,
    val compactPlayer: Boolean = false,
    val followSystemDark: Boolean = false,
    val clearVisualsMenu: Boolean = false,
    val whiteFont: Boolean = false,
    val fontColorArgb: Int? = null,
    val textScale: Float = 1f,
    /**
     * Whether the photosensitivity notice has been seen. It is an acknowledgement, not a choice:
     * the flash clamp is unconditional either way, so this only decides whether the notice shows.
     */
    val safetyAcknowledged: Boolean = false,
    /**
     * What the person said they came here to do, or null if first run has not asked yet. Null is
     * the "unasked" state rather than a default answer, so the question is asked exactly once.
     */
    val intent: UserIntent? = null,
    val reducedMotion: Boolean = false,
    val micReactive: Boolean = false,
    val touchSmear: Boolean = false,
    val touchSmearStrength: Float = 1f,
    val keyColor: Boolean = false,
    val secondScreen: Boolean = true,
    val touchTransform: Boolean = true,
) {
    val fontColorOverride: Int?
        get() = fontColorArgb ?: FontColorChoice.WHITE_ARGB.takeIf { whiteFont }

    val effectiveBeatMinIntervalMs: Float
        get() =
            dev.geode.render.VisualSafety
                .beatMinIntervalMs(beatMinIntervalMs)

    companion object {
        const val TEXT_SCALE_MIN = 0.85f
        const val TEXT_SCALE_MAX = 1.3f
    }
}

class ThemeStore(
    private val prefs: SharedPreferences,
) {
    fun load(): ThemePack = ThemePackCatalog.bySlug(migrateLegacyName(prefs.getString(KEY, null)))

    fun save(pack: ThemePack) {
        prefs.edit().putString(KEY, pack.slug).apply()
    }

    private fun migrateLegacyName(raw: String?): String? =
        when (raw) {
            "CLEAR_QUARTZ" -> "clear-quartz"
            "SUGILITE" -> "sugilite"
            else -> raw
        }

    fun loadGui(): GuiPrefs {
        val fontColor =
            when {
                prefs.contains(KEY_FONT_COLOR) -> prefs.getInt(KEY_FONT_COLOR, 0)
                prefs.getBoolean(KEY_WHITE_FONT, false) -> FontColorChoice.WHITE_ARGB
                else -> null
            }
        return GuiPrefs(
            playerPosition =
                runCatching { PlayerPosition.valueOf(prefs.getString(KEY_POS, null) ?: PlayerPosition.BOTTOM.name) }
                    .getOrDefault(PlayerPosition.BOTTOM),
            cornerStyle =
                runCatching { CornerStyle.valueOf(prefs.getString(KEY_CORNER, null) ?: CornerStyle.ROUNDED.name) }
                    .getOrDefault(CornerStyle.ROUNDED),
            barOpacity = prefs.getFloat(KEY_OPACITY, 0.72f),
            beatSensitivity =
                prefs
                    .getFloat("beat_sigma", BeatTuning.SENSITIVITY_DEFAULT)
                    .coerceIn(BeatTuning.SENSITIVITY_MIN, BeatTuning.SENSITIVITY_MAX),
            beatMinIntervalMs =
                prefs
                    .getFloat(KEY_BEAT_INTERVAL, BeatTuning.INTERVAL_MS_DEFAULT)
                    .coerceIn(BeatTuning.INTERVAL_MS_MIN, BeatTuning.INTERVAL_MS_MAX),
            presetMirrorUri = prefs.getString("preset_mirror_uri", null),
            morphBeats = prefs.getInt("morph_beats", 4),
            accentIntensity = prefs.getFloat(KEY_ACCENT, 1f),
            backgroundDim = prefs.getFloat(KEY_DIM, 0f),
            compactPlayer = prefs.getBoolean(KEY_COMPACT, false),
            followSystemDark = prefs.getBoolean(KEY_FOLLOW_DARK, false),
            clearVisualsMenu = prefs.getBoolean(KEY_CLEAR_VIZ_MENU, false),
            fontColorArgb = fontColor,
            textScale =
                prefs
                    .getFloat(KEY_TEXT_SCALE, 1f)
                    .coerceIn(GuiPrefs.TEXT_SCALE_MIN, GuiPrefs.TEXT_SCALE_MAX),
            safetyAcknowledged = loadSafetyAcknowledged(),
            intent =
                prefs
                    .getString(KEY_INTENT, null)
                    ?.let { stored -> UserIntent.entries.firstOrNull { it.name == stored } },
            reducedMotion = loadReducedMotion(),
            touchSmear = prefs.getBoolean(KEY_TOUCH_SMEAR, false),
            touchSmearStrength = prefs.getFloat(KEY_TOUCH_SMEAR_STRENGTH, 1f).coerceIn(0.2f, 2f),
            touchTransform = prefs.getBoolean(KEY_TOUCH_TRANSFORM, true),
            keyColor = prefs.getBoolean(KEY_KEY_COLOR, false),
            secondScreen = prefs.getBoolean(KEY_SECOND_SCREEN, true),
        )
    }

    /**
     * Anyone who answered the old three-way safety question has already been shown the notice, so
     * they are not asked again — the question itself is gone, but the acknowledgement carries over.
     */
    private fun loadSafetyAcknowledged(): Boolean =
        prefs.getBoolean(KEY_SAFETY_ACKNOWLEDGED, false) ||
            prefs.getString(KEY_SAFETY_CHOICE, null) != null

    /**
     * Reduced motion survives the same migration: it used to be one of the three answers, and it is
     * the only one of them that described something other than the flash clamp.
     */
    private fun loadReducedMotion(): Boolean =
        prefs.getBoolean(KEY_REDUCED_MOTION, false) ||
            prefs.getString(KEY_SAFETY_CHOICE, null) == LEGACY_CHOICE_REDUCED_MOTION

    fun saveGui(gui: GuiPrefs) {
        val fontColor = gui.fontColorOverride
        prefs
            .edit()
            .putString(KEY_POS, gui.playerPosition.name)
            .putString(KEY_CORNER, gui.cornerStyle.name)
            .putFloat(KEY_OPACITY, gui.barOpacity)
            .putFloat("beat_sigma", gui.beatSensitivity)
            .putFloat(KEY_BEAT_INTERVAL, gui.beatMinIntervalMs)
            .putString("preset_mirror_uri", gui.presetMirrorUri)
            .putInt("morph_beats", gui.morphBeats)
            .putFloat(KEY_ACCENT, gui.accentIntensity)
            .putFloat(KEY_DIM, gui.backgroundDim)
            .putBoolean(KEY_COMPACT, gui.compactPlayer)
            .putBoolean(KEY_FOLLOW_DARK, gui.followSystemDark)
            .putBoolean(KEY_CLEAR_VIZ_MENU, gui.clearVisualsMenu)
            .apply { if (fontColor != null) putInt(KEY_FONT_COLOR, fontColor) else remove(KEY_FONT_COLOR) }
            .remove(KEY_WHITE_FONT)
            .putFloat(KEY_TEXT_SCALE, gui.textScale)
            .putBoolean(KEY_SAFETY_ACKNOWLEDGED, gui.safetyAcknowledged)
            .apply {
                val intent = gui.intent
                if (intent != null) putString(KEY_INTENT, intent.name) else remove(KEY_INTENT)
            }.putBoolean(KEY_REDUCED_MOTION, gui.reducedMotion)
            .putBoolean(KEY_TOUCH_SMEAR, gui.touchSmear)
            .putFloat(KEY_TOUCH_SMEAR_STRENGTH, gui.touchSmearStrength)
            .putBoolean(KEY_TOUCH_TRANSFORM, gui.touchTransform)
            .putBoolean(KEY_KEY_COLOR, gui.keyColor)
            .putBoolean(KEY_SECOND_SCREEN, gui.secondScreen)
            .apply()
    }

    internal companion object {
        const val KEY_SAFETY_ACKNOWLEDGED = "gui_safety_acknowledged"
        const val KEY_INTENT = "gui_intent"

        /** Read-only now: the old three-way answer, kept solely to migrate existing installs. */
        const val KEY_SAFETY_CHOICE = "gui_safety_choice"
        const val LEGACY_CHOICE_REDUCED_MOTION = "REDUCED_MOTION"

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
        const val KEY_FONT_COLOR = "gui_font_color"
        const val KEY_TEXT_SCALE = "gui_text_scale"
        const val KEY_BEAT_INTERVAL = "beat_min_interval_ms"
        const val KEY_REDUCED_MOTION = "gui_reduced_motion"
        const val KEY_TOUCH_SMEAR = "gui_touch_smear"
        const val KEY_TOUCH_SMEAR_STRENGTH = "gui_touch_smear_strength"
        const val KEY_TOUCH_TRANSFORM = "gui_touch_transform"
        const val KEY_KEY_COLOR = "gui_key_color"
        const val KEY_SECOND_SCREEN = "gui_second_screen"
    }
}
