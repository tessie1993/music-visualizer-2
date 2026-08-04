package dev.musicviz.render

import dev.musicviz.render.scene.SceneParams
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/** Waveforms available to an LFO. */
enum class LfoWave(
    val label: String,
) {
    SINE("Sin"),
    TRIANGLE("Tri"),
    SAW("Saw"),
    SQUARE("Sqr"),
    RANDOM("S&H"),
}

/**
 * Parameters an LFO can modulate. Includes chain targets: an LFO may drive
 * the rate or depth of a HIGHER-numbered LFO (evaluation runs in index
 * order, so chains flow 1 -> 2 -> 3 without feedback loops).
 */
enum class LfoTarget(
    val label: String,
) {
    NONE("None"),
    SPEED("Speed"),
    ZOOM("Zoom"),
    ROTATION("Rotation"),
    SWAY("Sway"),
    PULSE("Pulse"),
    DRIFT_X("Drift X"),
    DRIFT_Y("Drift Y"),
    WARP("Warp"),
    RIPPLE("Ripple"),
    MORPH("Morph"),
    TWIST("Twist"),
    TILE("Tile"),
    PIXELATE("Pixelate"),
    POSTERIZE("Posterize"),
    COLOR_SHIFT("Hue shift"),
    PALETTE_MIX("Palette blend"),
    SATURATION("Saturation"),
    BRIGHTNESS("Brightness"),
    INTENSITY("Intensity"),
    BLOOM("Bloom"),
    TEMPERATURE("Temperature"),
    TURBULENCE("Turbulence"),
    CHROMA_AB("Chroma AB"),
    VIGNETTE("Vignette"),
    GLITCH("Glitch"),
    FISHEYE("Fisheye"),
    PARTICLE_SIZE("Particle size"),
    TRAIL_LENGTH("Trail length"),
    FLUID_CURL("Fluid curl"),
    FLUID_RADIUS("Fluid splat radius"),
    FLUID_FORCE("Fluid splat force"),
    FLUID_GLOW("Fluid glow"),
    FLUID_FADE("Fluid fade"),
    FLUID_CATCH_PULL("Catch pull"),
    FLUID_CATCH_RADIUS("Catch radius"),
    FLOW_STRENGTH("Flow strength"),
    WATER_RIPPLE("Ripple amp"),
    RIPPLE_OVERLAY("Ripple ovl"),
    LFO1_RATE("LFO1 rate"),
    LFO1_DEPTH("LFO1 depth"),
    LFO2_RATE("LFO2 rate"),
    LFO2_DEPTH("LFO2 depth"),
    LFO3_RATE("LFO3 rate"),
    LFO3_DEPTH("LFO3 depth"),
}

/** One LFO's configuration. Rate is Hz, or a beat division when [beatSync]. */
data class LfoConfig(
    val enabled: Boolean = false,
    val target: LfoTarget = LfoTarget.NONE,
    val wave: LfoWave = LfoWave.SINE,
    val rateHz: Float = 0.5f,
    val beatSync: Boolean = false,
    /** Cycle length in beats when beat-synced (0.25 = 16th ... 8 = 2 bars). */
    val beatDiv: Float = 1f,
    val depth: Float = 0.3f,
)

/**
 * Evaluates up to three chained LFOs each frame on the GL thread. Chain
 * targets modify later LFOs' rate/depth for the current frame only.
 */
class LfoEngine {
    @Volatile
    var configs: List<LfoConfig> = List(3) { LfoConfig() }

    private val phases = FloatArray(3)
    private val sampleHold = FloatArray(3)

    /** S&H cycle counter, wrapped to [SH_PHASE_WRAP] - see that constant. */
    private val totalPhase = FloatArray(3)
    private val lastCycle = IntArray(3) { -1 }

