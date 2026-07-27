package dev.musicviz.render.fluid

import android.content.Context
import android.opengl.GLES30
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.render.scene.Scene
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams

/**
 * The FLUID style, phases F2+F3: core sim, the four-emitter audio system,
 * and the GPU particle layer (drag-inertia light trails)
 * (BeatSplat/BandStirrer/TrebleSparkle/BassPump) with continuous modulation. The renderer binds the scene FBO before [update]/[draw],
 * so all internal sim passes snapshot and restore the framebuffer, viewport,
 * and blend state around themselves. Emitter system, look chain, Customize
 * tab, and FlowField land in the following fluid phases (see todo.md).
 */
internal class FluidScene(context: Context) : Scene {
    override val id: String = SceneIds.FLUID

    private val sim = FluidSim(context)
    private val particles = FluidParticles(context)
    private val emitters = FluidEmitters()

    /** F3 defaults; the Customize tab phase (F5) surfaces these. */
    private val particleCount = 256 * 256
    private var params = SceneParams()
    private var time = 0f
    private var lastDt = 1f / 60f
    private var pendingFeatures: AudioFeatures? = null

    // F2 modulation strengths (surfaced as SceneParams in F5).
    private val curlAudio = 0.5f
    private val fadeAudio = 0.6f
    private val baseCurl = 30f
    private val baseDensityDissipation = 1.0f

    override fun init() {
        sim.create()
        if (sim.available) particles.create(particleCount, sim.texFormats)
    }

    override fun setParams(params: SceneParams) {
        this.params = params
    }

    override fun resize(
        width: Int,
        height: Int,
    ) {
        sim.resize(width, height)
        particles.invalidateSeed()
    }

    override fun update(
        features: AudioFeatures,
        dt: Float,
    ) {
        time += dt
        lastDt = dt
        pendingFeatures = features
    }

    override fun draw(timeSeconds: Float) {
        if (!sim.available) return
        val f = pendingFeatures
        // Snapshot the engine's target: the sim renders to its own grids.
        val prevFbo = IntArray(1)
        val prevViewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, prevFbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, prevViewport, 0)
        val blendWas = GLES30.glIsEnabled(GLES30.GL_BLEND)

        if (f != null) {
            // Continuous modulation (v2 spec 7.3): mids swirl harder, quiet
            // passages fade the canvas, drops leave lasting ink.
            val energy = f.rms.coerceIn(0f, 1f)
            sim.curlStrength = baseCurl * params.turbulence.coerceIn(0.1f, 2f) * (1f + curlAudio * f.mid)
            sim.densityDissipation = baseDensityDissipation * (1f + fadeAudio * (1f - energy))
            sim.chromaticAging = 0.3f
            emitters.stirrerSpeed = params.speed.coerceIn(0.1f, 2f)
            emitters.paletteCycleSpeed = if (params.colorCycle) params.cycleSpeed * 20f else 0.15f
            for (s in emitters.tick(f, lastDt, sim.aspect, params.paletteBase, params.hueRange.coerceIn(0.1f, 1f))) {
                sim.queueSplat(s)
            }
            sim.step(lastDt)
            if (particles.available) {
                particles.drag = 0.5f
                particles.step(lastDt, sim.velocityTex, sim.aspect, sim.flowScale)
            }
            pendingFeatures = null
        }

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo[0])
        GLES30.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])
        sim.drawDisplay()
        if (particles.available) {
            particles.draw(
                aspect = sim.aspect,
                pointScale = (1.5f * params.particleSize.coerceIn(0.2f, 3f)),
                hueBase = params.paletteBase,
                hueSpan = params.hueRange.coerceIn(0.1f, 1f),
                brightness = 0.55f * (0.3f + params.density.coerceIn(0f, 1.5f)),
            )
        }
        if (blendWas) GLES30.glEnable(GLES30.GL_BLEND) else GLES30.glDisable(GLES30.GL_BLEND)
    }

    override fun release() {
        particles.release()
        sim.release()
    }
}
