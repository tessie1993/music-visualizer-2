package dev.musicviz.render.scene

import android.opengl.GLES30
import dev.musicviz.R
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shader compile/link helpers with error capture for the in-app editor. */
object GlUtil {
    class ShaderCompileException(
        message: String,
    ) : RuntimeException(message)

    /**
     * The fullscreen-triangle geometry every offscreen pass here draws with:
     * one clip-space triangle big enough to cover the screen (no diagonal
     * seam, one vertex fewer than a quad), uploaded once into a VAO/VBO pair
     * with position as attribute 0. Seven render classes used to carry their
     * own copy of this bootstrap. GL thread only.
     */
    class FullscreenTriangle {
        var vao = 0
            private set
        private var vbo = 0

        /** Creates the VAO/VBO pair; leaves no VAO bound. */
        fun create() {
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0)
            vao = ids[0]
            GLES30.glGenBuffers(1, ids, 0)
            vbo = ids[0]
            val quad = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
            val buf =
                ByteBuffer
                    .allocateDirect(quad.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .put(quad)
                    .apply { position(0) }
            GLES30.glBindVertexArray(vao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
            GLES30.glBindVertexArray(0)
        }

        /** Binds the VAO for a run of passes; pair with [unbind]. */
        fun bind() {
            GLES30.glBindVertexArray(vao)
        }

        fun unbind() {
            GLES30.glBindVertexArray(0)
        }

        /**
         * One whole pass: bind, draw the triangle, unbind. Creates the
         * geometry on first use for callers that draw lazily.
         */
        fun draw() {
            if (vao == 0) create()
            GLES30.glBindVertexArray(vao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBindVertexArray(0)
        }

        fun release() {
            if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
            if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            vbo = 0
            vao = 0
        }

        /**
         * Drops handles from a lost EGL context: dead names, never valid
         * again, so they are forgotten rather than deleted.
         */
        fun forget() {
            vao = 0
            vbo = 0
        }
    }

    /**
     * A linked [program] and the uniform locations resolved against it.
     * Caching the lookups is worth an object: dozens of glGetUniformLocation
     * calls per frame are measurable driver overhead on mobile GPUs.
     *
     * The cache travels WITH the program rather than living in a map keyed by
     * the GL name, because a name is not an identity: glDeleteProgram frees it
     * and the next glCreateProgram is free to hand the same number straight
     * back, at which point locations cached under that key point into some
     * other program's slots - a sampler on the wrong unit, a uniform that
     * never moves, no GL error anywhere to trace it from. Tying the two
     * together makes that class of bug unrepresentable: dropping the program
     * drops its locations because they are the same object.
     */
    class UniformCache(
        val program: Int,
    ) {
        private val locations = HashMap<String, Int>()

        fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }
    }

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
        // no GL error). Unbind samplers on every unit the pipeline uses -
        // through unit 4, where the composite's blue-noise dither depends on
        // NEAREST/REPEAT texture state (BlueNoise) that a leaked sampler
        // would override - and clear both pixel-buffer binding points.
        for (unit in 0..7) GLES30.glBindSampler(unit, 0)
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
            "lib_particle_common" to R.raw.lib_particle_common,
            "lib_particle_shade" to R.raw.lib_particle_shade,
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

    /**
     * [buildProgram] with the failure path every scene shares: a
     * driver-rejected shader must degrade the style, never throw on the GL
     * thread - every scene is init()ed before the user has picked one, so an
     * exception out of one build would take the whole visualizer down on
     * launch. Reports through [onError] and returns 0; callers gate their GL
     * setup on the returned handle.
     */
    fun buildProgramReporting(
        vertexSrc: String,
        fragmentSrc: String,
        onError: (String?) -> Unit,
    ): Int =
        try {
            buildProgram(vertexSrc, fragmentSrc)
        } catch (e: ShaderCompileException) {
            onError(e.message)
            0
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
