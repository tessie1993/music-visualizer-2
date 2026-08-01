package dev.musicviz.export

import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay

/**
 * A named starting point for a grade.
 *
 * A look does not become a mode: picking one WRITES the ordinary sliders and
 * then gets out of the way, exactly as the live-input profiles do for the
 * analyser. There is nothing to fall out of sync with, and disagreeing with a
 * look is just dragging a slider.
 */
enum class ClipLook(
    val label: String,
) {
    NONE("As shot"),
    PUNCH("Punch"),
    BLEACH("Bleach"),
    NEON("Neon"),
    NIGHT("Night"),
    MONO("Mono"),
    INVERT("Invert"),
    ;

    /** Returns [edit] with this look's values written into its grade. */
    fun applyTo(edit: ClipEdit): ClipEdit =
        edit.copy(
            brightness = brightness,
            contrast = contrast,
            saturation = saturation,
            hueDegrees = hue,
            monochrome = this == MONO,
            invert = this == INVERT,
        )

    private val brightness: Float
        get() =
            when (this) {
                PUNCH, MONO -> 0.02f
                BLEACH -> 0.06f
                NIGHT -> -0.14f
                else -> 0f
            }

    private val contrast: Float
        get() =
            when (this) {
                PUNCH -> 0.28f
                BLEACH -> 0.18f
                NEON -> 0.22f
                NIGHT -> 0.12f
                MONO -> 0.2f
                else -> 0f
            }

    private val saturation: Float
        get() =
            when (this) {
                PUNCH -> 30f
                BLEACH -> -55f
                NEON -> 65f
                NIGHT -> -15f
                else -> 0f
            }

    private val hue: Float
        get() =
            when (this) {
                NEON -> 14f
                NIGHT -> -18f
                else -> 0f
            }
}

/**
 * Everything the Studio can do to one clip, as plain data.
 *
 * Plain data on purpose: the edit is what the UI binds to, what a test can
 * assert about, and what [videoEffects] turns into a Media3 effect chain. No
 * GL object, encoder or Transformer is reachable from here.
 */
