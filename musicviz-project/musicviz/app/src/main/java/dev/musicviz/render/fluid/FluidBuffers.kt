package dev.musicviz.render.fluid

import android.opengl.GLES30
import kotlin.math.roundToInt

/**
 * GL buffer plumbing for the fluid simulation, per FLUID_SIM v2 section 5:
 * an empirical half-float renderability probe with the R16F -> RG16F ->
 * RGBA16F fallback cascade, single and ping-pong FBOs with copy-preserving
 * resize, and the aspect-correct grid resolution helper.
 */
internal object FluidBuffers {
    data class TexFormat(val internal: Int, val format: Int, val type: Int)

    data class Formats(
        val r: TexFormat,
        val rg: TexFormat,
        val rgba: TexFormat,
        /** Renderable full-float RGBA, or null: used for particle state so
         *  positions don't quantise into visible clustering (spec 5.2). */
        val rgba32: TexFormat?,
        val ok: Boolean,
    )

    /**
     * ES 3.0 guarantees SAMPLING 16F but not RENDERING to it; probe each
     * candidate by attaching a 4x4 texture to an FBO and checking
     * completeness, falling back R16F -> RG16F -> RGBA16F per role.
     */
    fun probeFormats(): Formats {
        val rgba = TexFormat(GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT)
        val rg = TexFormat(GLES30.GL_RG16F, GLES30.GL_RG, GLES30.GL_HALF_FLOAT)
        val r = TexFormat(GLES30.GL_R16F, GLES30.GL_RED, GLES30.GL_HALF_FLOAT)

        fun renderable(f: TexFormat): Boolean {
            val tex = IntArray(1)
            val fbo = IntArray(1)
            GLES30.glGenTextures(1, tex, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, f.internal, 4, 4, 0, f.format, f.type, null)
            GLES30.glGenFramebuffers(1, fbo, 0)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                tex[0],
                0,
            )
            val ok = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fbo, 0)
            GLES30.glDeleteTextures(1, tex, 0)
            return ok
        }
        val rgba32 = TexFormat(GLES30.GL_RGBA32F, GLES30.GL_RGBA, GLES30.GL_FLOAT)
        val rgba32Ok = renderable(rgba32)
        val rgbaOk = renderable(rgba)
        val rgOk = renderable(rg)
        val rOk = renderable(r)
        val res =
            Formats(
                r =
                    if (rOk) {
                        r
                    } else if (rgOk) {
                        rg
                    } else {
                        rgba
                    },
                rg = if (rgOk) rg else rgba,
                rgba = rgba,
                rgba32 = if (rgba32Ok) rgba32 else null,
                ok = rgbaOk,
            )
        android.util.Log.i(
            "FluidSim",
            "fluid formats: R16F=${if (rOk) "ok" else "fb"} RG16F=${if (rgOk) "ok" else "fb"} " +
                "RGBA16F=${if (rgbaOk) "ok" else "MISSING"} RGBA32F=${if (rgba32Ok) "ok" else "no"}",
        )
        return res
    }

    /** Short side gets [res] texels; long side scales by aspect. */
    fun resolution(
        res: Int,
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return res to res
        val aspect = width.toFloat() / height
        return if (aspect >= 1f) {
            (res * aspect).roundToInt().coerceAtLeast(2) to res
        } else {
            res to (res / aspect).roundToInt().coerceAtLeast(2)
        }
    }

    internal class Fbo(
        val width: Int,
        val height: Int,
        private val fmt: TexFormat,
        private val linear: Boolean,
    ) {
        var fbo = 0
            private set
        var tex = 0
            private set

        fun create() {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            tex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            val filter = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, fmt.internal, width, height, 0, fmt.format, fmt.type, null)
            GLES30.glGenFramebuffers(1, ids, 0)
            fbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                tex,
                0,
            )
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }

        fun release() {
            if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            tex = 0
            fbo = 0
        }
    }

    internal class DoubleFbo(
        width: Int,
        height: Int,
        private val fmt: TexFormat,
        private val linear: Boolean,
    ) {
        var read = Fbo(width, height, fmt, linear)
            private set
        var write = Fbo(width, height, fmt, linear)
            private set

        val width: Int get() = read.width
        val height: Int get() = read.height

        fun create() {
            read.create()
            write.create()
        }

        fun swap() {
            val t = read
            read = write
            write = t
        }

        fun release() {
            read.release()
            write.release()
        }
    }
}