    /**
     * Per-tick scratch, reused instead of reallocated every frame: this runs
     * in the draw path sixty times a second. Safe to reuse because [tick]
     * overwrites all three slots of each array before reading them and
     * nothing outside this class ever sees them - the chain targets mutate
     * [rateAdd]/[depthAdd] only within one call, and [out] is the value the
     * caller consumes before the next tick.
     *
     * [out] follows [AdsrEngine.tick], which already returns a reused array.
     * The contract that comes with that: the returned array is this frame's
     * view, not a snapshot, so a caller that wants to keep values across
     * ticks must copy them.
     */
    private val out = FloatArray(3)
    private val rateAdd = FloatArray(3)
    private val depthAdd = FloatArray(3)

    /**
     * Advances phases and returns each LFO's bipolar output value.
     *
     * [safety] caps the rate of any LFO pointed at a full-frame luminance
     * param. Clamping the VALUES afterwards (which `VisualSafety.apply` does)
     * bounds where the oscillation goes but not how fast it gets there, and a
     * square-wave LFO swinging brightness at the 30 Hz ceiling below is the
     * worst flash the app can produce - reachable by rate-chaining, or by the
     * randomizer rolling an LFO onto Brightness.
     */
    fun tick(
        dt: Float,
        bpm: Float,
        extRateAdd: FloatArray? = null,
        extDepthAdd: FloatArray? = null,
        safety: VisualSafety.SafetyConfig = VisualSafety.SafetyConfig.OFF,
    ): FloatArray {
        val cfgs = configs
        // Seed with external offsets (ADSR envelopes driving LFO rate/depth).
        // Copied in rather than aliased: the chain targets below add into
        // these, and the caller's arrays must not come back mutated (that was
        // `copyOf(3)`'s job, kept here without the two allocations).
        for (i in 0 until 3) {
            out[i] = 0f
            rateAdd[i] = if (extRateAdd != null && i < extRateAdd.size) extRateAdd[i] else 0f
            depthAdd[i] = if (extDepthAdd != null && i < extDepthAdd.size) extDepthAdd[i] else 0f
        }
        for (i in 0 until minOf(3, cfgs.size)) {
            val c = cfgs[i]
            if (!c.enabled || c.target == LfoTarget.NONE) continue
            val baseRate =
                if (c.beatSync && bpm > 40f) {
                    bpm / 60f / c.beatDiv.coerceAtLeast(0.0625f)
                } else {
                    c.rateHz
                }
            val rate =
                VisualSafety.limitLfoRate(
                    (baseRate + rateAdd[i]).coerceIn(0.01f, 30f),
                    c.target,
                    safety,
                )
            val depth = (c.depth + depthAdd[i]).coerceIn(0f, 2f)
            phases[i] = (phases[i] + rate * dt) % 1f
            totalPhase[i] = (totalPhase[i] + rate * dt) % SH_PHASE_WRAP
            val ph = phases[i]
            val raw =
                when (c.wave) {
                    LfoWave.SINE -> sin(ph * 6.2831853f)
                    LfoWave.TRIANGLE -> 4f * abs(ph - 0.5f) - 1f
                    LfoWave.SAW -> ph * 2f - 1f
                    LfoWave.SQUARE -> if (ph < 0.5f) 1f else -1f
                    LfoWave.RANDOM -> {
                        // One new random value per full LFO cycle (the labeled
                        // rate), counted on [totalPhase] so the %1f wrap of
                        // [phases] can't retrigger or skip samples. Its own
                        // wrap is boundary-aligned: see [SH_PHASE_WRAP].
                        val cycle = floor(totalPhase[i]).toInt()
                        if (cycle != lastCycle[i]) {
                            lastCycle[i] = cycle
                            sampleHold[i] = (Math.random().toFloat() * 2f - 1f)
                        }
                        sampleHold[i]
                    }
                }
            val v = raw * depth
            out[i] = v
            when (c.target) {
                LfoTarget.LFO2_RATE -> if (i < 1) rateAdd[1] += v * 4f
                LfoTarget.LFO2_DEPTH -> if (i < 1) depthAdd[1] += v
                LfoTarget.LFO3_RATE -> if (i < 2) rateAdd[2] += v * 4f
                LfoTarget.LFO3_DEPTH -> if (i < 2) depthAdd[2] += v
                else -> {}
            }
        }
        return out
    }

