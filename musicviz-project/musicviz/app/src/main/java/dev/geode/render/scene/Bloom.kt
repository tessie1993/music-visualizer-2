package dev.geode.render.scene

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class Bloom {
    var alive: Boolean = false
        internal set

    var species: HyperspaceMath.Species = HyperspaceMath.Species.GASKET
        internal set

    var age: Float = 0f
        internal set

    var lifetime: Float = 0f
        internal set

    var fade: Float = 0f
        internal set

    val centre: FloatArray = FloatArray(3)

    private val planeU = FloatArray(3)
    private val planeV = FloatArray(3)
    private var radiusU = 0f
    private var radiusV = 0f
    private var orbitRate = 0f
    private var orbitPhase = 0f

    private val spinAxisA = FloatArray(3)
    private val spinAxisB = FloatArray(3)
    private var spinRateA = 0f
    private var spinRateB = 0f
    private var spinAngleA = 0f
    private var spinAngleB = 0f

    var scale: Float = 1f
        internal set

    var hue: Float = 0f
        internal set

    var foldJitter: Float = 0f
        internal set

    var glow: Float = 1f
        internal set

    var breath: Float = 0f
        internal set

    private var breathRate = 0f

    private var growSeconds = 0f
    private var witherSeconds = 0f

    fun spawn(
        rng: Random,
        species: HyperspaceMath.Species,
        lifetime: Float,
        spread: Float,
        sizeScale: Float,
    ) {
        this.species = species
        this.lifetime = max(lifetime, 0.5f)
        growSeconds = this.lifetime * 0.1f
        witherSeconds = this.lifetime * 0.2f
        age = 0f
        fade = 0f
        alive = true
        HyperspaceMath.randomPlane(rng, planeU, planeV)
        radiusU = spread * jitter(rng, MIN_ORBIT_RADIUS, MAX_ORBIT_RADIUS)
        radiusV = spread * jitter(rng, MIN_ORBIT_RADIUS, MAX_ORBIT_RADIUS)
        orbitRate = (0.035f + 0.16f * rng.nextFloat()) * if (rng.nextBoolean()) 1f else -1f
        orbitPhase = rng.nextFloat() * 2f * PI.toFloat()
        HyperspaceMath.randomUnitVector(rng, spinAxisA)
        HyperspaceMath.randomUnitVector(rng, spinAxisB)
        spinRateA = (0.05f + 0.32f * rng.nextFloat()) * if (rng.nextBoolean()) 1f else -1f
        spinRateB = (0.03f + 0.19f * rng.nextFloat()) * if (rng.nextBoolean()) 1f else -1f
        spinAngleA = rng.nextFloat() * 2f * PI.toFloat()
        spinAngleB = rng.nextFloat() * 2f * PI.toFloat()
        scale = sizeScale * jitter(rng, MIN_SIZE_JITTER, MAX_SIZE_JITTER)
        hue = rng.nextFloat()
        foldJitter = rng.nextFloat() * 2f - 1f
        glow = 0.7f + 0.7f * rng.nextFloat()
        breath = rng.nextFloat() * 2f * PI.toFloat()
        breathRate = 0.08f + 0.22f * rng.nextFloat()
        advance(0f, 1f, 1f)
    }

    fun advance(
        dt: Float,
        motion: Float,
        orbitScale: Float,
        spinScale: Float = 1f,
    ) {
        if (!alive) return
        age += dt
        if (age >= lifetime) {
            alive = false
            fade = 0f
            return
        }
        val m = max(motion, 0f)
        val spin = m * max(spinScale, 0f)
        orbitPhase += dt * orbitRate * m * max(orbitScale, 0f) * 2f * PI.toFloat()
        spinAngleA += dt * spinRateA * spin * 2f * PI.toFloat()
        spinAngleB += dt * spinRateB * spin * 2f * PI.toFloat()
        breath += dt * breathRate * m
        val c = cos(orbitPhase)
        val s = sin(orbitPhase)
        for (i in 0 until 3) {
            centre[i] = planeU[i] * radiusU * c + planeV[i] * radiusV * s
        }
        fade =
            HyperspaceMath.lifeEnvelope(
                age = age,
                lifetime = lifetime,
                grow = growSeconds,
                wither = witherSeconds,
            )
    }

    fun retire(fadeSeconds: Float) {
        if (!alive) return
        val fade = max(fadeSeconds, 0.2f)
        val end = age + fade
        if (end < lifetime) {
            lifetime = end
            witherSeconds = min(witherSeconds, fade)
        }
    }

    fun writeRotation(
        out: FloatArray,
        offset: Int,
    ) {
        HyperspaceMath.worldToLocalRotation(spinAxisA, spinAngleA, spinAxisB, spinAngleB, out, offset)
    }

    companion object {
        const val MIN_ORBIT_RADIUS = 0.35f
        const val MAX_ORBIT_RADIUS = 1.2f

        const val MIN_SIZE_JITTER = 0.55f
        const val MAX_SIZE_JITTER = 1.45f

        private fun jitter(
            rng: Random,
            lo: Float,
            hi: Float,
        ): Float = lo + (hi - lo) * rng.nextFloat()
    }
}
