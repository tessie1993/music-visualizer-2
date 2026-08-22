package dev.geode.render.scene

import android.opengl.GLES30
import dev.geode.R
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GlUtil {
    class ShaderCompileException(
        message: String,
    ) : RuntimeException(message)

    class FullscreenTriangle {
        var vao = 0
            private set
        private var vbo = 0

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

        fun bind() {
            GLES30.glBindVertexArray(vao)
        }

        fun unbind() {
            GLES30.glBindVertexArray(0)
        }

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

        fun forget() {
            vao = 0
            vbo = 0
        }
    }

    class UniformCache(
        val program: Int,
    ) {
        private val locations = HashMap<String, Int>()
        private val arraySizes = HashMap<String, Int>()

        fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

        fun arrayCount(
            name: String,
            declared: Int,
        ): Int =
            arraySizes
                .getOrPut(name) {
                    val index = IntArray(1)
                    GLES30.glGetUniformIndices(program, arrayOf("$name[0]"), index, 0)
                    if (index[0] == GLES30.GL_INVALID_INDEX) {
                        GLES30.glGetUniformIndices(program, arrayOf(name), index, 0)
                    }
                    if (index[0] == GLES30.GL_INVALID_INDEX) {
                        declared
                    } else {
                        val size = IntArray(1)
                        GLES30.glGetActiveUniformsiv(program, 1, index, 0, GLES30.GL_UNIFORM_SIZE, size, 0)
                        size[0].coerceAtLeast(1)
                    }
                }.coerceAtMost(declared)
    }

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
        for (unit in 0..7) GLES30.glBindSampler(unit, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private val INCLUDES: Map<String, Int> =
        mapOf(
            "lib_palette" to R.raw.lib_palette,
            "lib_psrdnoise2" to R.raw.lib_psrdnoise2,
            "lib_particle_common" to R.raw.lib_particle_common,
            "lib_particle_shade" to R.raw.lib_particle_shade,
        )

    private val INCLUDE_PATTERN = Regex("^[ \\t]*//#include[ \\t]+(\\w+)[ \\t]*$", RegexOption.MULTILINE)

    fun loadShader(
        context: android.content.Context,
        resId: Int,
    ): String {
        val source = context.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        return resolveIncludes(context, source)
    }

    fun resolveIncludes(
        context: android.content.Context,
        source: String,
    ): String =
        INCLUDE_PATTERN.replace(source) { match ->
            val name = match.groupValues[1]
            val resId = INCLUDES[name] ?: throw ShaderCompileException("unknown shader include '$name'")
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