data class ClipEdit(
    /** Trim in-point. */
    val startMs: Long = 0L,
    /** Trim out-point; 0 means "to the end of the clip". */
    val endMs: Long = 0L,
    val look: ClipLook = ClipLook.NONE,
    /** -1..1, 0 = unchanged. */
    val brightness: Float = 0f,
    /** -1..1, 0 = unchanged. */
    val contrast: Float = 0f,
    /** -100..100 in HSL percent, 0 = unchanged. */
    val saturation: Float = 0f,
    /** -180..180 degrees around the colour wheel. */
    val hueDegrees: Float = 0f,
    val monochrome: Boolean = false,
    val invert: Boolean = false,
    /** 0.25..4; 1 = unchanged. Changes the clip's duration. */
    val speed: Float = 1f,
    /** Whole-frame rotation in degrees. */
    val rotationDegrees: Float = 0f,
    /** Reframe to this ratio, or null to keep the source's. */
    val ratio: ExportRatio? = null,
    /** Short side of the output when [ratio] reframes it. */
    val quality: ExportQuality = ExportQuality.FHD1080,
    val mute: Boolean = false,
    /** Burnt-in caption; blank for none. */
    val caption: String = "",
) {
    /** Duration of the trimmed section given the source's length. */
    fun trimmedMs(sourceDurationMs: Long): Long {
        val end = if (endMs > 0) endMs.coerceAtMost(sourceDurationMs) else sourceDurationMs
        return (end - startMs).coerceAtLeast(0L)
    }

    /** Duration of the OUTPUT: the trim, after the speed change. */
    fun outputMs(sourceDurationMs: Long): Long = (trimmedMs(sourceDurationMs) / speed.coerceAtLeast(0.01f)).toLong()

    /** True when this edit would change nothing and the export is a copy. */
    fun isIdentity(sourceDurationMs: Long): Boolean =
        startMs == 0L &&
            (endMs == 0L || endMs >= sourceDurationMs) &&
            brightness == 0f &&
            contrast == 0f &&
            saturation == 0f &&
            hueDegrees == 0f &&
            !monochrome &&
            !invert &&
            speed == 1f &&
            rotationDegrees == 0f &&
            ratio == null &&
            !mute &&
            caption.isBlank()

    /** The trim, in the shape Media3 wants it. */
    fun clipping(): MediaItem.ClippingConfiguration =
        MediaItem.ClippingConfiguration
            .Builder()
            .setStartPositionMs(startMs.coerceAtLeast(0L))
            .apply { if (endMs > startMs) setEndPositionMs(endMs) }
            .build()

    /**
     * The effect chain, in the order it has to run.
     *
     * Order is not cosmetic. Colour before geometry, so a grade is applied to
     * the picture rather than to the letterboxing. Presentation
     * (the reframe) after rotation, or a 90-degree turn would be cropped to
     * the pre-rotation frame. The caption last, so it is burnt on top of the
     * finished picture instead of being graded and cropped with it.
     *
     * Every stage is omitted when it would be a no-op: an empty chain is what
     * lets Transformer take its fast path and copy the encoded samples
     * through instead of re-encoding.
     */
    @UnstableApi
    fun videoEffects(): List<Effect> =
        buildList {
            if (brightness != 0f) add(Brightness(brightness))
            if (contrast != 0f) add(Contrast(contrast))
            if (saturation != 0f || hueDegrees != 0f) {
                add(
                    HslAdjustment
                        .Builder()
                        .adjustSaturation(saturation)
                        .adjustHue(hueDegrees)
                        .build(),
                )
            }
            if (monochrome) add(RgbFilter.createGrayscaleFilter())
            if (invert) add(RgbFilter.createInvertedFilter())
            if (rotationDegrees != 0f) {
                add(
                    ScaleAndRotateTransformation
                        .Builder()
                        .setRotationDegrees(rotationDegrees)
                        .build(),
                )
            }
            ratio?.let { r ->
                val aspect = ExportAspect.of(quality, r)
                // SCALE_TO_FIT_WITH_CROP fills the new frame rather than
                // pillarboxing it: reframing 16:9 to 9:16 for a phone screen
                // means cropping, and black bars down both sides is the one
                // result nobody wants from that.
                add(
                    Presentation.createForWidthAndHeight(
                        aspect.width,
                        aspect.height,
                        Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP,
                    ),
                )
            }
            caption.takeIf { it.isNotBlank() }?.let { text ->
                add(
                    OverlayEffect(
                        listOf(
                            TextOverlay.createStaticTextOverlay(
                                android.text.SpannableString(text),
                                StaticOverlaySettings
                                    .Builder()
                                    // Anchored to the bottom of the frame in
                                    // both spaces: the overlay's own bottom
                                    // edge against the frame's bottom edge,
                                    // lifted clear of the very edge.
                                    .setBackgroundFrameAnchor(0f, -0.82f)
                                    .setOverlayFrameAnchor(0f, -1f)
                                    .build(),
                            ),
                        ),
                    ),
                )
            }
        }

    /**
     * The speed change, as the one thing that retimes BOTH tracks.
     *
     * Deliberately not a video effect: `EditedMediaItem` rejects a
     * speed-changing effect when a provider is set, and the provider is the
     * one that also retimes the audio - a picture at 2x over sound at 1x is
     * not what "speed" means to anyone.
     */
    @UnstableApi
    fun speedProvider(): SpeedProvider? =
        if (speed == 1f) {
            null
        } else {
            object : SpeedProvider {
                override fun getSpeed(timeUs: Long): Float = speed

                // Never: one speed for the whole clip.
                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = androidx.media3.common.C.TIME_UNSET
            }
        }
}
