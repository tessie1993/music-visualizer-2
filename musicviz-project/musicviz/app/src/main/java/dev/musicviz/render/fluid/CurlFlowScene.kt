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
 * Curl-noise flow-field particle scene, rebuilt on the lifecycle particle
 * layer: a velocity texture is regenerated each frame as the curl of a
 * time-evolving FBM potential (Bridson SIGGRAPH 2007 - divergence-free, so
 * the streams swirl without clumping) and the particles ride it - but
 * births, captures and recycling follow the SAME [FluidChoreography]
 * spawn/catch progression as the fluid scene, so the streams visibly
 * journey through the track. Catch attraction composes with the curl field:
 * divergence-free flow plus explicit sinks gives swirl AND convergence,
 * which pure curl noise cannot do.
 *
 * Music mapping: mids drive field morph rate, treble gains the fine
 * turbulence octave, beats kick field amplitude and brightness (impulse +
 * exponential release), bass pulls toward the catch points. Existing
 * Customize controls map on: Speed = morph rate, Turbulence = spatial
 * frequency, Audio drive = flow strength, Particle size/Palette/Hue range =
 * rendering (the palette span via [FluidHue], shared with the other fluid
 * styles), Trails/Trail length = canvas persistence (via [CurlFlowMath]),
 * Particle drag/Particle life = the lifecycle layer, plus the shared fluid
 * spawn/catch params. Grading and hue rotation (Brightness, Intensity,
 * Contrast, Gamma, Hue shift, Zoom, Rotation) belong to the composite pass,
 * which grades this style - the scene must not apply them a second time.
 */
