package dev.geode.render.scene

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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

    fun beatGate(pulseConfidence: Float): Float = BEAT_GATE_FLOOR + (1f - BEAT_GATE_FLOOR) * smoothstep(0.2f, 0.65f, pulseConfidence)

    const val BEAT_GATE_FLOOR: Float = 0.35f

    fun progressImmersionFloor(progress: Float): Float = PROGRESS_FLOOR_MAX * smoothstep(0.2f, 0.75f, progress.coerceIn(0f, 1f))

    const val PROGRESS_FLOOR_MAX: Float = 0.30f

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

class BloomBank(
    private val rng: Random = Random.Default,
) {
    private companion object {
        const val SILENT_SPAWN_SECONDS = 2.5f

        const val SPAWN_GAP_SECONDS = 0.45f

        const val SPAWN_IMPULSE = 0.18f

        const val RETIRE_SECONDS = 1.6f
    }

    val blooms: List<Bloom> = List(HyperspaceMath.MAX_BLOOMS) { Bloom() }

    private var sinceSpawn = SILENT_SPAWN_SECONDS
    private var sinceImpulse = 0f

    val aliveCount: Int
        get() = blooms.count { it.alive }

    fun reset() {
        for (b in blooms) {
            b.alive = false
            b.fade = 0f
        }
        sinceSpawn = SILENT_SPAWN_SECONDS
        sinceImpulse = 0f
    }

    fun advance(
        dt: Float,
        target: Int,
        impulse: Float,
        species: HyperspaceMath.Species?,
        lifetime: Float,
        spread: Float,
        sizeScale: Float,
        motion: Float,
        orbitScale: Float,
        spinScale: Float = 1f,
    ) {
        for (b in blooms) b.advance(dt, motion, orbitScale, spinScale)
        sinceSpawn += dt
        sinceImpulse = if (impulse >= SPAWN_IMPULSE) 0f else sinceImpulse + dt

        val want = target.coerceIn(0, HyperspaceMath.MAX_BLOOMS)
        var living = aliveCount
        if (living > want) {
            var retiring = 0
            for (b in blooms) {
                if (b.alive && b.lifetime - b.age <= RETIRE_SECONDS) retiring++
            }
            var excess = living - want - retiring
            while (excess > 0) {
                var victim: Bloom? = null
                for (b in blooms) {
                    if (!b.alive) continue
                    if (b.lifetime - b.age <= RETIRE_SECONDS) continue
                    if (victim == null || b.age > victim.age) victim = b
                }
                if (victim == null) break
                victim.retire(RETIRE_SECONDS)
                excess--
            }
        }

        if (living < want && sinceSpawn >= SPAWN_GAP_SECONDS) {
            val onHit = impulse >= SPAWN_IMPULSE
            val onSilence = sinceImpulse >= SILENT_SPAWN_SECONDS
            if (onHit || onSilence) {
                val slot = blooms.firstOrNull { !it.alive }
                if (slot != null) {
                    val pick = species ?: HyperspaceMath.SPECIES[rng.nextInt(HyperspaceMath.SPECIES.size)]
                    val life = lifetime * (0.65f + 0.7f * rng.nextFloat())
                    slot.spawn(rng, pick, life, spread, sizeScale)
                    sinceSpawn = 0f
                    living++
                }
            }
        }
    }

    fun snapshot(
        fold: Float,
        pos: FloatArray,
        shape: FloatArray,
        look: FloatArray,
        rot: FloatArray,
        boundInflate: Float = 0f,
    ): Int {
        var n = 0
        for (b in blooms) {
            if (!b.alive || b.fade <= 0.002f) continue
            if (n >= HyperspaceMath.MAX_BLOOMS) break
            val i4 = n * HyperspaceMath.FLOATS_PER_VEC4
            val worldScale = b.scale * (0.25f + 0.75f * b.fade)
            pos[i4] = b.centre[0]
            pos[i4 + 1] = b.centre[1]
            pos[i4 + 2] = b.centre[2]
            pos[i4 + 3] = HyperspaceMath.localRadius(b.species) * worldScale + boundInflate
            shape[i4] = b.species.ordinal.toFloat()
            shape[i4 + 1] = worldScale
            shape[i4 + 2] = HyperspaceMath.foldFor(b.species, fold, b.foldJitter)
            shape[i4 + 3] = b.fade
            look[i4] = b.hue
            look[i4 + 1] = b.glow
            look[i4 + 2] = b.breath
            look[i4 + 3] = 0f
            b.writeRotation(rot, n * HyperspaceMath.FLOATS_PER_MAT3)
            n++
        }
        return n
    }
}

