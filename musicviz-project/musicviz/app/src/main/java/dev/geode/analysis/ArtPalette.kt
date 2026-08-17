package dev.geode.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a track's embedded artwork into a palette, so the visuals come out
 * the colour of the record.
 *
 * The extraction is a HUE HISTOGRAM rather than a mean or a k-means over RGB.
 * Averaging colour is the classic mistake here - a sleeve that is half orange
 * and half teal averages to grey, which is the one colour the artwork
 * certainly is not. A histogram keeps the modes, and the app's palettes are
 * defined as a base hue plus a span anyway, so hue is the axis that matters.
 *
 * Pixels are weighted by saturation and dropped when they are nearly grey or
 * nearly black: a sleeve is mostly background, and letting a large flat area
 * of near-white vote would hand back "no colour" for artwork that plainly has
 * one.
 *
 * Pure (an int array in, two floats out) so the whole judgement is testable
 * without decoding a bitmap.
 */
object ArtPalette {
    /** Hue histogram resolution. 36 bins = 10 degrees, finer than the eye
     *  distinguishes at a glance and coarse enough to be robust to dithering. */
    const val BINS = 36

    /** Below this saturation a pixel is grey and carries no hue opinion. */
    private const val MIN_SATURATION = 0.18f

    /** Outside this brightness band a pixel is shadow or blown highlight. */
    private const val MIN_VALUE = 0.12f
    private const val MAX_VALUE = 0.97f

    /**
     * Span (fraction of the hue wheel) reported for artwork whose colour sits
     * in a single bin. Not zero: a span of zero collapses every gradient in
     * the app to one flat tint, which no palette in the built-in table does.
     */
    private const val MIN_SPAN = 0.06f

    /** A palette lifted from artwork: where on the wheel, and how much of it. */
    data class Extracted(
        val baseHue: Float,
        val span: Float,
        /** Share of the sampled pixels that had a usable colour, 0..1. */
        val confidence: Float,
    )

    /**
     * Extracts a palette from ARGB [pixels], or null when the artwork carries
     * no colour worth using (a greyscale sleeve, a black square).
     *
     * Null rather than a grey palette: "this artwork has no colour" is a real
     * answer, and the caller's right response is to leave the user's palette
     * alone rather than to repaint the visuals grey.
     */
    fun extract(pixels: IntArray): Extracted? {
        if (pixels.isEmpty()) return null
        val weights = FloatArray(BINS)
        var coloured = 0
        for (argb in pixels) {
            val (h, s, v) = hsv(argb)
            if (s < MIN_SATURATION || v < MIN_VALUE || v > MAX_VALUE) continue
            coloured++
            // Saturation-weighted: a vivid accent outvotes a washed-out wash of
            // the same hue, which is how a person reads a sleeve too.
            weights[(h * BINS).toInt().coerceIn(0, BINS - 1)] += s * v
        }
        val confidence = coloured.toFloat() / pixels.size
        if (coloured == 0 || weights.all { it <= 0f }) return null

        val peak = weights.indices.maxByOrNull { weights[it] } ?: return null
        // The base hue is the peak bin's centre, refined by its neighbours so
        // the answer is not quantized to 10-degree steps.
        val left = weights[(peak - 1 + BINS) % BINS]
        val right = weights[(peak + 1) % BINS]
        val denom = left + weights[peak] + right
        val shift = if (denom > 0f) (right - left) / denom * 0.5f else 0f
        val baseHue = wrap01((peak + 0.5f + shift) / BINS)

        // The span covers every bin carrying a meaningful share of the weight,
        // measured as the widest arc from the peak - so a two-colour sleeve
        // reports a wide span and a monochrome one reports a narrow band.
        val total = weights.sum()
        val floor = total / BINS * 0.6f
        var reach = 0
        for (step in 1..BINS / 2) {
            val hot =
                weights[(peak + step) % BINS] >= floor ||
                    weights[(peak - step + BINS) % BINS] >= floor
            if (hot) reach = step
        }
        val span = max(MIN_SPAN, (reach * 2f + 1f) / BINS)
        return Extracted(baseHue, min(span, 1f), confidence)
    }

    /** ARGB -> (hue in [0,1), saturation, value), all normalized. */
    private fun hsv(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val d = hi - lo
        val h =
            when {
                d < 1e-6f -> 0f
                hi == r -> ((g - b) / d / 6f)
                hi == g -> ((b - r) / d + 2f) / 6f
                else -> ((r - g) / d + 4f) / 6f
            }
        val s = if (hi <= 0f) 0f else d / hi
        return Triple(wrap01(h), s, hi)
    }

    private fun wrap01(v: Float): Float {
        val x = v - kotlin.math.floor(v)
        return if (x >= 1f || abs(x) < 1e-7f) 0f else x
    }
}