internal class CurlFlowScene(
    private val context: Context,
) : Scene {
    override val id: String = SceneIds.CURLFLOW

    private val particles = FluidParticles(context)
    private val choreography = FluidChoreography()
    private lateinit var formats: FluidBuffers.Formats
    private var field: FluidBuffers.Fbo? = null
    private var fieldProgram = 0
    private val fieldUniforms = HashMap<String, Int>()
    private var quadVao = 0
    private var quadVbo = 0
    private var params = SceneParams()
    private var pending: AudioFeatures? = null
    private var lastDt = 1f / 60f
    private var noiseTime = 0f
    private var wallTime = 0f
    private var beatEnv = 0f
    private var aspect = 1f
    private var available = false

    private val spawnPack = FloatArray(FluidChoreography.MAX_SPAWN * 4)
    private val catchPack = FloatArray(FluidChoreography.MAX_CATCH * 4)
    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    override fun init() {
        release()
        formats = FluidBuffers.probeFormats()
        available = formats.ok
        if (!available) return
        choreography.reset()
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        quadVao = ids[0]
        GLES30.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        val quad = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val buf =
            java.nio.ByteBuffer
                .allocateDirect(quad.size * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(quad)
                .apply { position(0) }
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
                    context.resources
                        .openRawResource(R.raw.fluid_base_vert)
                        .bufferedReader()
                        .use { it.readText() },
                    context.resources
                        .openRawResource(R.raw.curl_field_frag)
                        .bufferedReader()
                        .use { it.readText() },
                )
        } catch (e: GlUtil.ShaderCompileException) {
            android.util.Log.w("FluidSim", "curl field shader rejected by driver: ${e.message}")
            release()
            return
        }
        fieldUniforms.clear()
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
        // LINEAR filtering (core ES3 for half-float SAMPLING) smooths the
        // per-cell velocity quantization that banded the old NEAREST field.
        val (fw, fh) = FluidBuffers.resolution(96, width, height)
        field = FluidBuffers.Fbo(fw, fh, formats.rg, linear = true).also { it.create() }
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

    private fun loc(name: String): Int = fieldUniforms.getOrPut(name) { GLES30.glGetUniformLocation(fieldProgram, name) }

    override fun draw(timeSeconds: Float) {
        if (!available) return
        val fld = field ?: return
        val f = pending
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)

        if (f != null) {
            wallTime += lastDt
            beatEnv = if (f.beat) 1f else beatEnv * kotlin.math.exp(-lastDt / 0.35f)
            noiseTime += lastDt * (0.15f + f.mid * 1.4f) * params.speed.coerceIn(0.1f, 2f)

            // Shared spawn/catch progression: same params as the fluid scene.
            choreography.path = params.fluidSpawnPath.coerceIn(0, FluidChoreography.PATH_LABELS.size - 1)
            choreography.spawnCount = params.fluidSpawnPoints.coerceIn(1, FluidChoreography.MAX_SPAWN)
            choreography.catchCount = params.fluidCatchPoints.coerceIn(0, FluidChoreography.MAX_CATCH)
            choreography.progressionAmount = params.fluidSpawnProgress.coerceIn(0f, 1f)
            choreography.speed = params.speed.coerceIn(0.1f, 2f)
            choreography.tick(f, lastDt, aspect)

            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glBindVertexArray(quadVao)
            GLES30.glUseProgram(fieldProgram)
            GLES30.glUniform2f(loc("uInvRes"), 1f / fld.width, 1f / fld.height)
            GLES30.glUniform1f(loc("uAspect"), aspect)
            GLES30.glUniform1f(loc("uTime"), noiseTime)
            GLES30.glUniform1f(loc("uFreq"), 1.2f * (0.5f + params.turbulence.coerceIn(0.1f, 2f)))
            GLES30.glUniform1f(loc("uDetail"), (f.treble * 3f).coerceIn(0f, 1.5f))
            GLES30.glUniform1f(
                loc("uAmp"),
                0.55f * params.audioDrive.coerceIn(0.2f, 2f) * (1f + beatEnv * 0.9f),
            )
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fld.fbo)
            GLES30.glViewport(0, 0, fld.width, fld.height)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glBindVertexArray(0)

            // Field speeds are already sim units/s -> flowScale 1. Lifecycle
            // recycling (ttl + catch capture) replaces the old stochastic
            // respawn: fresh births continuously appear AT the progressing
            // spawn points, and captures drain into the catch points.
            particles.drag = params.fluidParticleDrag.coerceIn(0.02f, 1f)
            particles.life = params.fluidParticleLife.coerceIn(1f, 20f)
            choreography.packSpawns(spawnPack)
            choreography.packCatches(
                catchPack,
                pull = params.fluidCatchPull.coerceIn(0f, 3f),
                captureRadius = params.fluidCatchRadius.coerceIn(0.03f, 0.3f),
            )
            particles.setChoreography(spawnPack, choreography.spawnCount, catchPack, choreography.catchCount)
            // Wall-clock time for the respawn hash: noiseTime nearly freezes
            // in quiet passages, which froze the old respawn gate.
            particles.step(lastDt, fld.tex, aspect, 1f, timeSeconds = wallTime)
            pending = null
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        val dpiScale = (prevViewport[3].coerceAtLeast(1) / 1080f).coerceIn(0.75f, 2.5f)
        // Colour split, shared by the whole fluid family: the SCENE owns
        // palette identity (base hue + the palette's own span, which decides
        // the colours at emission time), the COMPOSITE owns hue rotation
        // (colorShift + colour-cycle phase, which it already applies to this
        // style). The span used to be dropped here - raw hueRange - so every
        // palette painted the same streams in a different tint.
        particles.draw(
            aspect,
            params.particleSize.coerceIn(0.4f, 4f) * dpiScale,
            params.paletteBase,
            FluidHue.span(params.hueRange, params.paletteRange),
            // Beat response lives in the FIELD kick (uAmp); keeping the
            // brightness pulse gentle stops the compound amp+brightness+size
            // jump from reading as a strobe on busy tracks. Exposure
            // (brightness * intensity) is the composite pass's job - folding
            // intensity in here too made that slider quadratic.
            CurlFlowMath.particleBrightness(beatEnv),
        )
    }

    override fun release() {
        particles.release()
        field?.release()
        field = null
        if (fieldProgram != 0) GLES30.glDeleteProgram(fieldProgram)
        fieldProgram = 0
        fieldUniforms.clear()
        if (quadVbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
        if (quadVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(quadVao), 0)
        quadVbo = 0
        quadVao = 0
        available = false
    }
}