class HyperspaceJourney {
    var immersion: Float = 0f
        private set

    var actPosition: Float = 0f
        private set

    var act: Int = 0
        private set

    private var heldSeconds = 0f
    private var cyclePhase = 0f

    fun reset() {
        immersion = 0f
        actPosition = 0f
        act = 0
        heldSeconds = 0f
        cyclePhase = 0f
    }

    fun advance(
        dt: Float,
        energy: Float,
        mode: Int,
        holdAct: Int,
        cycleSeconds: Float,
        pace: Float,
        progress: Float = 0f,
    ) {
        val last = HyperspaceMath.ACTS.size - 1
        val step = dt * max(pace, 0f)
        val goal: Float =
            when (mode) {
                HyperspaceMath.JOURNEY_HOLD -> holdAct.coerceIn(0, last).toFloat()
                HyperspaceMath.JOURNEY_CYCLE -> {
                    val per = max(cycleSeconds, 2f)
                    val slots = max(2 * last, 1)
                    cyclePhase = (cyclePhase + step / per) % slots.toFloat()
                    val slot = cyclePhase.toInt().coerceIn(0, slots - 1)
                    (last - abs(last - slot)).toFloat()
                }
                else -> {
                    val drive = energy.coerceIn(0f, 1f) - HyperspaceMath.IMMERSION_PIVOT
                    val rate =
                        if (drive >= 0f) {
                            drive / (1f - HyperspaceMath.IMMERSION_PIVOT) / HyperspaceMath.RISE_SECONDS
                        } else {
                            drive / HyperspaceMath.IMMERSION_PIVOT / HyperspaceMath.FALL_SECONDS
                        }
                    immersion = (immersion + rate * step).coerceIn(0f, 1f)
                    immersion = max(immersion, HyperspaceMath.progressImmersionFloor(progress))
                    immersion * last
                }
            }
        actPosition += (goal - actPosition) * HyperspaceMath.smoothing(step, HyperspaceMath.ACT_GLIDE_SECONDS)
        actPosition = actPosition.coerceIn(0f, last.toFloat())
        heldSeconds = min(heldSeconds + dt, 3600f)
        val rounded = Math.round(actPosition).coerceIn(0, last)
        if (rounded != act && heldSeconds >= HyperspaceMath.MIN_ACT_SECONDS) {
            act = rounded
            heldSeconds = 0f
        }
    }

    fun profile(): HyperspaceMath.ActProfile = HyperspaceMath.profileAt(actPosition)
}

class HyperspaceCamera {
    val position: FloatArray = FloatArray(3)

    val basis: FloatArray = FloatArray(9)

    private var t = 0f

    fun reset() {
        t = 0f
    }

    fun advance(
        dt: Float,
        distance: Float,
        drift: Float,
    ) {
        t = (t + dt * max(drift, 0f)) % HyperspaceMath.TIME_WRAP_SECONDS
        val yaw = 0.11f * t + 0.37f * sin(0.073f * t) + 0.13f * sin(0.191f * t)
        val pitch = 0.42f * sin(0.041f * t) + 0.17f * sin(0.113f * t)
        val d = max(distance, 0.35f)
        val cp = cos(pitch)
        position[0] = d * cp * cos(yaw)
        position[1] = d * sin(pitch)
        position[2] = d * cp * sin(yaw)

        val inv = 1f / max(sqrt(position[0] * position[0] + position[1] * position[1] + position[2] * position[2]), 1e-5f)
        val fx = -position[0] * inv
        val fy = -position[1] * inv
        val fz = -position[2] * inv
        val upIsY = abs(fy) < 0.985f
        val ux = if (upIsY) 0f else 1f
        val uy = if (upIsY) 1f else 0f
        val uz = 0f
        var rx = fy * uz - fz * uy
        var ry = fz * ux - fx * uz
        var rz = fx * uy - fy * ux
        val rl = 1f / max(sqrt(rx * rx + ry * ry + rz * rz), 1e-5f)
        rx *= rl
        ry *= rl
        rz *= rl
        val vx = ry * fz - rz * fy
        val vy = rz * fx - rx * fz
        val vz = rx * fy - ry * fx
        basis[0] = rx
        basis[1] = ry
        basis[2] = rz
        basis[3] = vx
        basis[4] = vy
        basis[5] = vz
        basis[6] = fx
        basis[7] = fy
        basis[8] = fz
    }
}

