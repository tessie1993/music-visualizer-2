package dev.musicviz.render.fluid

import dev.musicviz.analysis.AudioFeatures
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The spawn/catch progression engine of the rebuilt fluid+particle stack:
 * every frame it places up to [MAX_SPAWN] spawn points (where dye splats fire
 * and particles are born) and up to [MAX_CATCH] catch points (attractors that
 * pull particles in, capture them and recycle them back to a spawn point),
 * and both sets PROGRESS through the track instead of sitting on a static
 * pattern.
 *
 * Three nested time scales drive the motion:
 *  1. Song progress (features.progress, 0..1) slides the layout through a
 *     journey: spawn points migrate bottom->spiral->top while catch points
 *     run the complementary path, so early, middle and late sections of a
 *     track read as different places on screen.
 *  2. Section changes (features.sectionIndex) re-seat the pattern phase by a
 *     golden-angle step, giving each verse/chorus its own anchor arrangement.
 *  3. Beats advance a phyllotaxis counter, so consecutive bursts land on
 *     successive golden-angle florets rather than on top of each other.
 *
 * Everything is rate-limited: anchors chase their targets with a critically
 * damped follow, so progression NEVER teleports (organic-motion property #2,
 * docs/ORGANIC_MOTION.md) - section jumps read as a purposeful glide.
 *
 * Pure Kotlin, deterministic per (inputs, dt) - the headless gate tests
 * continuity, progression and domain bounds directly
 * (FluidChoreographyTest).
 */
internal class FluidChoreography {
    companion object {
        const val MAX_SPAWN = 8
        const val MAX_CATCH = 4

        // Path families (SceneParams.fluidSpawnPath).
        const val PATH_ORBIT = 0
        const val PATH_LISSAJOUS = 1
        const val PATH_ROSE = 2
        const val PATH_BLOOM = 3
        const val PATH_DRIFT = 4
        val PATH_LABELS = listOf("Orbit", "Lissajous", "Rose", "Bloom", "Drift")

        /** Golden angle (radians): successive florets never overlap. */
        const val GOLDEN_ANGLE = 2.399963f

        /** Per-second anchor chase rate (exponential approach). */
        private const val FOLLOW_RATE = 2.2f

        /**
         * Hard ceiling on anchor travel, sim units per second: the
         * no-teleport guarantee is structural, not statistical - even a
         * corner-to-corner target swing (section jump on the Bloom path)
         * moves the anchor at most MAX_SPEED*dt per frame.
         */
        private const val MAX_SPEED = 4.5f

        /** Keeps every anchor inside the visible domain with a margin. */
        private const val DOMAIN_MARGIN = 0.92f
    }

    /** One choreographed point in sim space (y in [-1,1], x in [-a,a]). */
    class Anchor {
        var x = 0f
        var y = 0f
        var targetX = 0f
        var targetY = 0f

        /** 0..1 emphasis (beat/band envelope), free for the consumer. */
        var energy = 0f

        fun follow(dt: Float) {
            val k = 1f - kotlin.math.exp(-dt * FOLLOW_RATE)
            var dx = (targetX - x) * k
            var dy = (targetY - y) * k
            val step = sqrt(dx * dx + dy * dy)
            val maxStep = MAX_SPEED * dt
            if (step > maxStep && step > 1e-6f) {
                val s = maxStep / step
                dx *= s
                dy *= s
            }
            x += dx
            y += dy
        }

        fun snap() {
            x = targetX
            y = targetY
        }
    }

    // ---- configuration (mapped from SceneParams by the scenes) ----
    var path = PATH_LISSAJOUS
    var spawnCount = 3
    var catchCount = 2

    /** How strongly song progress reshapes the journey (0 = static pattern). */
    var progressionAmount = 1f

    /** Extra orbital motion speed multiplier. */
    var speed = 1f

    val spawns: List<Anchor> = List(MAX_SPAWN) { Anchor() }
    val catches: List<Anchor> = List(MAX_CATCH) { Anchor() }

