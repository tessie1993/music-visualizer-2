package dev.geode.export

/**
 * A named render target: the four settings a platform expects, together.
 *
 * Nothing here is new state. [quality], [ratio], [fps] and [loopSafe] are the
 * four controls the Export tab already has and already persists, and a preset is
 * only a name for one combination of them - so selecting one leaves the settings
 * fully editable afterwards, and the render path does not know presets exist.
 */
data class ExportPreset(
    val name: String,
    val quality: ExportQuality,
    val ratio: ExportRatio,
    val fps: Int,
    val loopSafe: Boolean,
)

/**
 * The named render targets offered above the Export defaults.
 *
 * Four controls, six ratios, three qualities and two frame rates is 36 shapes to
 * pick between for a job the user describes to themselves in one word. These are
 * the combinations worth a word, and each is a genuinely different answer - no
 * two presets carry the same four values, which is why TikTok and Reels share
 * one entry rather than getting a chip each.
 *
 *  - **TikTok** — 9:16 at 30 fps, which is also Reels and any other vertical
 *    short-form feed.
 *  - **Shorts** — the same shape at 60 fps, which YouTube accepts and the
 *    others re-encode.
 *  - **Feed** — 4:5, the tallest an Instagram or Facebook feed post shows
 *    without cropping it.
 *  - **Square** — 1:1.
 *  - **YouTube** — 16:9 at 60 fps.
 *  - **Master** — 16:9 at 4K, for an upload the platform will re-encode anyway
 *    or an archive copy.
 *
 * `loopSafe` follows the shape rather than the platform: the feeds that autoplay
 * a clip on repeat are exactly the vertical and square ones, and trimming to a
 * bar boundary is what stops the repeat from stuttering. 21:9 has no entry
 * because its only interesting quality tier is 4K, whose long side is past what
 * the AVC encoder accepts today.
 */
object ExportPresets {
    val ALL: List<ExportPreset> =
        listOf(
            ExportPreset("TikTok", ExportQuality.FHD1080, ExportRatio.R9_16, fps = 30, loopSafe = true),
            ExportPreset("Shorts", ExportQuality.FHD1080, ExportRatio.R9_16, fps = 60, loopSafe = true),
            ExportPreset("Feed", ExportQuality.FHD1080, ExportRatio.R4_5, fps = 30, loopSafe = true),
            ExportPreset("Square", ExportQuality.FHD1080, ExportRatio.R1_1, fps = 30, loopSafe = true),
            ExportPreset("YouTube", ExportQuality.FHD1080, ExportRatio.R16_9, fps = 60, loopSafe = false),
            ExportPreset("Master", ExportQuality.UHD4K, ExportRatio.R16_9, fps = 60, loopSafe = false),
        )

    /**
     * The preset these settings exactly are, or null when they are hand-tuned.
     *
     * All four have to agree. Matching on three would light a chip that
     * misdescribes the render the user is about to start - turning loop-safe off
     * under a lit TikTok chip has to unlight it.
     */
    fun matching(
        quality: ExportQuality,
        ratio: ExportRatio,
        fps: Int,
        loopSafe: Boolean,
    ): ExportPreset? = ALL.firstOrNull { it.isFor(quality, ratio, fps, loopSafe) }

    /** [matching] as an index into [ALL], or -1 - the shape a selector wants. */
    fun indexMatching(
        quality: ExportQuality,
        ratio: ExportRatio,
        fps: Int,
        loopSafe: Boolean,
    ): Int = ALL.indexOfFirst { it.isFor(quality, ratio, fps, loopSafe) }

    private fun ExportPreset.isFor(
        quality: ExportQuality,
        ratio: ExportRatio,
        fps: Int,
        loopSafe: Boolean,
    ): Boolean = this.quality == quality && this.ratio == ratio && this.fps == fps && this.loopSafe == loopSafe
}
