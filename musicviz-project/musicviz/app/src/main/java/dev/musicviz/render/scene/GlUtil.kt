package dev.musicviz.render.scene

import android.opengl.GLES30

/** Shader compile/link helpers with error capture for the in-app editor. */
object GlUtil {
    class ShaderCompileException(
        message: String,
    ) : RuntimeException(message)

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
        // Native projectM also leaks sampler objects and PBO bindings: a
        // leaked sampler silently overrides filtering (and wrap - REPEAT!)
        // for every fluid/composite texture fetch on that unit, and a leaked
        // pack PBO redirects FlowField.readback()'s glReadPixels into the
        // stale buffer object instead of client memory (corrupt readbacks,
        // no GL error). Unbind samplers on the units the pipeline uses and
        // clear both pixel-buffer binding points.
        for (unit in 0..3) GLES30.glBindSampler(unit, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /**
     * Splices [chunk] into [source] immediately after its `#version` line.
     *
     * GLSL ES has no `#include`, and `#version` must be the first thing in a
     * shader, so a shared library of functions can only be a plain text splice
     * done here. Everything after the version line - precision qualifiers
     * included - is ordinary declaration order, which is why the chunk carries
     * its own `precision highp float;`: its function bodies are compiled
     * BEFORE the includer's own precision statement is reached.
     *
     * A source with no `#version` (never true of this app's shaders, but the
     * in-app GLSL editor can hand over anything) is returned untouched rather
     * than being corrupted by a prepend.
     */
    fun withChunk(
        source: String,
        chunk: String,
    ): String {
        val version = source.indexOf("#version")
        if (version < 0) return source
        val eol = source.indexOf('\n', version)
        if (eol < 0) return source
        return source.substring(0, eol + 1) + chunk + "\n" + source.substring(eol + 1)
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