    /** Count of beats seen; advances the phyllotaxis floret index. */
    var beatCount = 0
        private set

    private var time = 0f
    private var lastSection = -1
    private var sectionPhase = 0f
    private var initialized = false
    private var beatEnv = 0f
    private var bassEnv = 0f

    /**
     * Advances the choreography one frame. [aspect] is sim-space half-width.
     * Reads features.progress / sectionIndex / beat / bands; all optional
     * (zero-defaults degrade to a slowly orbiting static-progress layout).
     */
    fun tick(
        f: AudioFeatures,
        dt: Float,
        aspect: Float,
    ) {
        time += dt * (0.4f + 0.6f * speed)
        if (f.beat) beatCount++
        beatEnv = if (f.beat) 1f else beatEnv * kotlin.math.exp(-dt / 0.35f)
        val bassTarget = (f.bass * 1.2f).coerceIn(0f, 1f)
        bassEnv += (bassTarget - bassEnv) * (if (bassTarget > bassEnv) (dt / 0.03f) else (dt / 0.45f)).coerceAtMost(1f)

        // Section re-seat: each detected section rotates the whole layout by
        // one golden-angle step (drifted to, never snapped).
        if (f.sectionIndex != lastSection) {
            lastSection = f.sectionIndex
            sectionPhase = f.sectionIndex * GOLDEN_ANGLE
        }

        val progress = f.progress.coerceIn(0f, 1f) * progressionAmount.coerceIn(0f, 1f)
        val ax = min(aspect, 1.6f) * DOMAIN_MARGIN

        val nS = spawnCount.coerceIn(1, MAX_SPAWN)
        for (i in 0 until nS) {
            val (tx, ty) = spawnTarget(i, nS, progress, ax)
            spawns[i].targetX = tx.coerceIn(-ax, ax)
            spawns[i].targetY = ty.coerceIn(-DOMAIN_MARGIN, DOMAIN_MARGIN)
            spawns[i].energy = beatEnv
        }
        val nC = catchCount.coerceIn(0, MAX_CATCH)
        for (i in 0 until nC) {
            val (tx, ty) = catchTarget(i, nC, progress, ax)
            catches[i].targetX = tx.coerceIn(-ax, ax)
            catches[i].targetY = ty.coerceIn(-DOMAIN_MARGIN, DOMAIN_MARGIN)
            catches[i].energy = bassEnv
        }

        if (!initialized) {
            initialized = true
            spawns.forEach { it.snap() }
            catches.forEach { it.snap() }
        } else {
            spawns.forEach { it.follow(dt) }
            catches.forEach { it.follow(dt) }
        }
    }

    /** Resets motion state (scene re-init); keeps configuration. */
    fun reset() {
        initialized = false
        time = 0f
        beatCount = 0
        lastSection = -1
        sectionPhase = 0f
        beatEnv = 0f
        bassEnv = 0f
    }

