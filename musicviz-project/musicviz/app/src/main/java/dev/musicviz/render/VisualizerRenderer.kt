package dev.musicviz.render

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import dev.musicviz.R
import dev.musicviz.analysis.AudioFeatures
import dev.musicviz.export.VideoExporter
import dev.musicviz.render.fluid.CurlFlowMath
import dev.musicviz.render.scene.AttractorScene
import dev.musicviz.render.scene.BeamScene
import dev.musicviz.render.scene.BurstScene
import dev.musicviz.render.scene.CymaticsScene
import dev.musicviz.render.scene.FountainScene
import dev.musicviz.render.scene.GalaxyScene
import dev.musicviz.render.scene.GlUtil
import dev.musicviz.render.scene.HyperspaceScene
import dev.musicviz.render.scene.InkflowScene
import dev.musicviz.render.scene.NebulaScene
import dev.musicviz.render.scene.OrbitScene
import dev.musicviz.render.scene.PMBridge
import dev.musicviz.render.scene.ParticleSceneBase
import dev.musicviz.render.scene.PcmChunk
import dev.musicviz.render.scene.ProjectMScene
import dev.musicviz.render.scene.Scene
import dev.musicviz.render.scene.SceneIds
import dev.musicviz.render.scene.SceneParams
import dev.musicviz.render.scene.ShaderScene
import dev.musicviz.render.scene.StormScene
import dev.musicviz.render.scene.SwarmScene
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.pow

/**
 * Multi-scene GL ES 3.0 renderer with an offscreen pipeline: the active scene
 * renders into FBO A; during a transition the outgoing scene renders into
 * FBO B and a compositor shader blends them (Cut/Fade/Melt). Trails work by
 * fading FBO A instead of clearing it. Scene switching, params and shader
 * edits are queued from other threads and applied on the GL thread; all GL
 * resources are (re)created in [onSurfaceCreated].
 */
