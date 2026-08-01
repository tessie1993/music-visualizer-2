package dev.musicviz.render.scene

import android.opengl.GLES30
import dev.musicviz.R

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
     * Shader libraries a scene source can pull in with `//#include <name>`.
     *
     * GLSL has no include of its own, so every one of the 57 shaders here used
     * to be standalone - which meant shared code was shared by copying it. The
     * palette function lived in twenty scene shaders byte-for-byte identically,
     * so a change to how the app colours anything was a twenty-file edit that
     * nothing checked for drift.
     *
     * The map is explicit rather than resolved by name through
     * `Resources.getIdentifier`: a typo in an include then fails to compile
     * here with a readable message instead of silently resolving to nothing on
     * a device, and R8 can still see every resource that is actually used.
     */
    private val INCLUDES: Map<String, Int> =
        mapOf(
            "lib_palette" to R.raw.lib_palette,
            "lib_psrdnoise2" to R.raw.lib_psrdnoise2,
        )

    /** `//#include name` at the start of a line, with optional indentation. */
    private val INCLUDE_PATTERN = Regex("^[ \\t]*//#include[ \\t]+(\\w+)[ \\t]*$", RegexOption.MULTILINE)

    /**
     * Reads a shader and resolves its `//#include` directives.
     *
     * Deliberately NOT recursive and deliberately not a real preprocessor:
     * one level, no conditionals, no include guards, no parameters. Shader
     * libraries here are small leaf files, and a general preprocessor would be
     * a second language to debug at driver-compile time - where the only error
     * report is a line number in a file that no longer exists on disk.
     *
     * An unknown include is an error rather than a silent empty expansion,
     * because the failure mode of the latter is a shader that compiles
     * everywhere except where the missing function was called.
     */
    fun loadShader(
        context: android.content.Context,
        resId: Int,
    ): String {
        val source = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        return resolveIncludes(context, source)
    }

    /** Substitutes `//#include` directives in [source]. Visible for testing. */
    fun resolveIncludes(
        context: android.content.Context,
        source: String,
    ): String =
        INCLUDE_PATTERN.replace(source) { match ->
            val name = match.groupValues[1]
            val resId = INCLUDES[name] ?: throw ShaderCompileException("unknown shader include '$name'")
            // Escaped so a `$` or a backslash inside a library cannot be read
            // as a replacement reference by the regex engine.
            Regex.escapeReplacement(
                context.resources.openRawResource(resId).bufferedReader().use { it.readText() },
            )
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
