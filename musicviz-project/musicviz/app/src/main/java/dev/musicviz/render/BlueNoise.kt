package dev.musicviz.render

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import java.nio.ByteBuffer

/**
 * The 64x64 blue-noise dither mask, shared by every pass that needs one.
 *
 * ### Why blue noise rather than the hashed white noise this replaces
 *
 * Both carry the same variance; they do not carry it in the same place. White
 * noise spreads its error flat across all spatial frequencies, so a good part
 * of it lands in the low frequencies the eye is most sensitive to and reads as
 * grain. Blue noise puts almost none there - the tile shipped here measures
 * ~144x more energy in its high-frequency bins than its low ones - so the eye's
 * own contrast sensitivity rolls the error off and what is left reads as a
 * clean gradient. That difference is the whole point of the technique, and it
 * is most visible in exactly this app's viewing condition: a dark room, an OLED
 * panel, and long smooth ramps (plasma, aurora, solar, the fluid pressure
 * field, every glow falloff).
 *
 * ### Why a raw .bin and not the upstream PNG
 *
 * The upstream asset (`64_64/LDR_LLL1_0.png`, CC0, see THIRD_PARTY_NOTICES) is
 * an RGBA PNG whose three colour channels carry the same mask. Decoding it on
 * device would mean a `BitmapFactory` call that premultiplies alpha by default
 * and can apply an sRGB decode - either of which silently destroys the spectral
 * property this exists for. The single channel is extracted at authoring time
 * into 4096 raw bytes instead: no decoder, no colour management, no premultiply,
 * and a quarter of the size.
 *
 * ### Sampling rules (get these wrong and the tile is worthless)
 *
 * - NEAREST, never LINEAR: interpolating between neighbouring samples averages
 *   the mask and drags its spectrum back down toward white.
 * - REPEAT, and sample at `gl_FragCoord.xy / 64.0` so one texel maps to one
 *   pixel whatever the render resolution.
 * - Amplitude of about one 8-bit step ([DITHER_AMOUNT]). Past that, invisible
 *   dither becomes visible grain, which also costs bitrate on export.
 * - Do NOT animate it per frame. A static mask is what stops banding; a mask
 *   that changes every frame turns fixed dither into temporal flicker, which is
 *   both uglier and a photosensitivity concern.
 */
internal object BlueNoise {
    /** Side of the tile, in texels. The mask is only periodic at this size. */
    const val SIZE: Int = 64

    /**
     * Dither amplitude in output units: one 8-bit step, applied as +-1/2 LSB.
     *
     * The composite pass writes to an 8-bit surface, so this is exactly the
     * quantization step it is fighting. Nothing here is a user control - the
     * effect is invisible when it is working, and a slider for it would only
     * offer the user a way to add grain.
     */
    const val DITHER_AMOUNT: Float = 1f / 255f

    /**
     * Uploads the mask and returns the texture name, or 0 if the resource
     * could not be read (callers then skip dithering rather than fail).
     * GL thread only.
     */
    fun createTexture(context: Context): Int {
        val bytes =
            runCatching {
                context.resources.openRawResource(R.raw.blue_noise_64).use { it.readBytes() }
            }.getOrNull() ?: return 0
        if (bytes.size < SIZE * SIZE) return 0
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        val buf = ByteBuffer.allocateDirect(SIZE * SIZE).put(bytes, 0, SIZE * SIZE).apply { position(0) }
        // One byte per texel: the default 4-byte unpack alignment would read
        // three bytes of the next row as padding on every row.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, SIZE, SIZE, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        return tex
    }
}
