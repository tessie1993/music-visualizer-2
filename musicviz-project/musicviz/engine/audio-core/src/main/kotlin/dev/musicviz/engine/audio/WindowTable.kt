package dev.musicviz.engine.audio

import kotlin.math.cos

/** Window shapes the analysis graph uses. */
enum class WindowShape {
    /** Flat. Useful only for tests that want the raw frame. */
    RECTANGULAR,

    /** Hann, periodic — the STFT default. */
    HANN,
}

/**
 * A precomputed window, applied without allocating.
 *
 * ## Periodic, not symmetric
 *
 * `HANN` is `0.5 - 0.5 cos(2πi / size)`, dividing by `size` rather than
 * `size - 1`. That is the periodic form, and it is the one an STFT wants: with
 * a hop that divides the window, successive periodic Hann windows sum to a
 * constant, so overlapping frames neither ripple nor need correcting. The
 * symmetric form is for filter design.
 *
 * `:app`'s legacy `FftProcessor` uses the symmetric form. This does not change
 * it — that path is untouched — but the two will differ slightly in the last
 * bin or two, which is expected rather than a defect, and it is why the oracle
 * comparison uses these nodes rather than that one.
 */
class WindowTable(
    val size: Int,
    val shape: WindowShape = WindowShape.HANN,
) {
    init {
        require(size > 0) { "size must be positive, was $size" }
    }

    private val table =
        FloatArray(size) { i ->
            when (shape) {
                WindowShape.RECTANGULAR -> 1f
                WindowShape.HANN -> (0.5 - 0.5 * cos(2.0 * Math.PI * i / size)).toFloat()
            }
        }

    /** The coefficient at [index]; for tests and for documenting the shape. */
    fun coefficient(index: Int): Float = table[index]

    /**
     * Multiplies [size] samples of [source] starting at [sourceOffset] into
     * [out]. Reads outside [source] contribute zero, which is what makes a
     * frame near the start of a stream representable at all — its window
     * begins before sample zero by construction (see [FrameGrid]).
     */
    fun applyInto(
        source: FloatArray,
        sourceOffset: Int,
        out: FloatArray,
    ) {
        require(out.size >= size) { "out holds ${out.size}, window needs $size" }
        for (i in 0 until size) {
            val at = sourceOffset + i
            out[i] = if (at in source.indices) source[at] * table[i] else 0f
        }
    }
}
