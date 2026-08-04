package dev.musicviz.ui

import android.content.Context
import dev.musicviz.export.ExportQuality
import dev.musicviz.export.ExportRatio

/**
 * The remembered export choices: quality tier, frame rate, aspect ratio and
 * the loop-safe trim. The Settings › Export tab edits these as standing
 * defaults, and the export dialog starts from them and writes its own changes
 * back - so the dialog always opens the way the last render was set up,
 * instead of resetting to 1080p/60 every time.
 *
 * The DESTINATION is deliberately absent: each export picks it at render time
 * (Videos library, or a folder chosen through the system picker), so there is
 * no standing destination to remember.
 */
data class ExportDefaults(
    val quality: ExportQuality = ExportQuality.FHD1080,
    val fps: Int = 60,
    val ratio: ExportRatio = ExportRatio.R16_9,
    val loopSafe: Boolean = false,
)

/** The chip label for a quality tier ("720p", "1080p", "4K"). */
internal fun exportQualityLabel(quality: ExportQuality): String =
    when (quality) {
        ExportQuality.HD720 -> "720p"
        ExportQuality.FHD1080 -> "1080p"
        ExportQuality.UHD4K -> "4K"
    }

/** Persists [ExportDefaults] in shared preferences (same pattern as ThemeStore). */
class ExportPrefsStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("musicviz-prefs", Context.MODE_PRIVATE)

    fun load(): ExportDefaults {
        val d = ExportDefaults()
        return ExportDefaults(
            quality =
                runCatching { ExportQuality.valueOf(prefs.getString(KEY_QUALITY, d.quality.name)!!) }
                    .getOrDefault(d.quality),
            // The renderer only offers the two rates; anything else stored
            // (or hand-edited) snaps back to the default rather than asking
            // the encoder for a rate the UI cannot show.
            fps = prefs.getInt(KEY_FPS, d.fps).let { if (it == 30) 30 else 60 },
            ratio =
                runCatching { ExportRatio.valueOf(prefs.getString(KEY_RATIO, d.ratio.name)!!) }
                    .getOrDefault(d.ratio),
            loopSafe = prefs.getBoolean(KEY_LOOP, d.loopSafe),
        )
    }

    fun save(d: ExportDefaults) {
        prefs
            .edit()
            .putString(KEY_QUALITY, d.quality.name)
            .putInt(KEY_FPS, d.fps)
            .putString(KEY_RATIO, d.ratio.name)
            .putBoolean(KEY_LOOP, d.loopSafe)
            .apply()
    }

    private companion object {
        const val KEY_QUALITY = "export_quality"
        const val KEY_FPS = "export_fps"
        const val KEY_RATIO = "export_ratio"
        const val KEY_LOOP = "export_loop"
    }
}