class VisualizerRenderer(
    private val context: Context,
) : GLSurfaceView.Renderer {
    companion object {
        /** Fragment-shader scenes: id -> raw resource. Order = UI order. */
        val SHADER_SCENES: Map<String, Int> =
            linkedMapOf(
                SceneIds.JULIA to R.raw.julia_frag,
                SceneIds.TUNNEL to R.raw.tunnel_frag,
                SceneIds.MANDEL to R.raw.mandel_frag,
                SceneIds.KALEIDO to R.raw.kaleido_frag,
                SceneIds.PLASMA to R.raw.plasma_frag,
                SceneIds.BARS to R.raw.bars_frag,
                SceneIds.RING to R.raw.ring_frag,
                SceneIds.SCOPE to R.raw.scope_frag,
                SceneIds.LISS to R.raw.liss_frag,
                SceneIds.WARP to R.raw.warp_frag,
                SceneIds.GRID to R.raw.grid_frag,
                SceneIds.VORONOI to R.raw.voronoi_frag,
                SceneIds.METABALLS to R.raw.metaballs_frag,
                SceneIds.RIPPLES to R.raw.ripples_frag,
                SceneIds.STARFIELD to R.raw.starfield_frag,
                SceneIds.WAVES to R.raw.waves_frag,
                SceneIds.HEXGRID to R.raw.hexgrid_frag,
                SceneIds.SPIRAL to R.raw.spiral_frag,
                SceneIds.AURORA to R.raw.aurora_frag,
                SceneIds.SOLAR to R.raw.solar_frag,
                SceneIds.WINTER to R.raw.winter_frag,
                SceneIds.LAVA to R.raw.lava_frag,
            )
        val PARTICLE_SCENES: List<String> =
            listOf(
                SceneIds.NEBULA,
                SceneIds.BURSTS,
                SceneIds.SWARM,
                SceneIds.FOUNTAIN,
                SceneIds.ORBITS,
                SceneIds.GALAXY,
                SceneIds.ATTRACTOR,
                SceneIds.STORM,
                SceneIds.INKFLOW,
            )

        /** Fingertip footprint for the touch smear, in sim units. */
        private const val TOUCH_RADIUS = 0.11f

        /** Queued drag frames kept while the GL thread catches up. */
        private const val MAX_TOUCH_BACKLOG = 24

        /** How long the borrowed ripple overlay outlives the last stroke. */
        private const val TOUCH_LINGER_MS = 2_500L

        /** Refraction floor while a finger is on the glass. */
        private const val TOUCH_MIN_OVERLAY_STRENGTH = 0.35f

        /**
         * Wrap period for [timeSeconds], the uniform uploaded as `uTime`.
         *
         * Every other clock in the app is wrapped for this reason already
         * (`FluidParticles` `% 256f`, `CompositeGrade.integrateRotation`
         * `% TAU`, `ShaderScene`'s `% 1f` phases). This one is the exception,
         * and it is the one that needs it most: the per-scene clocks live on
         * objects [onSurfaceCreated] rebuilds, so an EGL context loss resets
         * them, while this field lives on the RENDERER and survives every
         * context loss for the life of the process - and the live wallpaper
         * renders continuously. Unwrapped, `uTime * 91.7` (the composite
         * pass' Shake) reaches ~7.9e6 after a day of visible time, where the
         * float32 ULP is ~1 rad against a 1.53 rad per-frame phase advance:
         * the jitter degenerates into a two-value stutter and then freezes.
         *
         * 7100 s is the period that keeps EVERY consumer continuous across
         * the wrap, because it satisfies both families of multiplier at once:
         *
         * - Sines/cosines need `k * period` to be a whole number of turns.
         *   7100 s is 1130 turns of 2*pi to within 0.6 ms (355/113 is the
         *   classical convergent of pi), and every multiplier in the shaders
         *   has one decimal place - 0.7, 0.9, 1.0, 1.2, 3.0, 5.3, 77.3, 91.7
         *   - so `k * 1130` is a whole number of turns for all of them. The
         *   residual jump is at most 0.055 rad on the fastest term (Shake),
         *   which is BELOW the 0.0625 rad float32 ULP the same term already
         *   carries there: the wrap is indistinguishable from rounding.
         * - The non-sine terms multiply time by a plain number and take
         *   `fract`/`floor` of it: drift scroll (`uTime * 0.1`), the glitch
         *   band clock (`uTime * 12.0`) and the strobe (`uTime * 9.0`, or any
         *   whole-Hz rate). 7100 is a whole number of seconds, so all of them
         *   land on an exact integer and step across the wrap without a jump.
         *
         * A multiple of 2*pi alone (20*pi = 62.8 s, the smallest period that
         * makes the sines exact) fails the second family: `fract(0.1 * 20pi)`
         * = 0.28, i.e. the drift-scrolled image would pop by 28% of the frame
         * every minute. And 7100 s of unique phase leaves the fastest term at
         * 24 ULP-steps per frame of phase advance - the same precision the
         * app has today two hours in, held there forever instead of decaying.
         */
        private const val TIME_WRAP_SEC = 7100f

        /**
         * `uStyle` for Layers. 0..4 are [TransitionStyle]'s ordinals and 5 is
         * [TransitionCatalog.STYLE_LIBRARY], so this continues that sequence
         * and is part of `composite_frag.glsl`'s contract.
         */
        const val STYLE_LAYER = 6
    }

    @Volatile
    var features: AudioFeatures = AudioFeatures.empty()

    @Volatile
    var requestedSceneId: String = SceneIds.NEBULA

    @Volatile
    var sceneParams: SceneParams = SceneParams.DEFAULT

    /**
     * Photosensitivity limits, from Settings. Applied after every modulator in
     * [onDrawFrame] and mirrored by `FxCompositor` so an exported clip is as
     * safe as the screen was; [VisualSafety.SafetyConfig.OFF] changes nothing.
     */
    @Volatile
    var safety: VisualSafety.SafetyConfig = VisualSafety.SafetyConfig.OFF

    /** Smoothed params actually shown; fades toward [sceneParams] over paramFadeSec. */
    private var displayedParams: SceneParams = SceneParams.DEFAULT

    // One-shot preset morph: glides displayedParams over the given seconds,
    // then expires (3 time constants ~ 95% settled) so later slider tweaks
    // respond at the user's own paramFadeSec again.
    @Volatile
    private var morphFadeSec = 0f

    @Volatile
    private var morphRemainSec = 0f

    /** Called on preset apply; safe from any thread (floats, worst case one late frame). */
    fun beginParamMorph(seconds: Float) {
        if (seconds <= 0f) return
        morphFadeSec = seconds
        morphRemainSec = seconds * 3f
    }

    /** Assignable LFO modulation, evaluated per frame after smoothing. */
    val lfoEngine = LfoEngine()

    /** Beat-triggered ADSR envelope, applied after the LFOs. */
    val adsrEngine = AdsrEngine()

    /**
     * The envelopes' LFO rate/depth contributions for this frame. GL-thread
     * only, written by [AdsrEngine.lfoOffsets] and read by [LfoEngine.tick]
     * a line later, both inside [onDrawFrame] - so reusing them costs nothing
     * in lifetime and saves a `Pair` plus two `FloatArray(3)` every frame.
     * [LfoEngine.tick] copies before it accumulates, so it cannot write back
     * into these.
     */
    private val envRateOffsets = FloatArray(3)
    private val envDepthOffsets = FloatArray(3)

    /** Final params of the current frame (after fade + LFO), for the composite FX pass. */
    private var lastFinalParams: SceneParams = SceneParams.DEFAULT

    /** Composite-pass rotation angle. Rotation is a SPEED in every scene
     *  (`rotationAngle += p.rotation * dt`), so the pass that rotates the
     *  fluid family has to integrate its own angle rather than feed the raw
     *  slider through as a static offset. */
    private var postRotationAngle = 0f

    /** Composite-pass colour-cycle phase, integrated like ShaderScene's, so
     *  the hue uploaded below is `colorShift + cyclePhase` exactly as every
     *  self-grading scene computes it. */
    private var postCyclePhase = 0f

    /** Composite-pass beat envelope (1 on a beat, decaying), the source of the
     *  "Beat pulse" swell for the scenes that don't pulse themselves. The
     *  shader's own `uBeat` is a per-frame boolean, so a pulse driven from it
     *  would be a single-frame pop; this is the same decaying envelope
     *  ShaderScene/ParticleSceneBase keep. */
    private var postBeatPulse = 0f

    /** Exponential lerp between param sets; toggles and choices snap to target. */
    private fun lerpParams(
        from: SceneParams,
        to: SceneParams,
        k: Float,
    ): SceneParams {
        fun f(
            a: Float,
            b: Float,
        ) = a + (b - a) * k
        return to.copy(
            speed = f(from.speed, to.speed),
            zoom = f(from.zoom, to.zoom),
            rotation = f(from.rotation, to.rotation),
            endlessZoomSpeed = f(from.endlessZoomSpeed, to.endlessZoomSpeed),
            sway = f(from.sway, to.sway),
            pulse = f(from.pulse, to.pulse),
            driftX = f(from.driftX, to.driftX),
            driftY = f(from.driftY, to.driftY),
            shake = f(from.shake, to.shake),
            audioDrive = f(from.audioDrive, to.audioDrive),
            beatResponse = f(from.beatResponse, to.beatResponse),
            turbulence = f(from.turbulence, to.turbulence),
            density = f(from.density, to.density),
            trailLength = f(from.trailLength, to.trailLength),
            warp = f(from.warp, to.warp),
            ripple = f(from.ripple, to.ripple),
            morph = f(from.morph, to.morph),
            pixelate = f(from.pixelate, to.pixelate),
            posterize = f(from.posterize, to.posterize),
            particleSize = f(from.particleSize, to.particleSize),
            tile = f(from.tile, to.tile),
            twist = f(from.twist, to.twist),
            paletteMix = f(from.paletteMix, to.paletteMix),
            colorShift = f(from.colorShift, to.colorShift),
            hueRange = f(from.hueRange, to.hueRange),
            saturation = f(from.saturation, to.saturation),
            brightness = f(from.brightness, to.brightness),
            contrast = f(from.contrast, to.contrast),
            gamma = f(from.gamma, to.gamma),
            cycleSpeed = f(from.cycleSpeed, to.cycleSpeed),
            intensity = f(from.intensity, to.intensity),
            bloom = f(from.bloom, to.bloom),
            temperature = f(from.temperature, to.temperature),
            bassGain = f(from.bassGain, to.bassGain),
            midGain = f(from.midGain, to.midGain),
            trebGain = f(from.trebGain, to.trebGain),
            flash = f(from.flash, to.flash),
            chromaAb = f(from.chromaAb, to.chromaAb),
            vignette = f(from.vignette, to.vignette),
            scanlines = f(from.scanlines, to.scanlines),
            grain = f(from.grain, to.grain),
            glitch = f(from.glitch, to.glitch),
            fisheye = f(from.fisheye, to.fisheye),
            strobe = f(from.strobe, to.strobe),
            fluidPressure = f(from.fluidPressure, to.fluidPressure),
            fluidCurl = f(from.fluidCurl, to.fluidCurl),
            fluidVelocityDissipation = f(from.fluidVelocityDissipation, to.fluidVelocityDissipation),
            fluidDensityDissipation = f(from.fluidDensityDissipation, to.fluidDensityDissipation),
            fluidChromaticAging = f(from.fluidChromaticAging, to.fluidChromaticAging),
            fluidSplatRadius = f(from.fluidSplatRadius, to.fluidSplatRadius),
            fluidSplatForce = f(from.fluidSplatForce, to.fluidSplatForce),
            fluidStirrerSpeed = f(from.fluidStirrerSpeed, to.fluidStirrerSpeed),
            fluidPaletteCycleSpeed = f(from.fluidPaletteCycleSpeed, to.fluidPaletteCycleSpeed),
            fluidParticleDrag = f(from.fluidParticleDrag, to.fluidParticleDrag),
            fluidParticleBrightness = f(from.fluidParticleBrightness, to.fluidParticleBrightness),
            fluidBloomIntensity = f(from.fluidBloomIntensity, to.fluidBloomIntensity),
            fluidBloomThreshold = f(from.fluidBloomThreshold, to.fluidBloomThreshold),
            fluidSunraysWeight = f(from.fluidSunraysWeight, to.fluidSunraysWeight),
            fluidCurlAudio = f(from.fluidCurlAudio, to.fluidCurlAudio),
            fluidBloomAudio = f(from.fluidBloomAudio, to.fluidBloomAudio),
            fluidFadeAudio = f(from.fluidFadeAudio, to.fluidFadeAudio),
            fluidRadiusPulse = f(from.fluidRadiusPulse, to.fluidRadiusPulse),
            fluidSpawnProgress = f(from.fluidSpawnProgress, to.fluidSpawnProgress),
            fluidCatchPull = f(from.fluidCatchPull, to.fluidCatchPull),
            fluidCatchRadius = f(from.fluidCatchRadius, to.fluidCatchRadius),
            fluidParticleLife = f(from.fluidParticleLife, to.fluidParticleLife),
            flowStrength = f(from.flowStrength, to.flowStrength),
            flowForce = f(from.flowForce, to.flowForce),
            flowCurl = f(from.flowCurl, to.flowCurl),
            waterWaveSpeed = f(from.waterWaveSpeed, to.waterWaveSpeed),
            waterDamping = f(from.waterDamping, to.waterDamping),
            waterRippleStrength = f(from.waterRippleStrength, to.waterRippleStrength),
            waterDepth = f(from.waterDepth, to.waterDepth),
            waterSpecular = f(from.waterSpecular, to.waterSpecular),
            waterFlow = f(from.waterFlow, to.waterFlow),
            waterLiquid = f(from.waterLiquid, to.waterLiquid),
            waterLiquidFlow = f(from.waterLiquidFlow, to.waterLiquidFlow),
            waterLiquidFade = f(from.waterLiquidFade, to.waterLiquidFade),
            beamWidth = f(from.beamWidth, to.beamWidth),
            beamIntensity = f(from.beamIntensity, to.beamIntensity),
            beamTail = f(from.beamTail, to.beamTail),
            cymaticsFundamental = f(from.cymaticsFundamental, to.cymaticsFundamental),
            cymaticsRing = f(from.cymaticsRing, to.cymaticsRing),
            cymaticsFocus = f(from.cymaticsFocus, to.cymaticsFocus),
            cymaticsScale = f(from.cymaticsScale, to.cymaticsScale),
            cymaticsFill = f(from.cymaticsFill, to.cymaticsFill),
            cymaticsLine = f(from.cymaticsLine, to.cymaticsLine),
            cymaticsGlow = f(from.cymaticsGlow, to.cymaticsGlow),
            cymaticsIridescence = f(from.cymaticsIridescence, to.cymaticsIridescence),
            cymaticsCaustic = f(from.cymaticsCaustic, to.cymaticsCaustic),
            cymaticsFlow = f(from.cymaticsFlow, to.cymaticsFlow),
            cymaticsSwirl = f(from.cymaticsSwirl, to.cymaticsSwirl),
            hyperCycleSeconds = f(from.hyperCycleSeconds, to.hyperCycleSeconds),
            hyperBodies = f(from.hyperBodies, to.hyperBodies),
            hyperLifetime = f(from.hyperLifetime, to.hyperLifetime),
            hyperSpin = f(from.hyperSpin, to.hyperSpin),
            hyperOrbit = f(from.hyperOrbit, to.hyperOrbit),
            hyperFold = f(from.hyperFold, to.hyperFold),
            hyperDetail = f(from.hyperDetail, to.hyperDetail),
            hyperGlow = f(from.hyperGlow, to.hyperGlow),
            hyperNeon = f(from.hyperNeon, to.hyperNeon),
            hyperField = f(from.hyperField, to.hyperField),
            hyperHaze = f(from.hyperHaze, to.hyperHaze),
            hyperCamera = f(from.hyperCamera, to.hyperCamera),
            hyperTrap = f(from.hyperTrap, to.hyperTrap),
            hyperMelt = f(from.hyperMelt, to.hyperMelt),
            hyperStain = f(from.hyperStain, to.hyperStain),
            hyperLiquid = f(from.hyperLiquid, to.hyperLiquid),
            hyperRidges = f(from.hyperRidges, to.hyperRidges),
            hyperStir = f(from.hyperStir, to.hyperStir),
            hyperSwirl = f(from.hyperSwirl, to.hyperSwirl),
            hyperFlowFade = f(from.hyperFlowFade, to.hyperFlowFade),
            rippleOverlayStrength = f(from.rippleOverlayStrength, to.rippleOverlayStrength),
            rippleOverlaySpecular = f(from.rippleOverlaySpecular, to.rippleOverlaySpecular),
        )
    }

    private fun gainAdjusted(
        f: dev.musicviz.analysis.AudioFeatures,
        p: SceneParams,
    ): dev.musicviz.analysis.AudioFeatures =
        dev.musicviz.render.scene
            .applyBandGains(f, p)

    /**
     * Layers: the id of a SECOND scene rendered under the active one every
     * frame, or null for the single-scene behaviour this has always had.
     *
     * Renderer state rather than a [SceneParams] field, deliberately, and for
     * the same reason [transitionStyle] is: a SceneParams entry carries a
     * contract - a Customize control, a randomizer entry, a preset key and a
     * reader, all enforced by CustomizeSurfaceTest - because it describes how
     * ONE scene looks. This describes which scenes are on screen, which is the
     * renderer's business.
     *
     * Ignored when it names the active scene (a style blended with itself is
     * just that style at a different exposure) or an unknown id.
     */
    @Volatile
    var layerSceneId: String? = null

    /** How much the top layer contributes, 0..1. See `uLayerMix`. */
    @Volatile
    var layerMix: Float = 0.5f

    @Volatile
    var layerBlend: BlendMode = BlendMode.SCREEN

    @Volatile
    var transitionStyle: TransitionStyle = TransitionStyle.FADE

    @Volatile
    var transitionDurationMs: Long = 1200

    @Volatile
    var onShaderError: (String?) -> Unit = {}

    /** Queue (not a single slot) so rapid edits to different scenes all land. */
    private val pendingCustomShaders = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()

    /** Retained across EGL context loss so scenes can be restored on recreation. */
    @Volatile
    private var lastMilkPreset: String? = null

    /**
     * The live MilkDrop scene, or null while there is none (no libprojectM, or
     * the GL thread is between contexts). Written on the GL thread as the
     * registry is rebuilt, read by [loadMilkPreset] and
     * [reloadCurrentMilkPreset], which are called from the main thread.
     *
     * A preset that arrives during the rebuild lands on null and is dropped
     * here - [onSurfaceCreated] re-queues [lastMilkPreset] onto the fresh
     * scene afterwards, and both fields are `@Volatile`, so the write that
     * preceded the dropped queue is guaranteed visible to that re-queue.
     */
    @Volatile
    private var milkdropScene: ProjectMScene? = null
    private val activeCustomShaders = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** User fluid force/dye injection sources (extension points), retained
     *  across context loss like custom scene shaders. */
    @Volatile
    private var fluidForceSrc: String? = null

    @Volatile
    private var fluidDyeSrc: String? = null

    @Volatile
    private var fluidInjectionDirty = false

    /** Installs user force/dye GLSL for the FLUID scene (null = built-in). */
    fun submitFluidInjectionShaders(
        force: String?,
        dye: String?,
    ) {
        fluidForceSrc = force
        fluidDyeSrc = dye
        fluidInjectionDirty = true
    }

    /** F7 FlowField service + the 1x1 zero texture bound when it's off. */
    private var flowField: dev.musicviz.render.fluid.FlowField? = null
    private var zeroTex = 0

    /** F2 ripple overlay: renderer-owned heightfield refracting ANY style. */
    private var rippleOverlay: dev.musicviz.render.fluid.RippleSim? = null
    private val rippleDrops =
        dev.musicviz.render.fluid
            .RippleOverlayDrops()

    /** Overlay grid short side: fixed budget tier (WaterScene tier 4-ish);
     *  the overlay rides on top of a full scene, so it stays cheap. */
    private val rippleOverlayRes = 256

    /** One frame of a finger drag, in normalized screen space (y down). */
    private class TouchStroke(
        val nx: Float,
        val ny: Float,
        val ndx: Float,
        val ndy: Float,
        val dt: Float,
        val strength: Float,
    )

    private val touchStrokes = java.util.concurrent.ConcurrentLinkedQueue<TouchStroke>()

    @Volatile
    private var lastTouchMs = 0L

    /**
     * Queues one frame of a finger drag ("smear the visuals"). Coordinates are
     * normalized to the surface (0..1, y DOWN as the UI reports them); the GL
     * thread converts to sim space, where y is up.
     *
     * Routing mirrors the FLUID/FlowField and WATER/ripple-overlay exclusivity
     * the rest of this renderer keeps: on WATER the stroke goes into the
     * style's own surface, on every other style into the shared ripple
     * overlay, so the touch is never applied twice. Safe from the UI thread.
     */
    fun queueTouchStroke(
        nx: Float,
        ny: Float,
        ndx: Float,
        ndy: Float,
        dt: Float,
        strength: Float,
    ) {
        if (strength <= 0f) return
        if (touchStrokes.size >= MAX_TOUCH_BACKLOG) return
        lastTouchMs = SystemClock.elapsedRealtime()
        touchStrokes.add(TouchStroke(nx, ny, ndx, ndy, dt, strength))
    }

    /** Fresh mono PCM for projectM; set by the UI wiring. */
    @Volatile
    var pcmProvider: () -> PcmChunk? = { null }

    val milkdropAvailable: Boolean get() = PMBridge.available

    /**
     * The scene registry, GL THREAD ONLY: [onSurfaceCreated] clears and
     * repopulates it wholesale on every context recreation, and a plain
     * HashMap read during that rehash can return null for a key that is there,
     * spin, or throw. Anything off-thread that needs a scene gets a
     * `@Volatile` handle on it ([milkdropScene]) instead of a lookup.
     */
    private val scenes = LinkedHashMap<String, Scene>()
    private var activeScene: Scene? = null
    private var outgoingScene: Scene? = null

    /**
     * The resolved layer scene for THIS frame, or null. Read by the composite
     * block far below, so it is a field rather than a local.
     */
    private var layerScene: Scene? = null

    /** Frozen at transition start: the outgoing scene keeps the look it had
     *  when the switch happened. Feeding it the live (morphing) params made
     *  snapped fields - palette choice, toggles - jump on the OLD scene
     *  during its fade-out, one of the preset-switch flash sources. */
    private var outgoingParams: SceneParams? = null
    private var transitionStartMs = 0L
    private var width = 1
    private var height = 1
    private var renderWidth = 1
    private var renderHeight = 1
    private var lastFrameMs = 0L

    /** Visible-time clock uploaded as `uTime`; wrapped, see [TIME_WRAP_SEC]. */
    private var timeSeconds = 0f
    private var fadeProgram = 0
    private var trailWarpProgram = 0
    private var trailFbo = 0
    private var trailTex = 0
    private var compositeProgram = CompositeProgram(0)

    /** The unspliced composite, used for the built-in styles and as fallback. */
    private var baseCompositeProgram = CompositeProgram(0)

    /**
     * A linked composite program and the uniform locations resolved against
     * it. Caching them is worth an object: ~30 glGetUniformLocation calls per
     * frame are measurable driver overhead on mobile GPUs.
     *
     * The cache travels WITH the program rather than living in a map keyed by
     * the GL name, because a name is not an identity: glDeleteProgram frees it
     * and the next glCreateProgram is free to hand the same number straight
     * back. Keyed by handle, an evicted variant's locations were inherited by
     * whatever linked next - and every spliced variant declares its own
     * uniforms (see [TransitionCatalog.spliceInto]), so those locations point
     * at other slots, or at -1 where the splice pruned one. The result was a
     * sampler bound to the wrong unit and a uProgress that never advanced -
     * a black or frozen transition, with no GL error anywhere to trace it
     * from, lasting until the next context loss. Tying the two together makes
     * that class of bug unrepresentable: evicting the program evicts its
     * locations because they are the same object.
     */
    private class CompositeProgram(
        val handle: Int,
    ) {
        private val locs = HashMap<String, Int>()

        fun loc(name: String): Int = locs.getOrPut(name) { GLES30.glGetUniformLocation(handle, name) }
    }

    /**
     * Selected transition, as a [TransitionCatalog] id. Built-in styles are
     * handled by the base composite program; anything else names a corpus
     * transition and gets its own spliced variant.
     */
    @Volatile
    var transitionId: String = TransitionStyle.FADE.name.lowercase()

    /**
     * Linked composite variants by transition id, most-recently-used last.
     *
     * Bounded: each variant is a full copy of a 400-line shader, and a user
     * browsing the picker would otherwise leave 123 programs resident. Four is
     * enough that flipping between a couple of favourites never recompiles.
     */
    private val transitionPrograms = LinkedHashMap<String, CompositeProgram>()

    /** Corpus source of the variant currently bound, for its uniform upload. */
    private var activeTransition: TransitionCatalog.Def? = null

    /** Base composite source, kept so variants can be spliced without a re-read. */
    private var compositeSource: String = ""
    private val fadeLocs = HashMap<String, Int>()
    private val trailLocs = HashMap<String, Int>()

    private fun cLoc(name: String): Int = compositeProgram.loc(name)

    /** Max linked transition variants held at once (see [transitionPrograms]). */
    private val maxTransitionPrograms = 4

    /**
     * The composite program for [id]: the base one for a built-in style, or a
     * lazily linked spliced variant for a corpus transition.
     *
     * Compiling here means the first frame of a newly picked transition pays a
     * driver compile. That is deliberate over compiling all 123 at startup, and
     * it is why the renderer warms the program the moment the user picks one
     * rather than waiting for a scene switch to need it. A variant that fails
     * to link falls back to the base program: a transition that will not
     * compile on some driver must not take the app's scene switching with it.
     */
    private fun transitionProgram(id: String): CompositeProgram {
        if (TransitionCatalog.builtIn(id) != null) return baseCompositeProgram
        transitionPrograms[id]?.let {
            // Refresh recency: LinkedHashMap keeps insertion order, so
            // re-inserting moves it to the end where eviction never looks.
            transitionPrograms.remove(id)
            transitionPrograms[id] = it
            return it
        }
        val def = TransitionCatalog.definition(context, id) ?: return baseCompositeProgram
        val program =
            runCatching {
                CompositeProgram(
                    GlUtil.buildProgram(loadRaw(R.raw.fade_vert), TransitionCatalog.spliceInto(compositeSource, def)),
                )
            }.getOrElse {
                android.util.Log.w("Transitions", "\"$id\" failed to link: ${it.message}")
                return baseCompositeProgram
            }
        while (transitionPrograms.size >= maxTransitionPrograms) {
            val oldest = transitionPrograms.keys.first()
            // Dropping the entry drops the evicted program's uniform locations
            // with it - the driver is about to reissue that name.
            transitionPrograms.remove(oldest)?.let { p -> GLES30.glDeleteProgram(p.handle) }
        }
        transitionPrograms[id] = program
        return program
    }

    /** Links a transition ahead of the switch that will use it. GL thread. */
    fun warmTransition(id: String) {
        if (compositeSource.isNotEmpty()) transitionProgram(id)
    }

    private var quadVao = 0

    /** Blue-noise dither mask for the composite's output stage; 0 if unavailable. */
    private var noiseTex = 0

    /** Cyclic colour-map atlas shared by every shader scene; 0 if unavailable. */
    private var paletteLutTex = 0
    private var fboA = TargetFbo()
    private var fboB = TargetFbo()

    private class TargetFbo {
        var fbo = 0
        var tex = 0
        var w = 0
        var h = 0

        fun ensure(
            width: Int,
            height: Int,
        ) {
            if (fbo != 0 && w == width && h == height) return
            release()
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            tex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                GLES30.GL_RGBA8,
                width,
                height,
                0,
                GLES30.GL_RGBA,
                GLES30.GL_UNSIGNED_BYTE,
                null,
            )
            GLES30.glGenFramebuffers(1, ids, 0)
            fbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(
                GLES30.GL_FRAMEBUFFER,
                GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D,
                tex,
                0,
            )
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            w = width
            h = height
        }

        fun release() {
            if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            if (tex != 0) GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            fbo = 0
            tex = 0
            w = 0
            h = 0
        }
    }

    /**
     * Every style this build can offer, in the order the registry holds them.
     *
     * This is now the ONE list: [onSurfaceCreated] walks it and builds each
     * scene through [createScene], so a style named here always exists and a
     * style not named here can never be reached. It used to be a second,
     * parallel spelling of the constructor block below, and it drifted -
     * Curl Flow was listed here for a release while nothing ever constructed
     * it, so picking it silently did nothing.
     */
    fun availableSceneIds(): List<String> =
        buildList {
            addAll(PARTICLE_SCENES)
            addAll(SHADER_SCENES.keys)
            if (PMBridge.available) add(SceneIds.MILKDROP)
            add(SceneIds.FLUID)
            add(SceneIds.CURLFLOW)
            add(SceneIds.WATER)
            add(SceneIds.CYMATICS)
            add(SceneIds.BEAM)
            add(SceneIds.HYPERSPACE)
        }

    /**
     * Constructs the scene named by [id], wired to this renderer's error
     * channel. GL thread only, and only from [onSurfaceCreated]: the returned
     * scene owns no GL resources until [Scene.init] runs.
     *
     * Every branch is reachable from [availableSceneIds] and nothing else asks
     * for an id, so `error` here is a wiring mistake rather than a device
     * condition - `RendererWiringTest` compares the two lists and fails the
     * build on one instead of letting it reach a GL thread.
     */
    private fun createScene(
        id: String,
        particleShaders: ParticleSceneBase.ShaderSources,
        quadVert: String,
    ): Scene {
        SHADER_SCENES[id]?.let { res -> return ShaderScene(id, quadVert, loadRaw(res)) { onShaderError(it) } }
        return when (id) {
            SceneIds.NEBULA -> NebulaScene(particleShaders)
            SceneIds.BURSTS -> BurstScene(particleShaders)
            SceneIds.SWARM -> SwarmScene(particleShaders)
            SceneIds.FOUNTAIN -> FountainScene(particleShaders)
            SceneIds.ORBITS -> OrbitScene(particleShaders)
            SceneIds.GALAXY -> GalaxyScene(particleShaders)
            SceneIds.ATTRACTOR -> AttractorScene(particleShaders)
            SceneIds.STORM -> StormScene(particleShaders)
            SceneIds.INKFLOW -> InkflowScene(particleShaders)
            SceneIds.FLUID ->
                dev.musicviz.render.fluid.FluidScene(context).also { fluid ->
                    fluid.onShaderError = { onShaderError(it) }
                }
            SceneIds.CURLFLOW ->
                dev.musicviz.render.fluid
                    .CurlFlowScene(context)
            SceneIds.WATER ->
                dev.musicviz.render.fluid.WaterScene(context).also { water ->
                    water.onShaderError = { onShaderError(it) }
                }
            SceneIds.BEAM ->
                BeamScene(context).also { beam ->
                    beam.onShaderError = { onShaderError(it) }
                }
            SceneIds.CYMATICS ->
                CymaticsScene(context).also { plate ->
                    plate.onShaderError = { onShaderError(it) }
                }
            SceneIds.HYPERSPACE ->
                HyperspaceScene(context).also { hyper ->
                    hyper.onShaderError = { onShaderError(it) }
                }
            SceneIds.MILKDROP ->
                ProjectMScene(
                    postVertexSrc = loadRaw(R.raw.fade_vert),
                    postFragmentSrc = loadRaw(R.raw.pm_post_frag),
                    sharedTextureDir = File(context.filesDir, "milk/textures").absolutePath,
                    pcmProvider = { pcmProvider() },
                    onError = { onShaderError(it) },
                )
            else -> error("availableSceneIds offers \"$id\" but createScene cannot build it")
        }
    }

    fun submitShader(
        sceneId: String,
        fragmentSrc: String,
    ) {
        pendingCustomShaders.add(sceneId to fragmentSrc)
        activeCustomShaders[sceneId] = fragmentSrc
    }

    fun shaderSourceFor(sceneId: String): String? = SHADER_SCENES[sceneId]?.let { loadRaw(it) }

    /** The user-edited fragment source for [sceneId], or null if unedited. */
    fun customShaderFor(sceneId: String): String? = activeCustomShaders[sceneId]

    /** Thread-safe: the scene queues the path and loads it on the GL thread. */
    fun loadMilkPreset(path: String) {
        // Recorded FIRST, so a call that races the registry rebuild is still
        // picked up by onSurfaceCreated's re-queue rather than lost. Losing it
        // is what left MilkDrop showing projectM's idle logo after a restart.
        lastMilkPreset = path
        milkdropScene?.queuePreset(path)
    }

    /** Re-queues the currently loaded preset so newly added textures apply. */
    fun reloadCurrentMilkPreset() {
        milkdropScene?.reloadCurrent()
    }

    override fun onSurfaceCreated(
        gl: GL10?,
        config: EGLConfig?,
    ) {
        // Nulled before the scenes it names are released, so a main-thread
        // preset load never reaches a scene whose GL handles are already dead.
        milkdropScene = null
        scenes.values.forEach { it.release() }
        scenes.clear()
        fboA.release()
        fboB.release()
        // Trail buffer names belong to the OLD context; without this reset
        // ensureTrailBuffer() keeps blitting into a dead framebuffer after
        // the app resumes (trail warp renders black until a resize).
        trailFbo = 0
        trailTex = 0
        trailW = 0
        trailH = 0
        val particleShaders = particleShaderSources(context)
        val quadVert = loadRaw(R.raw.quad_vert)
        for (id in availableSceneIds()) scenes[id] = createScene(id, particleShaders, quadVert)
        // The particle family shares one base, so it is wired here rather than
        // nine times over in createScene. Before init(), because init() is
        // where a driver-rejected shader has something to report.
        scenes.values.filterIsInstance<ParticleSceneBase>().forEach { particles ->
            particles.onShaderError = { onShaderError(it) }
        }
        milkdropScene = scenes[SceneIds.MILKDROP] as? ProjectMScene
        scenes.values.forEach { it.init() }
        // Restore state that would otherwise be lost when the EGL context is
        // destroyed while backgrounded: re-apply the current params to every
        // scene, re-push any edited custom shaders, and re-queue the last
        // milkdrop preset so the visualizer resumes exactly where it was.
        scenes.values.forEach { it.setParams(sceneParams) }
        for ((sceneId, src) in activeCustomShaders) {
            (scenes[sceneId] as? ShaderScene)?.setFragmentSource(src)
        }
        lastMilkPreset?.let { milkdropScene?.queuePreset(it) }
        // Re-apply user fluid injection shaders lost with the old context.
        if (fluidForceSrc != null || fluidDyeSrc != null) fluidInjectionDirty = true
        activeScene = scenes[requestedSceneId] ?: scenes[SceneIds.NEBULA]
        outgoingScene = null
        outgoingParams = null

        // FlowField service (F7) + the always-valid zero flow texture.
        if (noiseTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(noiseTex), 0)
            noiseTex = 0
        }
        if (paletteLutTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(paletteLutTex), 0)
            paletteLutTex = 0
        }
        flowField?.release()
        flowField =
            dev.musicviz.render.fluid
                .FlowField(context)
                .also { it.create() }
        // Ripple overlay service (F2): handles from a lost EGL context are
        // dead names, so it is released and rebuilt here like the FlowField.
        rippleOverlay?.release()
        rippleOverlay =
            dev.musicviz.render.fluid
                .RippleSim(context)
                .also {
                    it.create()
                    it.applyResolution(rippleOverlayRes)
                }
        rippleDrops.reset()
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        zeroTex = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, zeroTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        val zero =
            java.nio.ByteBuffer
                .allocateDirect(4)
                .apply { put(byteArrayOf(0, 0, 0, 0)).position(0) }
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            1,
            1,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            zero,
        )

        noiseTex = BlueNoise.createTexture(context)
        paletteLutTex = CyclicPalettes.createTexture(context)
        scenes.values.filterIsInstance<ShaderScene>().forEach { it.setPaletteLut(paletteLutTex) }
        fadeProgram = GlUtil.buildProgram(loadRaw(R.raw.fade_vert), loadRaw(R.raw.fade_frag))
        trailWarpProgram = GlUtil.buildProgram(loadRaw(R.raw.fade_vert), loadRaw(R.raw.trail_warp_frag))
        compositeSource = loadRaw(R.raw.composite_frag)
        baseCompositeProgram = CompositeProgram(GlUtil.buildProgram(loadRaw(R.raw.fade_vert), compositeSource))
        compositeProgram = baseCompositeProgram
        // Variants belong to the lost context; their names are dead now, and
        // their cached locations go with them because they are the same object.
        transitionPrograms.clear()
        activeTransition = null
        fadeLocs.clear()
        trailLocs.clear()
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        quadVao = ids[0]
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        lastFrameMs = SystemClock.elapsedRealtime()
    }

    override fun onSurfaceChanged(
        gl: GL10?,
        width: Int,
        height: Int,
    ) {
        this.width = width
        this.height = height
        GLES30.glViewport(0, 0, width, height)
        // Render scenes into supersampled FBOs (1.4x per axis ~= 2x the pixels),
        // then downsample with LINEAR filtering at composite time. This is the
        // fix for visible pixelation/aliasing when the Zoom customization
        // magnifies the image: the extra source resolution gives the zoom more
        // detail to sample instead of blowing up screen-resolution texels.
        // Capped so 4K-class displays don't exceed sane FBO sizes.
        val ss = supersampleFactor(width, height)
        renderWidth = (width * ss).toInt()
        renderHeight = (height * ss).toInt()
        scenes.values.forEach { it.resize(renderWidth, renderHeight) }
        flowField?.resize(renderWidth, renderHeight)
        rippleOverlay?.resize(renderWidth, renderHeight)
        fboA.ensure(renderWidth, renderHeight)
        fboB.ensure(renderWidth, renderHeight)
    }

    /** 1.4x supersample on typical screens, easing to 1.0x on very large ones. */
    private fun supersampleFactor(
        width: Int,
        height: Int,
    ): Float {
        val longest = maxOf(width, height)
        return when {
            longest >= 2200 -> 1.0f
            longest >= 1600 -> 1.25f
            else -> 1.4f
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        // State contract: undo anything the previous frame's scenes (native
        // projectM especially) left dirty before any pass runs.
        GlUtil.resetFrameState()
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastFrameMs).coerceIn(1, 100)) / 1000f
        lastFrameMs = now
        timeSeconds = (timeSeconds + dt) % TIME_WRAP_SEC

        while (true) {
            val (sceneId, src) = pendingCustomShaders.poll() ?: break
            (scenes[sceneId] as? ShaderScene)?.setFragmentSource(src)
        }
        val requested = scenes[requestedSceneId]
        var sceneJustSwitched = false
        if (requested != null && requested !== activeScene) {
            val cuts = TransitionCatalog.builtIn(transitionId) == TransitionStyle.CUT
            if (!cuts && activeScene != null) {
                outgoingScene = activeScene
                outgoingParams = lastFinalParams
                transitionStartMs = now
            }
            activeScene = requested
            sceneJustSwitched = true
        }
        val scene = activeScene ?: return
        // Settings fade: exponentially approach the target params so preset
        // and slider changes glide instead of jumping. Toggles/choices snap.
        // A transient preset morph can lengthen the fade without ever being
        // written into (and persisted with) the params themselves.
        val morph =
            if (morphRemainSec > 0f) {
                morphRemainSec -= dt
                morphFadeSec
            } else {
                0f
            }
        val fade = maxOf(sceneParams.paramFadeSec, morph)
        displayedParams =
            if (fade <= 0.01f) {
                sceneParams
            } else {
                val k = (dt / fade).coerceIn(0f, 1f)
                lerpParams(displayedParams, sceneParams, k)
            }
        // Envelopes first: their outputs can drive LFO rate/depth, so the
        // offsets must exist before the LFOs tick.
        val envValues = adsrEngine.tick(dt, features)
        AdsrEngine.lfoOffsets(adsrEngine.configs, envValues, envRateOffsets, envDepthOffsets)
        val lfoValues = lfoEngine.tick(dt, features.bpm, envRateOffsets, envDepthOffsets, safety)
        var p = LfoEngine.apply(displayedParams, lfoEngine.configs, lfoValues)
        p = AdsrEngine.apply(p, adsrEngine.configs, envValues)
        // LAST, after every modulator: a safe stored value is worth nothing if
        // an LFO or envelope can push it back into the hazardous range, so the
        // photosensitivity clamp sees the numbers the scenes actually get.
        // An exact no-op (same instance) while Safe visuals is off.
        p = VisualSafety.apply(p, safety)
        lastFinalParams = p
        postRotationAngle = CompositeGrade.integrateRotation(postRotationAngle, p.rotation, dt)
        postCyclePhase = CompositeGrade.integrateCyclePhase(postCyclePhase, p.cycleSpeed, dt, p.colorCycle)
        postBeatPulse = CompositeGrade.integrateBeatPulse(postBeatPulse, features.motionImpulse, dt)
        if (fluidInjectionDirty) {
            fluidInjectionDirty = false
            (scenes[SceneIds.FLUID] as? dev.musicviz.render.fluid.FluidScene)
                ?.setInjectionShaders(fluidForceSrc, fluidDyeSrc)
        }
        // F7 FlowField: advance the shared velocity field (its own tiny FBOs)
        // before any scene target is bound. When the FLUID scene is active
        // its own field is reused instead - never both (one source of truth).
        val ff = flowField
        val fluidActive = scene is dev.musicviz.render.fluid.FluidScene
        // A field-DEFINED particle style (Inkflow) runs the service whatever
        // the Flow toggle says: `flowEnabled` ships off, and a style that
        // renders a frozen screen until the user finds a checkbox in another
        // tab would read as broken, not as opt-in.
        val sceneNeedsFlow = (scene as? ParticleSceneBase)?.requiresFlowField == true
        if ((p.flowEnabled || sceneNeedsFlow) && ff != null && ff.available && !fluidActive) {
            ff.step(gainAdjusted(features, p), dt, p)
        }
        // F2 ripple overlay: advance the shared heightfield (its own tiny
        // FBOs) before any scene target is bound. When the WATER scene is
        // active its own sim already refracts the display - never both (one
        // source of truth, no double-applied refraction), mirroring the
        // FLUID/FlowField exclusivity above.
        val ripple = rippleOverlay
        val waterActive = scene is dev.musicviz.render.fluid.WaterScene
        // Touch smear: a finger drag borrows the overlay even when the user
        // never switched it on, and keeps it alive for TOUCH_LINGER_MS after
        // the last stroke so the rings it left decay away instead of popping
        // out of existence the moment the finger lifts.
        val smearing = now - lastTouchMs < TOUCH_LINGER_MS
        drainTouchStrokes(scene, ripple)
        val rippleOverlayOn =
            (p.rippleOverlayEnabled || smearing) && ripple != null && ripple.available && !waterActive
        if (rippleOverlayOn) {
            ripple.waveSpeed = 1.2f * p.waterWaveSpeed.coerceIn(0.2f, 2f)
            ripple.damping = p.waterDamping.coerceIn(0.9f, 0.999f)
            rippleDrops.tick(gainAdjusted(features, p), ripple.aspect) { x, y, radius, amp ->
                ripple.queueDrop(x, y, radius, amp)
            }
            ripple.step(dt)
        }
        fboA.ensure(renderWidth, renderHeight)
        fboB.ensure(renderWidth, renderHeight)

        var progress = 1f
        val outgoing = outgoingScene
        // Layers and transitions both want FBO B, so a transition WINS: it is
        // brief and it is the thing the user just asked for, while the layer is
        // a standing setting that can resume a second later. Resolved every
        // frame because layerSceneId is written from another thread.
        layerScene =
            if (outgoing != null) {
                null
            } else {
                layerSceneId
                    ?.takeIf { it != requestedSceneId }
                    ?.let { scenes[it] }
                    ?.takeIf { it !== activeScene }
            }
        val layer = layerScene
        if (layer != null) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB.fbo)
            GLES30.glViewport(0, 0, renderWidth, renderHeight)
            // Always cleared: the layer has no trail state of its own, and
            // letting it accumulate would build an ever-brighter plate under
            // the active scene that no control could clear.
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            layer.setParams(p)
            layer.update(gainAdjusted(features, p), dt)
            layer.draw(timeSeconds)
        }
        if (outgoing != null) {
            progress = ((now - transitionStartMs).toFloat() / transitionDurationMs).coerceIn(0f, 1f)
            if (progress >= 1f) {
                outgoingScene = null
                outgoingParams = null
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboB.fbo)
                GLES30.glViewport(0, 0, renderWidth, renderHeight)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                val op = outgoingParams ?: p
                outgoing.setParams(op)
                outgoing.update(gainAdjusted(features, op), dt)
                outgoing.draw(timeSeconds)
            }
        }

        // Active scene renders into FBO A (fade instead of clear for trails).
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboA.fbo)
        GLES30.glViewport(0, 0, renderWidth, renderHeight)
        // Curl Flow always persists: it draws bare GL_POINTS, which need canvas
        // echo to read as streams rather than strobing dots, and SceneParams
        // .trails defaults to FALSE - so gating its persistence on the toggle
        // alone made the style strobe the moment it was selected from Styles
        // (only the one built-in "Streams" preset sets trails = true).
        // The toggle stays live all the same: CurlFlowMath.retention gives
        // Trails OFF a short, fixed echo (OFF_RETENTION, motion blur) and
        // Trails ON the whole Trail length slider remapped onto its long
        // streaming band. Every other scene keeps the plain toggle gate.
        val isCurl = scene is dev.musicviz.render.fluid.CurlFlowScene
        // The beam is phosphor: a trace with no persistence is a single-frame
        // wire, and the decay between frames IS the afterglow. Like Curl Flow
        // it persists regardless of the Trails toggle, which still sets how
        // long the glow lasts.
        val isBeam = scene is BeamScene
        val persists = isCurl || isBeam || (p.trails && scene is ParticleSceneBase)
        if (persists && !sceneJustSwitched) {
            val keep =
                when {
                    isCurl -> CurlFlowMath.retention(p.trailLength, p.trails)
                    // Phosphor: a floor so the trace always has an afterglow,
                    // with the Trail length slider setting how long above it.
                    isBeam -> (0.55f + 0.44f * p.trailLength).coerceIn(0f, 0.99f)
                    else -> p.trailLength
                }
            if (p.trailZoom != 0f || p.trailWarp > 0f) {
                drawTrailWarp(p, keep, timeSeconds, dt)
            } else {
                // Retention^(dt*60): same look as the old per-frame constant
                // at 60 Hz, but trail length no longer halves on 120 Hz panels.
                drawFadeQuad(1f - (keep * 0.97f).pow(dt * 60f))
            }
        } else {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        // FlowField consumers on the active scene: CPU grid for particle
        // scenes (CPU_GRID readback), uFlow sampler for shader scenes.
        if ((p.flowEnabled || sceneNeedsFlow) && ff != null) {
            if (scene is ParticleSceneBase && (p.flowAdvectParticles || sceneNeedsFlow) && ff.available) {
                ff.readback(ff.velocityTex, ff.flowScale, ff.aspect)
                scene.flowGrid = ff.cpuGrid
            } else if (scene is ParticleSceneBase) {
                scene.flowGrid = null
            }
            if (scene is ShaderScene && p.flowEnabled) {
                scene.setFlow(if (ff.available) ff.velocityTex else zeroTex, p.flowStrength)
            }
        } else {
            (scene as? ParticleSceneBase)?.flowGrid = null
            (scene as? ShaderScene)?.setFlow(zeroTex, 0f)
        }
        scene.setParams(p)
        scene.update(gainAdjusted(features, p), dt)
        // Two-way coupling, the return leg: a particle style that rides the
        // field can also push into it. Drained here, right after the update
        // that produced them, so the kicks are queued before the next frame's
        // step() consumes them - one frame of latency, and the field carries a
        // trace of where the population has been.
        if (ff != null && ff.available && !fluidActive && scene is ParticleSceneBase) {
            val kicks = scene.flowKicks
            for (i in 0 until kicks.size) {
                ff.queueKick(kicks.x[i], kicks.y[i], kicks.vx[i], kicks.vy[i], kicks.radius[i])
            }
            kicks.clear()
        }
        scene.draw(timeSeconds)

        // Composite to screen.
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        // Pick the composite variant BEFORE any uniform upload: cLoc() resolves
        // against whichever program is bound, so setting uniforms first would
        // write them into the previous variant.
        compositeProgram = transitionProgram(transitionId)
        activeTransition = TransitionCatalog.definition(context, transitionId)
        GLES30.glUseProgram(compositeProgram.handle)
        activeTransition?.let { TransitionCatalog.uploadParams(compositeProgram.handle, it) }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboA.tex)
        GLES30.glUniform1i(cLoc("uTexA"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboB.tex)
        GLES30.glUniform1i(cLoc("uTexB"), 1)
        // fluidWarp: bend the scene fetch through the flow field. The FLUID
        // scene contributes its own velocity texture; anything else uses the
        // FlowField service; a 1x1 zero texture keeps the sampler valid off.
        var flowTex = zeroTex
        var flowStrength = 0f
        if (p.flowEnabled) {
            val fluidScene = scene as? dev.musicviz.render.fluid.FluidScene
            if (fluidScene != null && fluidScene.simAvailable) {
                flowTex = fluidScene.velocityTexture
                flowStrength = p.flowStrength
            } else if (ff != null && ff.available) {
                flowTex = ff.velocityTex
                flowStrength = p.flowStrength
            }
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, flowTex)
        GLES30.glUniform1i(cLoc("uFlow"), 2)
        GLES30.glUniform1f(cLoc("uFlowStrength"), flowStrength)
        // F2 ripple overlay: refraction + glint over any style. The zero
        // texture keeps the sampler valid when the overlay is off or WATER
        // is active (whose own display already refracts - see step site).
        var rippleTex = zeroTex
        var rippleTexelW = 0f
        var rippleTexelH = 0f
        var rippleStrength = 0f
        var rippleSpecular = 0f
        if (rippleOverlayOn) {
            rippleTex = ripple.heightTex
            rippleTexelW = ripple.texelW
            rippleTexelH = ripple.texelH
            // While smearing, the overlay is floored so the finger is visible
            // even with the user's own strength at zero - the touch is the
            // request, and an invisible response reads as a broken feature.
            rippleStrength =
                if (smearing) {
                    maxOf(p.rippleOverlayStrength, TOUCH_MIN_OVERLAY_STRENGTH).coerceIn(0f, 1f)
                } else {
                    p.rippleOverlayStrength.coerceIn(0f, 1f)
                }
            rippleSpecular = p.rippleOverlaySpecular.coerceIn(0f, 1f)
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rippleTex)
        GLES30.glUniform1i(cLoc("uRipple"), 3)
        GLES30.glUniform2f(cLoc("uRippleTexel"), rippleTexelW, rippleTexelH)
        GLES30.glUniform1f(cLoc("uRippleStrength"), rippleStrength)
        GLES30.glUniform1f(cLoc("uRippleSpecular"), rippleSpecular)
        // Dither mask on unit 4, applied at the very end of the composite. The
        // amount is 0 when the tile could not be loaded, so the pass stays an
        // exact no-op rather than sampling an unbound texture.
        GLES30.glActiveTexture(GLES30.GL_TEXTURE4)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTex)
        GLES30.glUniform1i(cLoc("uNoise"), 4)
        GLES30.glUniform1f(cLoc("uDither"), if (noiseTex != 0) BlueNoise.DITHER_AMOUNT else 0f)
        GLES30.glUniform1f(cLoc("uProgress"), progress)
        GLES30.glUniform1f(cLoc("uLayerMix"), layerMix.coerceIn(0f, 1f))
        GLES30.glUniform1i(cLoc("uBlendMode"), layerBlend.ordinal)
        // uStyle tells the shader what to do THIS frame. Outside a transition
        // it is always CUT (draw the incoming scene), whichever transition is
        // selected - the spliced variant stays bound between switches rather
        // than swapping programs every time one ends.
        val styleValue =
            when {
                // A transition owns FBO B while it runs, so layerScene is null
                // here by construction - the two can never both claim uTexB.
                layerScene != null -> STYLE_LAYER
                outgoingScene == null -> TransitionStyle.CUT.ordinal
                activeTransition != null -> TransitionCatalog.STYLE_LIBRARY
                else -> transitionStyle.ordinal
            }
        GLES30.glUniform1i(cLoc("uStyle"), styleValue)
        // `ratio` in the gl-transitions contract: the aspect of what is being
        // composited, not of the window, so a supersampled target is honest.
        GLES30.glUniform1f(cLoc("uRatio"), renderWidth.toFloat() / renderHeight.toFloat())
        val fx = lastFinalParams
        GLES30.glUniform1f(cLoc("uTime"), timeSeconds)
        GLES30.glUniform1f(cLoc("uBeat"), features.beatImpulse)
        GLES30.glUniform1f(cLoc("uChroma"), fx.chromaAb)
        GLES30.glUniform1f(cLoc("uVignette"), fx.vignette)
        GLES30.glUniform1f(cLoc("uScanline"), fx.scanlines)
        GLES30.glUniform1f(cLoc("uGrain"), fx.grain)
        GLES30.glUniform1f(cLoc("uGlitch"), fx.glitch)
        GLES30.glUniform1f(cLoc("uFisheye"), fx.fisheye)
        GLES30.glUniform1f(cLoc("uStrobe"), fx.strobe)
        GLES30.glUniform1f(cLoc("uStrobeHz"), VisualSafety.strobeHz(safety))
        // Universal geometric + color effects for scenes whose own pipeline
        // can't honor them (particles, milkdrop, the fluid family). Every
        // uPost* below is uploaded RAW; which groups actually run is decided
        // per TEXTURE by uGateA/uGateB (see the gate upload after this block),
        // because composite_frag routes the outgoing texture through the same
        // postFx() and a transition can cross scene families. Shader scenes
        // already apply all of these in-shader via view()/grade(), so their
        // gate is off - otherwise warp/ripple/kaleido/pixelate/tile/twist/
        // bloom/posterize would each apply TWICE (double warp strength, double
        // kaleido segmentation, double-quantized posterize, over-bloom).
        GLES30.glUniform1f(cLoc("uPostWarp"), fx.warp)
        GLES30.glUniform1f(cLoc("uPostRipple"), fx.ripple)
        GLES30.glUniform1f(cLoc("uPostSymmetry"), fx.symmetry.toFloat())
        GLES30.glUniform1f(cLoc("uPostKaleido"), if (fx.kaleidoscope) 1f else 0f)
        GLES30.glUniform1f(cLoc("uPostPixelate"), fx.pixelate)
        GLES30.glUniform1f(cLoc("uPostTile"), fx.tile)
        GLES30.glUniform1f(cLoc("uPostTwist"), fx.twist)
        GLES30.glUniform1f(cLoc("uPostBloom"), fx.bloom)
        GLES30.glUniform1f(cLoc("uPostPosterize"), fx.posterize)
        GLES30.glUniform1f(cLoc("uPostDriftX"), fx.driftX)
        GLES30.glUniform1f(cLoc("uPostDriftY"), fx.driftY)
        GLES30.glUniform1f(cLoc("uPostSway"), fx.sway)
        GLES30.glUniform1f(cLoc("uPostShake"), fx.shake)
        GLES30.glUniform1f(cLoc("uPostFlash"), fx.flash)
        GLES30.glUniform1f(cLoc("uPostTemp"), fx.temperature)
        GLES30.glUniform1f(cLoc("uPostSolarize"), if (fx.solarize) 1f else 0f)
        // Mirror/invert: shader scenes AND the milkdrop post pass handle these
        // themselves. Everything else needs them here - particle scenes (whose
        // fragment shader explicitly defers invert to this pass) and the fluid
        // family, which was previously excluded and so had no mirror/invert at
        // all. (Gate component y.)
        GLES30.glUniform1f(cLoc("uPostMirror"), if (fx.mirror) 1f else 0f)
        GLES30.glUniform1f(cLoc("uPostInvert"), if (fx.invert) 1f else 0f)
        // Universal grading + zoom/rotation (gate component z), a SMALLER set
        // of scenes than the geometry group: ShaderScene (view()/grade()), the
        // particle pipeline (particle_vert's uZoom/uRotation, particle_frag's
        // uSat/uBright/uContrast/uGamma) and the milkdrop post pass all apply
        // these in their OWN pass, so the gate is off for them - otherwise
        // every one of them would be zoomed twice and graded twice (squared
        // brightness, doubled contrast). Only scenes that grade nothing
        // themselves - the fluid family (Fluid, Curl Flow, Water) - are graded
        // here, which is what made Zoom/Rotation/Saturation/Brightness/
        // Contrast/Gamma/Hue/Intensity dead on those styles. The gate switches
        // the shader block off wholesale, so the off case is an exact no-op.
        GLES30.glUniform1f(cLoc("uPostZoom"), fx.zoom)
        GLES30.glUniform1f(cLoc("uPostRotation"), postRotationAngle)
        GLES30.glUniform1f(cLoc("uPostSat"), fx.saturation)
        GLES30.glUniform1f(cLoc("uPostBright"), CompositeGrade.brightness(fx.brightness, fx.intensity))
        GLES30.glUniform1f(cLoc("uPostContrast"), fx.contrast)
        GLES30.glUniform1f(cLoc("uPostGamma"), fx.gamma)
        GLES30.glUniform1f(cLoc("uPostHue"), fx.colorShift + postCyclePhase)
        // "Beat pulse": gate component w, a DIFFERENT set from the grade on
        // purpose. Only two scene families read SceneParams.pulse themselves -
        // ShaderScene (uPulse, folded into view()'s zoom) and the particle
        // pipeline (a uSize swell). ProjectMScene is in the grading exclusion
        // set but NOT this one: the milkdrop post pass grades and zooms, yet
        // nothing in it or in pm_post_frag reads pulse, so before this upload
        // the slider was inert on MilkDrop exactly as on the fluid family.
        GLES30.glUniform1f(cLoc("uPostPulse"), CompositeGrade.pulseAmount(fx.pulse, postBeatPulse))
        // One gate per texture. uTexA is the ACTIVE scene, uTexB the OUTGOING
        // one, and they can belong to different families for the whole length
        // of a cross-family transition: gating both from the active scene
        // graded the outgoing julia frame a second time on a julia -> fluid
        // fade (white, over-zoomed flash) and dropped the outgoing fluid grade
        // on the reverse. Outside a transition uTexB is unread (uStyle = CUT),
        // so it simply carries the active gate.
        val gateA = CompositeGrade.gateFor(compositeFamily(activeScene))
        // uTexB is the outgoing scene during a transition, the bottom layer
        // while Layers is on, and unread otherwise - and each of those is a
        // DIFFERENT family whose grade the composite may or may not own. A
        // fluid layer under a shader scene needs the fluid gate or its zoom and
        // rotation are applied nowhere.
        val gateB =
            CompositeGrade.gateFor(compositeFamily(layerScene ?: outgoingScene ?: activeScene))
        GLES30.glUniform4fv(cLoc("uGateA"), 1, gateA.toVec4(), 0)
        GLES30.glUniform4fv(cLoc("uGateB"), 1, gateB.toVec4(), 0)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /**
     * Hands queued finger drags to whichever surface owns the touch this
     * frame, converting normalized screen coordinates (y down) into sim space
     * (y up, x scaled by aspect).
     */
    private fun drainTouchStrokes(
        scene: Scene,
        ripple: dev.musicviz.render.fluid.RippleSim?,
    ) {
        // WaterScene owns the touch when it is the active style, exactly as it
        // owns the refraction - the overlay stays off there, so routing a
        // stroke to both would be two responses to one finger.
        val water = scene as? dev.musicviz.render.fluid.WaterScene
        // HYPERSPACE gets the stroke too: its medium is what molds the
        // fractals, so a drag across the screen pulls the geometry it crosses
        // out of shape and stains it in the same gesture.
        val hyper = scene as? HyperspaceScene
        // WaterScene takes normalized coordinates and scales by its own sim
        // aspect; the shared overlay is scaled here, from its own.
        val aspect = ripple?.aspect ?: 1f
        while (true) {
            val st = touchStrokes.poll() ?: return
            if (hyper != null) {
                hyper.queueTouchStroke(
                    st.nx * 2f - 1f,
                    1f - st.ny * 2f,
                    st.ndx * 2f,
                    -st.ndy * 2f,
                    st.strength,
                )
            } else if (water != null) {
                water.queueTouchStroke(
                    st.nx * 2f - 1f,
                    1f - st.ny * 2f,
                    st.ndx * 2f,
                    -st.ndy * 2f,
                    st.dt,
                    st.strength,
                )
            } else if (ripple != null && ripple.available) {
                ripple.queueStroke(
                    (st.nx * 2f - 1f) * aspect,
                    1f - st.ny * 2f,
                    st.ndx * 2f * aspect,
                    -st.ndy * 2f,
                    st.dt,
                    TOUCH_RADIUS,
                    st.strength,
                )
            }
        }
    }

    /**
     * Which composite-pass gate a scene falls under. The composite-graded
     * family - Fluid, Curl Flow, Water, Cymatics and Hyperspace - is the
     * `else` branch:
     * none of them has a grading pass of its own, so the composite owns every
     * group for them.
     */
    private fun compositeFamily(scene: Scene?): CompositeGrade.SceneFamily =
        when (scene) {
            is ShaderScene -> CompositeGrade.SceneFamily.SHADER
            is ParticleSceneBase -> CompositeGrade.SceneFamily.PARTICLE
            is ProjectMScene -> CompositeGrade.SceneFamily.MILKDROP
            else -> CompositeGrade.SceneFamily.FLUID
        }

    /**
     * Feedback-trail warp: copies the persisted frame aside, then redraws it
     * into the scene FBO slightly zoomed/warped and decayed (blend off - the
     * resample is the new base). Falls back to the plain fade when the trail
     * buffer can't be sized.
     *
     * [retention] is the caller's frame-retention factor, normally
     * `p.trailLength` but remapped for styles with their own persistence band
     * (see CurlFlowMath), so the warp path decays at the same rate as the plain
     * fade path.
     */
    private fun drawTrailWarp(
        p: SceneParams,
        retention: Float,
        timeSeconds: Float,
        dt: Float,
    ) {
        ensureTrailBuffer()
        if (trailFbo == 0) {
            drawFadeQuad(1f - (retention * 0.97f).pow(dt * 60f))
            return
        }
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fboA.fbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, trailFbo)
        GLES30.glBlitFramebuffer(
            0,
            0,
            renderWidth,
            renderHeight,
            0,
            0,
            renderWidth,
            renderHeight,
            GLES30.GL_COLOR_BUFFER_BIT,
            GLES30.GL_NEAREST,
        )
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboA.fbo)
        GLES30.glViewport(0, 0, renderWidth, renderHeight)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(trailWarpProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex)

        fun tLoc(n: String) = trailLocs.getOrPut(n) { GLES30.glGetUniformLocation(trailWarpProgram, n) }
        GLES30.glUniform1i(tLoc("uPrev"), 0)
        // [retention], NOT p.trailLength: styles with their own persistence
        // band (Curl Flow) hand in a remapped value, and reading the raw
        // slider here made the warp path decay faster than the fade path -
        // Curl Flow's streams broke into strobing dots the moment Trail zoom
        // or Trail warp went non-zero.
        GLES30.glUniform1f(tLoc("uDecay"), CurlFlowMath.warpDecay(retention, dt))
        GLES30.glUniform1f(tLoc("uZoom"), p.trailZoom)
        GLES30.glUniform1f(tLoc("uWarp"), p.trailWarp)
        GLES30.glUniform1f(tLoc("uTime"), timeSeconds)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun ensureTrailBuffer() {
        if (trailTex != 0 && trailW == renderWidth && trailH == renderHeight) return
        if (trailTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(trailTex), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(trailFbo), 0)
            trailTex = 0
            trailFbo = 0
        }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        trailTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, trailTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA8,
            renderWidth,
            renderHeight,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null,
        )
        GLES30.glGenFramebuffers(1, ids, 0)
        trailFbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, trailFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, trailTex, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glDeleteTextures(1, intArrayOf(trailTex), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(trailFbo), 0)
            trailTex = 0
            trailFbo = 0
        }
        trailW = renderWidth
        trailH = renderHeight
    }

    private var trailW = 0
    private var trailH = 0

    private fun drawFadeQuad(alpha: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(fadeProgram)
        GLES30.glUniform1f(
            fadeLocs.getOrPut("uFadeAlpha") { GLES30.glGetUniformLocation(fadeProgram, "uFadeAlpha") },
            alpha.coerceIn(0.02f, 1f),
        )
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /**
     * Reads a shader, resolving its `//#include` directives - so every source
     * that reaches a driver here has its libraries already spliced in.
     */
    private fun loadRaw(resId: Int): String = GlUtil.loadShader(context, resId)

    /**
     * Builds fresh scene instances for the export GL context. Never reuses
     * live-context objects: GL handles are not shareable across contexts.
     */
    fun exportSceneFactory(sceneId: String): VideoExporter.SceneFactory =
        object : VideoExporter.SceneFactory {
            override fun create(): Scene {
                val exportParams = sceneParams
                val particleShaders = particleShaderSources(context)
                val quadVert = loadRaw(R.raw.quad_vert)
                val scene: Scene =
                    when {
                        sceneId == SceneIds.FLUID ->
                            dev.musicviz.render.fluid.FluidScene(context).also {
                                it.setInjectionShaders(fluidForceSrc, fluidDyeSrc)
                            }
                        sceneId == SceneIds.CURLFLOW ->
                            dev.musicviz.render.fluid
                                .CurlFlowScene(context)
                        sceneId == SceneIds.WATER ->
                            dev.musicviz.render.fluid
                                .WaterScene(context)
                        sceneId == SceneIds.CYMATICS -> CymaticsScene(context)
                        sceneId == SceneIds.BEAM -> BeamScene(context)
                        sceneId == SceneIds.HYPERSPACE -> HyperspaceScene(context)
                        sceneId == SceneIds.MILKDROP && PMBridge.available ->
                            ProjectMScene(
                                postVertexSrc = loadRaw(R.raw.fade_vert),
                                postFragmentSrc = loadRaw(R.raw.pm_post_frag),
                                sharedTextureDir = File(context.filesDir, "milk/textures").absolutePath,
                                // null -> the scene feeds itself from the export
                                // timeline's per-frame waveform in update().
                                pcmProvider = { null },
                            ).also { pm ->
                                // Without this the export renders projectM's
                                // default idle preset instead of what's on
                                // screen.
                                lastMilkPreset?.let { pm.queuePreset(it) }
                            }
                        sceneId == SceneIds.BURSTS -> BurstScene(particleShaders)
                        sceneId == SceneIds.SWARM -> SwarmScene(particleShaders)
                        sceneId == SceneIds.FOUNTAIN -> FountainScene(particleShaders)
                        sceneId == SceneIds.ORBITS -> OrbitScene(particleShaders)
                        sceneId == SceneIds.GALAXY -> GalaxyScene(particleShaders)
                        sceneId == SceneIds.ATTRACTOR -> AttractorScene(particleShaders)
                        sceneId == SceneIds.STORM -> StormScene(particleShaders)
                        sceneId == SceneIds.INKFLOW -> InkflowScene(particleShaders)
                        SHADER_SCENES.containsKey(sceneId) ->
                            ShaderScene(sceneId, quadVert, activeCustomShaders[sceneId] ?: loadRaw(SHADER_SCENES.getValue(sceneId)))
                        else -> NebulaScene(particleShaders)
                    }
                scene.setParams(exportParams)
                return scene
            }
        }

    private fun particleShaderSources(context: Context): ParticleSceneBase.ShaderSources {
        // The app-wide particle look, shared with the fluid styles' own
        // particle layer. Both stages include lib_particle_common (constants,
        // the SDF shapes, the billboard and sub-pixel math); only the fragment
        // stage includes lib_particle_shade, which antialiases with fwidth()
        // and would not compile in a vertex shader. Which libraries a stage
        // takes is stated in the shader that needs them rather than assembled
        // here, so the two particle families cannot drift apart.
        return ParticleSceneBase.ShaderSources(
            loadRaw(R.raw.particle_vert),
            loadRaw(R.raw.particle_frag),
        )
    }
}