    companion object {
        /**
         * Wrap period for [totalPhase], the S&H wave's cycle counter - the
         * float-absorption threat `VisualizerRenderer.TIME_WRAP_SEC`
         * documents, on the one accumulator in this engine that grows for
         * the life of the process. Unwrapped, an S&H LFO at the 30 Hz rate
         * ceiling crosses 2^23 after ~3 days of wallpaper uptime, where the
         * float32 ULP (1.0) starts absorbing a 60 fps frame's 0.5 advance;
         * past 2^24 (~a week) the advance is under half an ULP, the
         * accumulator stops moving, and the "random" wave holds one value
         * forever.
         *
         * Any whole-number period is invisible to the consumer: it compares
         * consecutive `floor()`s for INEQUALITY only, and the held value is
         * a fresh draw per transition rather than a hash of the cycle index,
         * so the 63 -> 0 step at the wrap is exactly the one transition that
         * cycle boundary owes anyway (a fractional period would put the wrap
         * mid-cycle and fire a spurious extra sample). 64 leaves margin both
         * ways: a single tick advances at most 30 Hz * 0.1 s (the renderer's
         * dt clamp) = 3, so no tick can lap the period; and the ULP just
         * below 64 (2^-18 ~ 3.8e-6) keeps even the smallest advance
         * (0.01 Hz at the renderer's 1 ms dt floor = 1e-5) above the
         * absorption threshold, so the counter cannot stall just beneath
         * the wrap the way it stalled at 2^24.
         */
        internal const val SH_PHASE_WRAP = 64f

        /** Applies LFO outputs onto [p], returning the modulated params. */
        fun apply(
            p: SceneParams,
            cfgs: List<LfoConfig>,
            values: FloatArray,
        ): SceneParams {
            var r = p
            for (i in values.indices) {
                val c = cfgs.getOrNull(i) ?: continue
                if (!c.enabled) continue
                r = applyTarget(r, c.target, values[i])
            }
            return r
        }

        /**
         * One modulator's contribution to one target: the whole
         * target -> field -> clamp table, in one place.
         *
         * Split out of [apply] so [AdsrEngine.apply] can route a target
         * through it directly. That used to go the long way round - a
         * throwaway `LfoConfig`, a `listOf` and a `floatArrayOf` per envelope
         * target per frame, just to describe a single (target, value) pair to
         * [apply]. Same arithmetic, same clamp order, three fewer objects per
         * target on every frame of a continuously-rendering loop.
         *
         * The `r.copy(...)` per call stays: see [AdsrEngine.apply] for why
         * the result has to be a fresh immutable snapshot.
         */
        internal fun applyTarget(
            r: SceneParams,
            target: LfoTarget,
            v: Float,
        ): SceneParams =
            when (target) {
                LfoTarget.SPEED -> r.copy(speed = (r.speed + v).coerceIn(0.05f, 4f))
                LfoTarget.ZOOM -> r.copy(zoom = (r.zoom + v).coerceIn(0.3f, 3f))
                LfoTarget.ROTATION -> r.copy(rotation = (r.rotation + v * 1.5f).coerceIn(-3f, 3f))
                LfoTarget.SWAY -> r.copy(sway = (r.sway + v).coerceIn(0f, 1f))
                LfoTarget.PULSE -> r.copy(pulse = (r.pulse + v).coerceIn(0f, 1f))
                LfoTarget.DRIFT_X -> r.copy(driftX = (r.driftX + v).coerceIn(-1f, 1f))
                LfoTarget.DRIFT_Y -> r.copy(driftY = (r.driftY + v).coerceIn(-1f, 1f))
                LfoTarget.WARP -> r.copy(warp = (r.warp + v).coerceIn(0f, 1f))
                LfoTarget.RIPPLE -> r.copy(ripple = (r.ripple + v).coerceIn(0f, 1f))
                LfoTarget.MORPH -> r.copy(morph = (r.morph + v).coerceIn(0f, 1f))
                LfoTarget.TWIST -> r.copy(twist = (r.twist + v).coerceIn(-1f, 1f))
                LfoTarget.TILE -> r.copy(tile = (r.tile + v * 2f).coerceIn(1f, 6f))
                LfoTarget.PIXELATE -> r.copy(pixelate = (r.pixelate + v).coerceIn(0f, 1f))
                LfoTarget.POSTERIZE -> r.copy(posterize = (r.posterize + v).coerceIn(0f, 1f))
                LfoTarget.COLOR_SHIFT -> r.copy(colorShift = r.colorShift + v * 0.5f)
                LfoTarget.PALETTE_MIX -> r.copy(paletteMix = (r.paletteMix + v).coerceIn(0f, 1f))
                LfoTarget.SATURATION -> r.copy(saturation = (r.saturation + v).coerceIn(0f, 1.5f))
                LfoTarget.BRIGHTNESS -> r.copy(brightness = (r.brightness + v).coerceIn(0.2f, 2f))
                LfoTarget.INTENSITY -> r.copy(intensity = (r.intensity + v).coerceIn(0.2f, 2f))
                LfoTarget.BLOOM -> r.copy(bloom = (r.bloom + v).coerceIn(0f, 1f))
                LfoTarget.TEMPERATURE -> r.copy(temperature = (r.temperature + v).coerceIn(-1f, 1f))
                LfoTarget.TURBULENCE -> r.copy(turbulence = (r.turbulence + v).coerceIn(0f, 1.5f))
                LfoTarget.CHROMA_AB -> r.copy(chromaAb = (r.chromaAb + v).coerceIn(0f, 1f))
                LfoTarget.VIGNETTE -> r.copy(vignette = (r.vignette + v).coerceIn(0f, 1f))
                LfoTarget.GLITCH -> r.copy(glitch = (r.glitch + v).coerceIn(0f, 1f))
                LfoTarget.FISHEYE -> r.copy(fisheye = (r.fisheye + v).coerceIn(-1f, 1f))
                LfoTarget.PARTICLE_SIZE -> r.copy(particleSize = (r.particleSize + v).coerceIn(0.3f, 2.5f))
                LfoTarget.TRAIL_LENGTH -> r.copy(trailLength = (r.trailLength + v).coerceIn(0.05f, 0.98f))
                LfoTarget.FLUID_CURL -> r.copy(fluidCurl = (r.fluidCurl + v * 25f).coerceIn(0f, 50f))
                LfoTarget.FLUID_RADIUS -> r.copy(fluidSplatRadius = (r.fluidSplatRadius + v * 0.15f).coerceIn(0.02f, 0.4f))
                LfoTarget.FLUID_FORCE -> r.copy(fluidSplatForce = (r.fluidSplatForce + v * 1.5f).coerceIn(0f, 3f))
                LfoTarget.FLUID_GLOW -> r.copy(fluidBloomIntensity = (r.fluidBloomIntensity + v).coerceIn(0.1f, 2f))
                LfoTarget.FLUID_FADE -> r.copy(fluidDensityDissipation = (r.fluidDensityDissipation + v * 1.5f).coerceIn(0f, 4f))
                LfoTarget.FLUID_CATCH_PULL -> r.copy(fluidCatchPull = (r.fluidCatchPull + v * 1.5f).coerceIn(0f, 3f))
                LfoTarget.FLUID_CATCH_RADIUS -> r.copy(fluidCatchRadius = (r.fluidCatchRadius + v * 0.12f).coerceIn(0.03f, 0.3f))
                LfoTarget.FLOW_STRENGTH -> r.copy(flowStrength = (r.flowStrength + v).coerceIn(0f, 1f))
                LfoTarget.WATER_RIPPLE -> r.copy(waterRippleStrength = (r.waterRippleStrength + v).coerceIn(0f, 2f))
                LfoTarget.RIPPLE_OVERLAY -> r.copy(rippleOverlayStrength = (r.rippleOverlayStrength + v).coerceIn(0f, 1f))
                else -> r
            }
    }
}
