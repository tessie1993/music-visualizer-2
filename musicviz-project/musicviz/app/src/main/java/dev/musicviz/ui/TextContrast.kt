package dev.musicviz.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * WCAG contrast math and the small colour conversions the text-colour picker
 * needs, over plain ARGB `Int`s.
 *
 * Kept free of any android/androidx type - exactly like [ColorDerive], and for
 * the same reason: the Appearance option this backs is only defensible if the
 * legibility rule it enforces can be pinned in the headless JUnit gate, on
 * every theme, without a device.
 *
 * The two thresholds below are the whole accessibility story of the "Text
 * colour" option, so they are worth stating plainly:
 *
 *  - [MIN_LEGIBLE] is a HARD FLOOR. A chosen colour that falls under it
 *    against any surface the app writes on is not applied at all; the theme's
 *    own text colours stand. This is what stops black-on-black: the picker
 *    will let you choose it, but it says so, and the app never renders it.
 *  - [AA_TEXT] is ADVISORY. Between the two the colour IS applied and the
 *    picker warns that it is hard to read, because the alternative - refusing
 *    every colour that misses AA - would reject perfectly usable choices on
 *    the lighter dark themes (amber on Clear Quartz measures about 4:1) and
 *    turn an appearance setting into an argument.
 */
object TextContrast {
    /** WCAG 2.1 AA for normal-size body text. Advisory: below this we warn. */
    const val AA_TEXT = 4.5f

    /**
     * WCAG 2.1 AA for large text and for non-text UI. Used here as the hard
     * floor under which a chosen colour is refused outright - see the class
     * docs for why the floor is not [AA_TEXT].
     */
    const val MIN_LEGIBLE = 3.0f

    /** Highest ratio WCAG can express (black on white). */
    const val MAX_RATIO = 21f

    /** Opaque alpha, OR-ed onto every colour this app stores or renders. */
    val OPAQUE_ALPHA: Int = 0xFF shl 24

    /**
     * Relative luminance of an ARGB colour per WCAG 2.1, i.e. sRGB channels
     * linearised and weighted for the human eye. Alpha is ignored: every
     * colour this app puts text in is composited opaque first.
     *
     * NOT the same number as Compose's `Color.luminance()`, which the theme
     * code uses for "is this fill light or dark" eyeballing. This one has to
     * agree with what a contrast checker would report, so it is spelled out
     * here rather than borrowed.
     */
    fun relativeLuminance(argb: Int): Float {
        fun linear(shift: Int): Float {
            val c = ((argb ushr shift) and 0xFF) / 255f
            return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
        return 0.2126f * linear(16) + 0.7152f * linear(8) + 0.0722f * linear(0)
    }

    /** WCAG contrast ratio between two colours, 1 (identical) .. 21. */
    fun ratio(
        a: Int,
        b: Int,
    ): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
    }

    /**
     * The contrast of [text] against the WORST of [backdrops] - every surface
     * role the option repaints text onto. All-or-nothing is deliberate: a
     * colour that reads on the background but vanishes on a filled chip is not
     * a colour this app is willing to render as "your text colour", because
     * the user would have to hunt the app for the places it broke.
     */
    fun worstRatio(
        text: Int,
        backdrops: IntArray,
    ): Float = backdrops.minOfOrNull { ratio(text, it) } ?: MAX_RATIO

    /** True when [text] clears [MIN_LEGIBLE] on every one of [backdrops]. */
    fun isLegible(
        text: Int,
        backdrops: IntArray,
    ): Boolean = worstRatio(text, backdrops) >= MIN_LEGIBLE

    /**
     * The nearest colour to [text], on the straight line toward whichever of
     * white/black gains contrast fastest, that clears [MIN_LEGIBLE] on every
     * one of [backdrops]. Returns [text] unchanged when it already does, and
     * null when even the extreme fails - which happens on a theme that mixes a
     * near-white and a near-black surface, where no single text colour reads
     * on both.
     *
     * This is what the picker's "Nudge to legible" button applies. It exists so
     * that the hard floor is an offer rather than a wall: the user keeps the
     * hue they picked and the app takes it as far toward readable as it has to.
     */
    fun nearestLegible(
        text: Int,
        backdrops: IntArray,
    ): Int? {
        if (isLegible(text, backdrops)) return text
        val white = OPAQUE_ALPHA or 0xFFFFFF
        val black = OPAQUE_ALPHA
        val target = if (worstRatio(white, backdrops) >= worstRatio(black, backdrops)) white else black
        // Walked rather than solved: contrast against the worst of several
        // backdrops is not monotone in a closed form worth deriving, and 20
        // steps of an Int lerp is nothing at the once-per-tap rate this runs.
        for (step in 1..RAMP_STEPS) {
            val candidate = ColorDerive.lerpArgb(text, target, step.toFloat() / RAMP_STEPS)
            if (isLegible(candidate, backdrops)) return candidate
        }
        return null
    }

    /**
     * ARGB -> (hue, saturation, value), all 0..1, for seeding the picker's
     * sliders from whatever colour is currently in force. The inverse is
     * [PaletteStore.hueRgb], which the custom-palette editor already uses -
     * one HSV convention in the app, not two.
     */
    fun argbToHsv(argb: Int): FloatArray {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val span = hi - lo
        val hue =
            when {
                span == 0f -> 0f
                hi == r -> ((g - b) / span / 6f + 1f) % 1f
                hi == g -> ((b - r) / span + 2f) / 6f
                else -> ((r - g) / span + 4f) / 6f
            }
        return floatArrayOf(hue, if (hi == 0f) 0f else span / hi, hi)
    }

    /** (hue, saturation, value) all 0..1 -> opaque ARGB. */
    fun hsvToArgb(
        hue: Float,
        saturation: Float,
        value: Float,
    ): Int {
        val (r, g, b) = PaletteStore.hueRgb(hue, saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))

        fun ch(c: Float): Int = (c * 255f).roundToInt().coerceIn(0, 255)
        return OPAQUE_ALPHA or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** Six upper-case hex digits, no `#` - what the picker's field shows. */
    fun toHex(argb: Int): String = "%06X".format(argb and 0xFFFFFF)

    /**
     * "#1A2B3C" / "1a2b3c" / "#abc" -> opaque ARGB, or null if it is not a
     * colour yet. Null is the normal state while someone is still typing, so
     * the caller keeps the last good value rather than flashing black.
     */
    fun parseHex(text: String): Int? {
        val digits = text.trim().removePrefix("#")
        val rgb =
            when (digits.length) {
                // #abc is the CSS shorthand; each digit doubles.
                3 -> digits.map { "$it$it" }.joinToString("")
                6 -> digits
                else -> return null
            }
        if (!rgb.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return OPAQUE_ALPHA or rgb.toInt(16)
    }

    /** Steps [nearestLegible] walks from the chosen colour to the extreme. */
    private const val RAMP_STEPS = 20
}
