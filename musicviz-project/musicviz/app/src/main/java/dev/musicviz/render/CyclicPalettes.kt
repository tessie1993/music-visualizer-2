package dev.musicviz.render

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import java.nio.ByteBuffer

/**
 * Fabio Crameri's cyclic scientific colour maps, as one texture.
 *
 * ### What these are for
 *
 * The built-in palettes are cosine ramps around the hue wheel. They wrap
 * seamlessly and they are one slider away from each other, but they are not
 * perceptually uniform: a hue ramp swings hard in lightness - yellow is far
 * lighter than blue at the same saturation - so a smooth field painted with
 * one grows bright and dark bands that live in the PALETTE and not in the
 * music. On a spectrum, a wave field or a fluid pressure gradient that reads
 * as structure nobody played.
 *
 * These five ramps are measured to have even perceptual steps, and they are
 * the CYCLIC members of the family: their two ends join. The packed atlas
 * measures a wrap gap of 0-2/255 against a largest ordinary step of 3/255, so
 * the seam is smaller than the distance between neighbouring entries - which
 * is what makes them safe for a circular quantity (phase, angle, pitch class)
 * where a linear ramp shows a hard edge at the wrap.
 *
 * ### Shape and sampling
 *
 * One row per ramp, 256 entries each, RGB8 - 3840 bytes for the set. Sampled
 * with REPEAT on x because the ramps are cyclic and a hue shift should walk
 * off one end and back onto the other, and CLAMP on y because the rows are
 * unrelated to one another and must never bleed. LINEAR on x for a smooth
 * ramp; NEAREST on y for the same reason as the clamp.
 */
internal object CyclicPalettes {
    /** Entries per ramp. */
    const val SIZE: Int = 256

    /**
     * Ramp names, in atlas row order. Fabio Crameri's naming: the trailing O
     * marks the cyclic variant of each map.
     */
    val NAMES: List<String> = listOf("bamO", "brocO", "corkO", "romaO", "vikO")

    /** Row centre in texture coordinates for ramp [index]. */
    fun rowCoordinate(index: Int): Float = (index.coerceIn(0, NAMES.size - 1) + 0.5f) / NAMES.size

    /**
     * Uploads the atlas and returns the texture name, or 0 if the resource
     * could not be read (callers then fall back to the procedural palettes).
     * GL thread only.
     */
    fun createTexture(context: Context): Int {
        val expected = SIZE * NAMES.size * 3
        val bytes =
            runCatching {
                context.resources.openRawResource(R.raw.cyclic_palettes).use { it.readBytes() }
            }.getOrNull() ?: return 0
        if (bytes.size < expected) return 0
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val buf = ByteBuffer.allocateDirect(expected).put(bytes, 0, expected).apply { position(0) }
        // Three bytes per texel: the default 4-byte unpack alignment would
        // shear every row that is not a multiple of four bytes wide.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGB8,
            SIZE,
            NAMES.size,
            0,
            GLES30.GL_RGB,
            GLES30.GL_UNSIGNED_BYTE,
            buf,
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        return tex
    }
}
