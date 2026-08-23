package dev.geode.render.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object HyperspaceMath {
    const val MAX_BLOOMS: Int = 8

    const val FLOATS_PER_VEC4: Int = 4

    const val FLOATS_PER_MAT3: Int = 9

    enum class Species {
        GASKET,

        TEMPLE,

        JEWEL,

        CORAL,

        BULB,

        SEED,
    }

    val SPECIES: List<Species> = Species.entries.toList()

    enum class Act {
        THRESHOLD,

        CHRYSANTHEMUM,

        MAGIC_EYE,

        WAITING_ROOM,

        BREAKTHROUGH,
    }

    val ACTS: List<Act> = Act.entries.toList()

    val ACT_NAMES: List<String> =
        listOf("Threshold", "Chrysanthemum", "Magic eye", "Waiting room", "Breakthrough")

    val JOURNEY_MODES: List<String> = listOf("Music", "Hold", "Cycle")

    const val JOURNEY_MUSIC: Int = 0
    const val JOURNEY_HOLD: Int = 1
    const val JOURNEY_CYCLE: Int = 2

    data class ActProfile(
        val bodies: Int,
        val field: Float,
        val mirror: Float,
        val styleMirror: Float,
        val camera: Float,
        val motion: Float,
        val glow: Float,
        val hueSpread: Float,
    )

    val ACT_PROFILES: List<ActProfile> =
        listOf(
            ActProfile(
                bodies = 2,
                field = 0.22f,
                mirror = 0f,
                styleMirror = 1f,
                camera = 6.5f,
                motion = 0.45f,
                glow = 0.7f,
                hueSpread = 0.18f,
            ),
            ActProfile(
                bodies = 3,
                field = 1.35f,
                mirror = 1f,
                styleMirror = 1f,
                camera = 9f,
                motion = 0.8f,
                glow = 1.1f,
                hueSpread = 0.55f,
            ),
            ActProfile(
                bodies = 5,
                field = 0.5f,
                mirror = 0f,
                styleMirror = 1f,
                camera = 5.4f,
                motion = 1f,
                glow = 1f,
                hueSpread = 0.7f,
            ),
            ActProfile(
                bodies = 7,
                field = 0.35f,
                mirror = 0f,
                styleMirror = 1f,
                camera = 4.2f,
                motion = 1.15f,
                glow = 1.2f,
                hueSpread = 0.85f,
            ),
            ActProfile(
                bodies = MAX_BLOOMS,
                field = 0.65f,
                mirror = 0f,
                styleMirror = 0f,
                camera = 5.2f,
                motion = 1.5f,
                glow = 1.45f,
                hueSpread = 1f,
            ),
        )

    fun profileAt(act: Float): ActProfile {
        val x = act.coerceIn(0f, (ACT_PROFILES.size - 1).toFloat())
        val i = x.toInt().coerceAtMost(ACT_PROFILES.size - 2)
        val t = (x - i).coerceIn(0f, 1f)
        val a = ACT_PROFILES[i]
        val b = ACT_PROFILES[i + 1]

        fun f(
            u: Float,
            v: Float,
        ) = u + (v - u) * t
        return ActProfile(
            bodies = Math.round(f(a.bodies.toFloat(), b.bodies.toFloat())),
            field = f(a.field, b.field),
            mirror = f(a.mirror, b.mirror),
            styleMirror = f(a.styleMirror, b.styleMirror),
            camera = f(a.camera, b.camera),
            motion = f(a.motion, b.motion),
            glow = f(a.glow, b.glow),
            hueSpread = f(a.hueSpread, b.hueSpread),
        )
    }

    const val RISE_SECONDS: Float = 26f

    const val FALL_SECONDS: Float = 44f

    const val IMMERSION_PIVOT: Float = 0.42f

    const val MIN_ACT_SECONDS: Float = 4f

    const val ACT_GLIDE_SECONDS: Float = 2.5f

    const val TIME_WRAP_SECONDS: Float = 6283.1853f

    fun smoothing(
        dt: Float,
        seconds: Float,
    ): Float {
        if (seconds <= 0f) return 1f
        return 1f - kotlin.math.exp(-dt / seconds)
    }

    fun slewLimit(
        current: Float,
        target: Float,
        dt: Float,
        riseRate: Float,
        fallRate: Float,
    ): Float {
        val t = target.coerceIn(0f, 1f)
        val step = (t - current).coerceIn(-abs(fallRate) * dt, abs(riseRate) * dt)
        return (current + step).coerceIn(0f, 1f)
    }

    /**
     * How much of a hit's strength is allowed to bud a new body.
     *
     * Was gated on the beat tracker's CONFIDENCE, which meant the room stayed half-dead for
     * the several seconds the tracker needed to lock, and never woke up at all on material
     * with no steady pulse. Gated on the live level instead: a loud passage buds hard, a
     * near-silent one barely at all, decided from the frame in hand.
     */
    fun hitGate(level: Float): Float = HIT_GATE_FLOOR + (1f - HIT_GATE_FLOOR) * smoothstep(0.05f, 0.35f, level.coerceIn(0f, 1f))

    const val HIT_GATE_FLOOR: Float = 0.35f

    fun worldToLocalRotation(
        axisA: FloatArray,
        angleA: Float,
        axisB: FloatArray,
        angleB: Float,
        out: FloatArray,
        offset: Int,
    ) {
        val a = FloatArray(9)
        val b = FloatArray(9)
        axisAngle(axisA, angleA, a)
        axisAngle(axisB, angleB, b)
        for (c in 0 until 3) {
            for (r in 0 until 3) {
                var sum = 0f
                for (k in 0 until 3) {
                    sum += a[k * 3 + r] * b[c * 3 + k]
                }
                out[offset + c * 3 + r] = sum
            }
        }
    }

    internal fun axisAngle(
        axis: FloatArray,
        angle: Float,
        out: FloatArray,
    ) {
        val len = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
        if (len < 1e-6f) {
            out.fill(0f)
            out[0] = 1f
            out[4] = 1f
            out[8] = 1f
            return
        }
        val x = axis[0] / len
        val y = axis[1] / len
        val z = axis[2] / len
        val c = cos(angle)
        val s = sin(angle)
        val t = 1f - c
        out[0] = t * x * x + c
        out[1] = t * x * y - s * z
        out[2] = t * x * z + s * y
        out[3] = t * x * y + s * z
        out[4] = t * y * y + c
        out[5] = t * y * z - s * x
        out[6] = t * x * z - s * y
        out[7] = t * y * z + s * x
        out[8] = t * z * z + c
    }

    fun transform(
        m: FloatArray,
        offset: Int,
        v: FloatArray,
        out: FloatArray,
    ) {
        for (r in 0 until 3) {
            out[r] = m[offset + r] * v[0] + m[offset + 3 + r] * v[1] + m[offset + 6 + r] * v[2]
        }
    }

    fun randomUnitVector(
        rng: Random,
        out: FloatArray,
        offset: Int = 0,
    ) {
        val z = rng.nextFloat() * 2f - 1f
        val a = rng.nextFloat() * 2f * PI.toFloat()
        val r = sqrt(max(0f, 1f - z * z))
        out[offset] = r * cos(a)
        out[offset + 1] = r * sin(a)
        out[offset + 2] = z
    }

    fun randomPlane(
        rng: Random,
        u: FloatArray,
        v: FloatArray,
    ) {
        randomUnitVector(rng, u)
        randomUnitVector(rng, v)
        val d = u[0] * v[0] + u[1] * v[1] + u[2] * v[2]
        v[0] -= d * u[0]
        v[1] -= d * u[1]
        v[2] -= d * u[2]
        var len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-4f) {
            val ax = abs(u[0])
            val ay = abs(u[1])
            val az = abs(u[2])
            val bx = if (ax <= ay && ax <= az) 1f else 0f
            val by = if (ay < ax && ay <= az) 1f else 0f
            val bz = if (bx == 0f && by == 0f) 1f else 0f
            v[0] = u[1] * bz - u[2] * by
            v[1] = u[2] * bx - u[0] * bz
            v[2] = u[0] * by - u[1] * bx
            len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        }
        v[0] /= len
        v[1] /= len
        v[2] /= len
    }

    fun lifeEnvelope(
        age: Float,
        lifetime: Float,
        grow: Float,
        wither: Float,
    ): Float {
        if (lifetime <= 0f) return 0f
        if (age <= 0f || age >= lifetime) return 0f
        val half = lifetime * 0.5f
        val g = grow.coerceIn(0.01f, half)
        val w = wither.coerceIn(0.01f, half)
        val rise = smoothstep(0f, g, age)
        val fall = 1f - smoothstep(lifetime - w, lifetime, age)
        return (rise * fall).coerceIn(0f, 1f)
    }

    fun smoothstep(
        edge0: Float,
        edge1: Float,
        x: Float,
    ): Float {
        if (edge1 <= edge0) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun foldFor(
        species: Species,
        fold: Float,
        jitter: Float,
    ): Float {
        val t = (fold.coerceIn(0f, 1f) + jitter * 0.12f).coerceIn(0f, 1f)
        return when (species) {
            Species.GASKET -> 0.85f + 0.48f * t
            Species.TEMPLE -> 0.05f + 1.15f * t
            Species.JEWEL -> -2.4f + 1.5f * t
            Species.CORAL -> 0.55f + 0.55f * t
            Species.BULB -> 5f + 6f * t
            Species.SEED -> 0.08f + 0.44f * t
        }
    }

    fun localRadius(species: Species): Float =
        when (species) {
            Species.GASKET -> 1.85f
            Species.TEMPLE -> 1.7f
            Species.JEWEL -> 3.2f
            Species.CORAL -> 2.1f
            Species.BULB -> 1.35f
            Species.SEED -> 1.5f
        }

    val MAX_LOCAL_RADIUS: Float = SPECIES.maxOf { localRadius(it) }
}
