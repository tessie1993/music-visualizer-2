package dev.geode.render

import dev.geode.render.scene.SceneParams
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

enum class LfoWave(
    val label: String,
) {
    SINE("Sin"),
    TRIANGLE("Tri"),
    SAW("Saw"),
    SQUARE("Sqr"),
    RANDOM("S&H"),
}

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

data class LfoConfig(
    val enabled: Boolean = false,
    val target: LfoTarget = LfoTarget.NONE,
    val wave: LfoWave = LfoWave.SINE,
    val rateHz: Float = 0.5f,
    val beatSync: Boolean = false,
    val beatDiv: Float = 1f,
    val depth: Float = 0.3f,
)

class LfoEngine {
    @Volatile
    var configs: List<LfoConfig> = List(3) { LfoConfig() }

    private val phases = FloatArray(3)
    private val sampleHold = FloatArray(3)

    private val totalPhase = FloatArray(3)
    private val lastCycle = IntArray(3) { -1 }

    private val out = FloatArray(3)
    private val rateAdd = FloatArray(3)
    private val depthAdd = FloatArray(3)

    fun tick(
        dt: Float,
        bpm: Float,
        extRateAdd: FloatArray? = null,
        extDepthAdd: FloatArray? = null,
        safety: VisualSafety.SafetyConfig = VisualSafety.SafetyConfig.OFF,
    ): FloatArray {
        val cfgs = configs
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
        internal const val SH_PHASE_WRAP = 64f

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
