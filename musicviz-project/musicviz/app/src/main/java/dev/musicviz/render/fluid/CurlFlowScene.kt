package dev.musicviz.render.fluid

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.scene.GlUtil
import dev.musicviz.render.scene.Scene
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams

/**
 * Curl-noise flow-field particle scene (docs/ORGANIC_MOTION.md quick-win 2,
 * Bridson SIGGRAPH 2007): a small velocity texture is regenerated each frame
 * as the curl of a time-evolving FBM potential - divergence-free, so the
 * particle streams swirl without ever clumping - and the existing GPU
 * particle layer rides it. Music mapping per the report's conventions:
 * mids drive how fast the field morphs, treble gains the fine-turbulence
 * octave, beats kick field amplitude and particle brightness (impulse +
 * exponential release). Existing Customize controls map on: Speed = morph
 * rate, Turbulence = spatial frequency, Audio drive = flow strength,
 * Particle size/Palette/Hue range = rendering.
 */
internal class CurlFlowScene(private val context: Context) : Scene {
    override val id: String = SceneIds.CURLFLOW

    private val particles = FluidParticles(context)
    private lateinit var formats: FluidBuffers.Formats
    private var field: FluidBuffers.Fbo? = null
    private var fieldProgram = 0
    private var quadVao = 0
    private var quadVbo = 0
    private var params = SceneParams()
    private var pending: AudioFeatures? = null
    private var lastDt = 1f / 60f
    private var noiseTime = 0f
    private var beatEnv = 0f
    private var aspect = 1f
    private var available = false

    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    override fun init() {
        release()
        formats = FluidBuffers.probeFormats()
        available = formats.ok
        if (!available) return
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        quadVao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        val quad = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val buf =
            java.nio.ByteBuffer.allocateDirect(quad.size * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(quad).apply { position(0) }
        GLES30.glBindVertexArray(quadVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindVertexArray(0)
        // Compile failure must degrade the style, never crash the GL thread.
        try {
            fieldProgram =
                GlUtil.buildProgram(
                    context.resources.openRawResource(R.raw.fluid_base_vert).bufferedReader().use { it.readText() },
                    context.resources.openRawResource(R.raw.curl_field_frag).bufferedReader().use { it.readText() },
                )
        } catch (e: GlUtil.ShaderCompileException) {
            android.util.Log.w("FluidSim", "curl field shader rejected by driver: ${e.message}")
            release()
            return
        }
        particles.create(49_152, formats)
        if (!particles.available) {
            release()
        }
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        if (!available) return
        aspect = width.toFloat() / height.coerceAtLeast(1)
        field?.release()
        val (fw, fh) = FluidBuffers.resolution(64, width, height)
        field = FluidBuffers.Fbo(fw, fh, formats.rg, linear = false).also { it.create() }
        particles.invalidateSeed()
    }

    override fun setParams(p: SceneParams) {
        params = p
    }

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        pending = features
        lastDt = dt.coerceIn(0f, 1f / 30f)
    }

    override fun draw(timeSeconds: Float) {
        if (!available) return
        val fld = field ?: return
        val f = pending
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)

        if (f != null) {
            beatEnv = if (f.beat) 1f else beatEnv * kotlin.math.exp(-lastDt / 0.35f)
            noiseTime += lastDt * (0.15f + f.mid * 1.4f) * params.speed.coerceIn(0.1f, 2f)

            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glBindVertexArray(quadVao)
            GLES30.glUseProgram(fieldProgram)
            GLES30.glUniform2f(GLES30.glGetUniformLocation(fieldProgram, "uInvRes"), 1f / fld.width, 1f / fld.height)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(fieldProgram, "uAspect"), aspect)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(fieldProgram, "uTime"), noiseTime)
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(fieldProgram, "uFreq"),
                1.2f * (0.5f + params.turbulence.coerceIn(0.1f, 2f)),
            )
            GLES30.glUniform1f(GLES30.glGetUniformLocation(fieldProgram, "uDetail"), (f.treble * 3f).coerceIn(0f, 1.5f))
            GLES30.glUniform1f(
                GLES30.glGetUniformLocation(fieldProgram, "uAmp"),
                0.55f * params.audioDrive.coerceIn(0.2f, 2f) * (1f + beatEnv * 0.9f),
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fld.fbo)
            GLES30.glViewport(0, 0, fld.width, fld.height)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBindVertexArray(0)

            // Field speeds are already sim units/s -> flowScale 1. Respawn
            // 0.25/s: fresh origin points appear continuously (~4 s full
            // turnover) instead of the one-time seed slowly filamenting.
            particles.step(lastDt, fld.tex, aspect, 1f, respawnRate = 0.25f, timeSeconds = noiseTime)
            pending = null
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        particles.draw(
            aspect,
            params.particleSize.coerceIn(0.4f, 4f),
            params.paletteBase,
            params.hueRange.coerceIn(0.1f, 1f),
            // Beat response lives in the FIELD kick (uAmp); keeping the
            // brightness pulse gentle stops the compound amp+brightness+size
            // jump from reading as a strobe on busy tracks.
            (0.85f + beatEnv * 0.35f) * params.intensity.coerceIn(0.2f, 2f),
        )
    }

    override fun release() {
        particles.release()
        field?.release()
        field = null
        if (fieldProgram != 0) GLES30.glDeleteProgram(fieldProgram)
        fieldProgram = 0
        if (quadVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
        if (quadVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
        quadVbo = 0
        quadVao = 0
        available = false
    }
}