data class MarchBudget(
    val steps: Int,
    val iterations: Int,
    val bulbIterations: Int,
    val seedIterations: Int,
) {
    companion object {
        const val MAX_STEPS: Int = 128
        const val MAX_ITERS: Int = 14
        const val MAX_BULB_ITERS: Int = 10
        const val MAX_SEED_ITERS: Int = 12

        const val MIN_DETAIL: Float = 0.25f
        const val MAX_DETAIL: Float = 1.5f

        private const val FLOOR_STEPS: Int = 64
        private const val FLOOR_ITERS: Int = 5
        private const val FLOOR_BULB_ITERS: Int = 3
        private const val FLOOR_SEED_ITERS: Int = 5

        private const val TOP_STEPS: Int = MAX_STEPS
        private const val TOP_ITERS: Int = MAX_ITERS
        private const val TOP_BULB_ITERS: Int = 8
        private const val TOP_SEED_ITERS: Int = MAX_SEED_ITERS

        fun forDetail(detail: Float): MarchBudget {
            val t = ((detail - MIN_DETAIL) / (MAX_DETAIL - MIN_DETAIL)).coerceIn(0f, 1f)
            return MarchBudget(
                steps = lerpBudget(FLOOR_STEPS, TOP_STEPS, t),
                iterations = lerpBudget(FLOOR_ITERS, TOP_ITERS, t),
                bulbIterations = lerpBudget(FLOOR_BULB_ITERS, TOP_BULB_ITERS, t),
                seedIterations = lerpBudget(FLOOR_SEED_ITERS, TOP_SEED_ITERS, t),
            )
        }

        private fun lerpBudget(
            floor: Int,
            top: Int,
            t: Float,
        ): Int = Math.round(floor + (top - floor) * t)
    }
}

object HyperspaceLook {
    fun spread(bodies: Int): Float = 1.1f + 0.22f * bodies

    fun bodySize(bodies: Int): Float = (0.72f - 0.045f * bodies).coerceAtLeast(0.26f)

    fun maxBodyRadius(bodies: Int): Float = bodySize(bodies) * Bloom.MAX_SIZE_JITTER * HyperspaceMath.MAX_LOCAL_RADIUS

    fun cameraDistance(
        actCamera: Float,
        spread: Float,
        maxBodyRadius: Float,
        cameraScale: Float = 1f,
    ): Float = max(actCamera * cameraScale, spread * Bloom.MAX_ORBIT_RADIUS + maxBodyRadius + 0.9f)

    fun bodyTarget(
        profileBodies: Int,
        density: Float,
    ): Int = Math.round(profileBodies * density.coerceIn(0.1f, 2f)).coerceIn(1, HyperspaceMath.MAX_BLOOMS)

    fun farPlane(
        camera: Float,
        spread: Float,
    ): Float = camera + spread + 6f

    fun maxMarchStep(scale: Float): Float = max(scale, 0.05f)

    const val HIT_EPSILON: Float = 0.0016f

    const val BOUND_MARGIN: Float = 0.12f
}

class SpectralSummary {
    val levels: FloatArray = FloatArray(SIZE)

    private val target = FloatArray(SIZE)

    fun reset() {
        levels.fill(0f)
    }

    fun advance(
        bands: FloatArray,
        dt: Float,
    ) {
        summarize(bands, target)
        for (i in 0 until SIZE) {
            val goal = target[i]
            val k =
                HyperspaceMath.smoothing(
                    dt,
                    if (goal > levels[i]) ATTACK_SECONDS else RELEASE_SECONDS,
                )
            val next = levels[i] + (goal - levels[i]) * k
            levels[i] = if (next.isFinite()) next.coerceIn(0f, LEVEL_CEILING) else 0f
        }
    }

    companion object {
        const val SIZE: Int = 16

        const val ATTACK_SECONDS: Float = 0.06f
        const val RELEASE_SECONDS: Float = 0.32f
        const val LEVEL_CEILING: Float = 1.5f

        fun summarize(
            bands: FloatArray,
            out: FloatArray,
        ) {
            val n = out.size
            if (bands.isEmpty()) {
                out.fill(0f)
                return
            }
            for (i in 0 until n) {
                val lo = i * bands.size / n
                val hi = (((i + 1) * bands.size / n).coerceAtLeast(lo + 1)).coerceAtMost(bands.size)
                var sum = 0f
                for (j in lo until hi) sum += bands[j].coerceIn(0f, LEVEL_CEILING)
                out[i] = sum / (hi - lo)
            }
        }
    }
}
