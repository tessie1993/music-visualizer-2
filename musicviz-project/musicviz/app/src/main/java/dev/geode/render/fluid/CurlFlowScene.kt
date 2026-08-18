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
 * exponential release, scaled by Beat response), bass pulls toward the catch
 * points. Existing Customize controls map on: Speed = morph rate, Turbulence =
 * spatial frequency, Audio drive = flow strength, Beat response = how far a
 * beat kicks the field and the points, Particle size/Palette/Hue range =
 * rendering (the palette span via [FluidHue], shared with the other fluid
 * styles), Trails/Trail length = canvas persistence (via [CurlFlowMath]),
 * Particle drag/Particle life = the lifecycle layer, plus the shared fluid
 * spawn/catch params. Grading and hue rotation (Brightness, Intensity,
 * Contrast, Gamma, Hue shift, Zoom, Rotation) belong to the composite pass,
 * which grades this style - the scene must not apply them a second time.
 */
internal class CurlFlowScene(
    private val context: Context,
) : Scene,
    PcmSink {
    override val id: String = SceneIds.CURLFLOW

    private companion object {
        /**
         * Noise-clock wrap: 200 * pi (CymaticsScene TIME_WRAP convention).
         * uTime reaches curl_field_frag only as psrdnoise's 2pi-periodic
         * gradient rotation at two-decimal octave rates, so k * 200pi is
         * k * 100 whole turns - exactly periodic at the wrap.
         */
        const val NOISE_WRAP_SECONDS = 628.31853f

        /** Hash-clock wrap, matching VisualizerRenderer.TIME_WRAP_SEC. */
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

    /** Noise clock, wrapped at 200 * pi - see the wrap site in [draw]. */
    private var noiseTime = 0f

    /** Respawn-hash clock, wrapped at the renderer's TIME_WRAP horizon. */
    private var wallTime = 0f
    private var beatEnv = 0f

    /** [beatEnv] after "Beat response" - the value both beat terms ride. */
    private var beatDrive = 0f
    private val pcmPulse = PcmPulse()
    private var pcmKick = 0f
    private var aspect = 1f
    private var available = false

    private val spawnPack = FloatArray(FluidChoreography.MAX_SPAWN * 4)
    private val catchPack = FloatArray(FluidChoreography.MAX_CATCH * 4)
    private val prevFbo = IntArray(1)
    private val prevViewport = IntArray(4)

    /**
     * Error channel, wired by `VisualizerRenderer.createScene` like every
     * other fluid style's. This scene used to fail with a logcat line at
     * best - silent black is the worst failure mode, and this style had
     * three ways to reach it with nothing on screen to say why.
     */
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
        // Compile failure must degrade the style, never crash the GL thread.
        fieldProgram =
            GlUtil.buildProgramReporting(
                GlUtil.loadShader(context, R.raw.fluid_base_vert),
                // Resolves the psrdnoise include the field is built on.
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
        // LINEAR filtering (core ES3 for half-float SAMPLING) smooths the
        // per-cell velocity quantization that banded the old NEAREST field.
        val (fw, fh) = FluidBuffers.resolution(96, width, height)
        // ok-checked: create() self-releases on an incomplete FBO, and a
        // dead handle here meant draw() bound framebuffer 0 mid-frame and
        // rasterized the field pass onto the screen at the field viewport.
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
            // Wrapped (TIME_WRAP convention): wallTime only seeds the
            // particle respawn hash, which is value-agnostic; the wrap point
            // matches VisualizerRenderer.TIME_WRAP_SEC.
            wallTime = (wallTime + lastDt) % WALL_WRAP_SECONDS
            beatEnv = kotlin.math.max(f.motionImpulse, beatEnv * kotlin.math.exp(-lastDt / 0.35f))
            // The envelope carries the timing, "Beat response" the depth: the
            // slider had no reader on this style, so it moved nothing while
            // "Audio drive" (the field kick below) worked.
            beatDrive = CurlFlowMath.beatDrive(beatEnv, params.beatResponse)
            // Wrapped at 200 * pi (CymaticsScene TIME_WRAP convention):
            // curl_field_frag reads uTime only as psrdnoise's gradient
            // rotation (2pi-periodic) at two-decimal octave rates (1.0 /
            // 1.7 / 2.9), and k * 200pi is k * 100 whole turns.
            noiseTime = (noiseTime + lastDt * (0.15f + f.mid * 1.4f) * FluidChoreography.sceneSpeed(params.speed)) %
                NOISE_WRAP_SECONDS

            // Shared spawn/catch progression: same params as the fluid scene.
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
            // 0 = aperiodic. A whole number of cells here would make the
            // field tile exactly, which is how a seamless loop is built.
            GLES30.glUniform2f(loc("uPeriod"), 0f, 0f)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fld.fbo)
            GLES30.glViewport(0, 0, fld.width, fld.height)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            quad.unbind()

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
