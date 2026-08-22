package dev.geode.render.fluid

import android.content.Context
import android.opengl.GLES30
import dev.geode.R
import dev.geode.analysis.AudioFeatures
import dev.geode.render.scene.GlUtil
import dev.geode.render.scene.PcmPulse
import dev.geode.render.scene.PcmSink
import dev.geode.render.scene.Scene
import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.SceneParams

internal class CurlFlowScene(
    private val context: Context,
) : Scene,
    PcmSink {
    override val id: String = SceneIds.CURLFLOW

    private companion object {
        const val NOISE_WRAP_SECONDS = 628.31853f

        const val WALL_WRAP_SECONDS = 7100f
    }

    private val particles = FluidParticles(context)
    private val choreography = FluidChoreography()
    private lateinit var formats: FluidBuffers.Formats
    private var field: FluidBuffers.Fbo? = null
    private var fieldProgram = 0
    private var fieldUniforms = GlUtil.UniformCache(0)
    private val quad = GlUtil.FullscreenTriangle()
    private var params = SceneParams()
    private var pending: AudioFeatures? = null
    private var lastDt = 1f / 60f

    private var noiseTime = 0f

    private var wallTime = 0f
    private var beatEnv = 0f

    private var beatDrive = 0f
    private val pcmPulse = PcmPulse()
    private var pcmKick = 0f
    private var aspect = 1f
    private var available = false

    private val spawnPack = FloatArray(FluidChoreography.MAX_SPAWN * 4)
    private val catchPack = FloatArray(FluidChoreography.MAX_CATCH * 4)
    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    var onShaderError: (String?) -> Unit = {}

    override fun init() {
        release()
        formats = FluidBuffers.probeFormats()
        available = formats.ok
        if (!available) {
            onShaderError("Curl Flow unavailable: this GPU can't render half-float buffers")
            return
        }
        choreography.reset()
        quad.create()
        fieldProgram =
            GlUtil.buildProgramReporting(
                GlUtil.loadShader(context, R.raw.fluid_base_vert),
                GlUtil.loadShader(context, R.raw.curl_field_frag),
            ) { onShaderError("Curl Flow unavailable on this GPU: $it") }
        if (fieldProgram == 0) {
            release()
            return
        }
        fieldUniforms = GlUtil.UniformCache(fieldProgram)
        particles.create(49_152, formats)
        if (!particles.available) {
            onShaderError("Curl Flow unavailable: this GPU refused the particle state buffers")
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
        val (fw, fh) = FluidBuffers.resolution(96, width, height)
        field =
            FluidBuffers.Fbo(fw, fh, formats.rg, linear = true)
                .also { it.create() }
                .takeIf { it.ok }
        if (field == null) onShaderError("Curl Flow unavailable: this GPU refused the flow-field buffer")
        particles.invalidateSeed()
    }

    override fun setParams(params: SceneParams) {
        this.params = params
    }

    override fun acceptPcm(
        samples: FloatArray,
        count: Int,
    ) = pcmPulse.accept(samples, count)

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        pending = features
        lastDt = dt.coerceIn(0f, 1f / 30f)
        pcmKick = pcmPulse.tick(dt).coerceIn(0f, 1f)
    }

    private fun loc(name: String): Int = fieldUniforms.loc(name)

    override fun draw(timeSeconds: Float) {
        if (!available) return
        val fld = field ?: return
        val f = pending
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)

        if (f != null) {
            wallTime = (wallTime + lastDt) % WALL_WRAP_SECONDS
            beatEnv = kotlin.math.max(f.motionImpulse, beatEnv * kotlin.math.exp(-lastDt / 0.35f))
            beatDrive = CurlFlowMath.beatDrive(beatEnv, params.beatResponse)
            noiseTime = (noiseTime + lastDt * (0.15f + f.mid * 1.4f) * FluidChoreography.sceneSpeed(params.speed)) %
                NOISE_WRAP_SECONDS

            choreography.path = params.fluidSpawnPath.coerceIn(0, FluidChoreography.PATH_LABELS.size - 1)
            choreography.spawnCount = params.fluidSpawnPoints.coerceIn(1, FluidChoreography.MAX_SPAWN)
            choreography.catchCount = params.fluidCatchPoints.coerceIn(0, FluidChoreography.MAX_CATCH)
            choreography.progressionAmount = params.fluidSpawnProgress.coerceIn(0f, 1f)
            choreography.speed = FluidChoreography.sceneSpeed(params.speed)
            choreography.tick(f, lastDt, aspect)

            GLES30.glDisable(GLES30.GL_BLEND)
            quad.bind()
            GLES30.glUseProgram(fieldProgram)
            GLES30.glUniform2f(loc("uInvRes"), 1f / fld.width, 1f / fld.height)
            GLES30.glUniform1f(loc("uAspect"), aspect)
            GLES30.glUniform1f(loc("uTime"), noiseTime)
            GLES30.glUniform1f(loc("uFreq"), 1.2f * (0.5f + params.turbulence.coerceIn(0.1f, 2f)))
            GLES30.glUniform1f(loc("uDetail"), (f.treble * 3f + pcmKick * 0.8f).coerceIn(0f, 1.5f))
            GLES30.glUniform1f(loc("uAmp"), CurlFlowMath.fieldAmp(params.audioDrive, beatDrive) * (1f + pcmKick * 0.35f))
            GLES30.glUniform2f(loc("uPeriod"), 0f, 0f)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fld.fbo)
            GLES30.glViewport(0, 0, fld.width, fld.height)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            quad.unbind()

            particles.drag = params.fluidParticleDrag.coerceIn(0.02f, 1f)
            particles.life = params.fluidParticleLife.coerceIn(1f, 20f)
            choreography.packSpawns(spawnPack)
            choreography.packCatches(
                catchPack,
                pull = params.fluidCatchPull.coerceIn(0f, 3f),
                captureRadius = params.fluidCatchRadius.coerceIn(0.03f, 0.3f),
            )
            particles.setChoreography(spawnPack, choreography.spawnCount, catchPack, choreography.catchCount)
            particles.step(lastDt, fld.tex, aspect, 1f, timeSeconds = wallTime)
            pending = null
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        val dpiScale = (prevViewport[3].coerceAtLeast(1) / 1080f).coerceIn(0.75f, 2.5f)
        particles.draw(
            aspect,
            params.particleSize.coerceIn(0.4f, 4f) * dpiScale,
            params.paletteBase,
            FluidHue.span(params.hueRange, params.paletteRange),
            CurlFlowMath.particleBrightness(beatDrive),
            shape = params.particleShape.toFloat(),
            glow = dev.geode.render.scene.ParticleLook.glow(params.bloom),
            timeSeconds = wallTime,
        )
    }

    override fun release() {
        particles.release()
        field?.release()
        field = null
        if (fieldProgram != 0) GLES30.glDeleteProgram(fieldProgram)
        fieldProgram = 0
        fieldUniforms = GlUtil.UniformCache(0)
        quad.release()
        available = false
    }
}
