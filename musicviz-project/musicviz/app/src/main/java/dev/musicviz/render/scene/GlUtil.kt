package dev.musicviz.render.scene

import android.opengl.GLES30

/** Shader compile/link helpers with error capture for the in-app editor. */
object GlUtil {
    class ShaderCompileException(message: String) : RuntimeException(message)

    /**
     * Resets the mutable GL state the render pipeline assumes but never sets
     * per-pass: scissor/stencil/depth/cull toggles, write masks and the blend
     * EQUATION (scenes restore blend enable + func, but never the equation).
     * The libprojectM native render runs an arbitrary preset pipeline and is
     * free to leave any of these dirty - a leaked scissor rect silently clips
     * every subsequent FBO pass (fluid grids included) and a MIN/MAX blend
     * equation corrupts every blended draw, with zero GL errors to trace.
     * Call at the top of every frame and after any native/external render.
     */
    fun resetFrameState() {
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_STENCIL_TEST)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glDisable(GLES30.GL_SAMPLE_ALPHA_TO_COVERAGE)
        GLES30.glColorMask(true, true, true, true)
        GLES30.glDepthMask(true)
        GLES30.glStencilMask(-1)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    fun buildProgram(
        vertexSrc: String,
        fragmentSrc: String,
    ): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertexSrc)
        val fs =
            try {
                compile(GLES30.GL_FRAGMENT_SHADER, fragmentSrc)
            } catch (e: ShaderCompileException) {
                GLES30.glDeleteShader(vs)
                throw e
            }
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw ShaderCompileException("Link failed: $log")
        }
        return prog
    }

    fun compile(
        type: Int,
        src: String,
    ): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw ShaderCompileException(log)
        }
        return shader
    }
}
