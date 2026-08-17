package dev.geode.ui

import kotlin.math.roundToInt

/**
 * Pure ARGB-Int color math used to derive the full Material color scheme of
 * each [AppTheme] from its four anchor colors, and to apply the user's
 * accent-intensity / background-dim appearance preferences. Kept free of any
 * android/androidx types so it runs in the headless JUnit gate.
 */
object ColorDerive {
    /** Per-channel linear interpolation from [from] to [to]; [t] is clamped to 0..1. */
    fun lerpArgb(
        from: Int,
        to: Int,
        t: Float,
    ): Int {
        val f = t.coerceIn(0f, 1f)

        fun ch(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return (a + (b - a) * f).roundToInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Scales color saturation around the pixel's luma: factor 1 is identity,
     * < 1 desaturates toward gray, > 1 pushes channels away from gray
     * (clamped). Alpha is preserved.
     */
    fun scaleSaturation(
        argb: Int,
        factor: Float,
    ): Int {
        if (factor == 1f) return argb
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val luma = 0.299f * r + 0.587f * g + 0.114f * b

        fun ch(c: Int): Int = (luma + (c - luma) * factor).roundToInt().coerceIn(0, 255)
        return (argb and 0xFF000000.toInt()) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** Darkens RGB by [amount] (0 = identity, 1 = black); alpha is preserved. */
    fun dim(
        argb: Int,
        amount: Float,
    ): Int {
        val k = 1f - amount.coerceIn(0f, 1f)

        fun ch(shift: Int): Int = (((argb ushr shift) and 0xFF) * k).roundToInt().coerceIn(0, 255)
        return (argb and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