    /**
     * Spawn-point target for slot [i] of [n]: the path family shape, swept
     * through the journey by song [progress]. The journey arc is shared by
     * every family: radius breathes 0.35->0.75->0.5, the layout precesses a
     * half-turn, and the vertical center rises from the lower third to the
     * upper third across the track.
     */
    private fun spawnTarget(
        i: Int,
        n: Int,
        progress: Float,
        ax: Float,
    ): Pair<Float, Float> {
        val frac = i.toFloat() / n
        val precession = sectionPhase + progress * PI.toFloat() + time * 0.13f
        val journeyR = 0.35f + 0.4f * sin(progress * PI.toFloat())
        val cy = (progress - 0.5f) * 0.7f
        return when (path) {
            PATH_ORBIT -> {
                val a = frac * 2f * PI.toFloat() + precession
                (cos(a) * journeyR * ax) to (cy + sin(a) * journeyR)
            }
            PATH_ROSE -> {
                // r = cos(k*theta) rose; k morphs 2->5 petals over the track.
                val k = 2f + 3f * progress
                val theta = frac * 2f * PI.toFloat() + precession
                val r = journeyR * (0.35f + 0.65f * abs(cos(k * theta)))
                (cos(theta) * r * ax) to (cy + sin(theta) * r)
            }
            PATH_BLOOM -> {
                // Phyllotaxis: florets step outward at the golden angle; the
                // beat counter advances which florets are occupied, so bursts
                // bloom outward as the song plays.
                val idx = (beatCount + i).toFloat()
                val a = idx * GOLDEN_ANGLE + sectionPhase
                val r = journeyR * sqrt(((idx % 24f) + 1f) / 24f)
                (cos(a) * r * ax) to (cy + sin(a) * r)
            }
            PATH_DRIFT -> {
                // Slow deterministic wander, unique per slot: two
                // incommensurate sines per axis (no hash - continuity).
                val s = i * 3.7f + sectionPhase
                val t = time * 0.31f + progress * 5f
                val x = 0.7f * sin(t * 0.83f + s) * sin(t * 0.19f + s * 1.7f)
                val y = 0.7f * sin(t * 0.67f + s * 2.3f) * sin(t * 0.23f + s)
                (x * ax) to (cy * 0.5f + y * 0.8f)
            }
            else -> {
                // PATH_LISSAJOUS: 3:2 figure with per-slot phase offset; the
                // ratio slides toward 5:4 with progress for a denser weave.
                val a = 3f + 2f * progress
                val b = 2f + 2f * progress
                val ph = frac * 2f * PI.toFloat() + precession
                (sin(a * time * 0.21f + ph) * journeyR * ax) to
                    (cy + sin(b * time * 0.21f + ph * 1.5f + 1.1f) * journeyR)
            }
        }
    }

    /**
     * Catch-point target: the complementary journey. Catches sit where
     * spawns are NOT - phase-opposed on the same family radius early in the
     * track, then spiral toward the center as the song closes so the finale
     * visibly drains inward.
     */
    private fun catchTarget(
        i: Int,
        n: Int,
        progress: Float,
        ax: Float,
    ): Pair<Float, Float> {
        val frac = i.toFloat() / n
        val a = frac * 2f * PI.toFloat() + sectionPhase + PI.toFloat() / n - time * 0.09f
        // Radius shrinks with progress: endgame pulls everything to center.
        val r = (0.62f - 0.4f * progress).coerceAtLeast(0.12f)
        val cy = (0.5f - progress) * 0.5f
        return (cos(a) * r * ax) to (cy + sin(a) * r * 0.85f)
    }

    /**
     * Packs spawn points into a vec4 array for the particle shaders:
     * (x, y, weight, jitterRadius). Weight biases WHICH spawn point a
     * recycled particle respawns at - beat-hot spawns get more births.
     */
    fun packSpawns(out: FloatArray) {
        val n = spawnCount.coerceIn(1, MAX_SPAWN)
        for (i in 0 until MAX_SPAWN) {
            val o = i * 4
            if (i < n) {
                out[o] = spawns[i].x
                out[o + 1] = spawns[i].y
                out[o + 2] = 0.4f + 0.6f * spawns[i].energy
                out[o + 3] = 0.05f + 0.10f * spawns[i].energy
            } else {
                out[o] = 0f
                out[o + 1] = 0f
                out[o + 2] = 0f
                out[o + 3] = 0f
            }
        }
    }

    /**
     * Packs catch points for the particle shaders:
     * (x, y, pullStrength, captureRadius); pull scales with the bass
     * envelope so drops physically drag the field in.
     */
    fun packCatches(
        out: FloatArray,
        pull: Float,
        captureRadius: Float,
    ) {
        val n = catchCount.coerceIn(0, MAX_CATCH)
        for (i in 0 until MAX_CATCH) {
            val o = i * 4
            if (i < n) {
                out[o] = catches[i].x
                out[o + 1] = catches[i].y
                out[o + 2] = pull * (0.5f + 0.9f * catches[i].energy)
                out[o + 3] = captureRadius
            } else {
                out[o] = 0f
                out[o + 1] = 0f
                out[o + 2] = 0f
                out[o + 3] = 0f
            }
        }
    }
}
