package dev.geode.render.scene

import dev.geode.analysis.AudioFeatures
import dev.geode.render.fluid.FlowField
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

internal class EmergenceSim(
    val count: Int = DEFAULT_COUNT,
    seed: Long = 7L,
) {
    companion object {
        const val DEFAULT_COUNT: Int = 2600
        const val FLOATS_PER_PARTICLE: Int = 7
        const val VELOCITY_OFFSET: Int = 5

        const val WORLD_EDGE: Float = 1.05f
        const val RESPAWN_EDGE: Float = 1.3f
        const val DEATH_ENERGY: Float = 0.035f
        const val SPAWN_ENERGY: Float = 0.35f
        const val HUE_BANDS: Int = 5

        private const val RADIUS_BASE: Float = 0.085f
        private const val RADIUS_MID_SPAN: Float = 0.05f
        private const val RADIUS_MIN: Float = 0.075f
        private const val GRID_SPAN: Float = 2f * RESPAWN_EDGE
        private const val MAX_CELLS_PER_AXIS: Int = 36
        private const val DENSITY_NORM: Float = 8.5f
        private const val GROWTH_RATE: Float = 1.6f
        private const val SETTLE_RATE: Float = 0.12f
        private const val FRICTION_TIME_CONSTANT: Float = 0.35f
        private const val JITTER_BASE: Float = 0.05f
        private const val KICK_DECAY_PER_SECOND: Float = 3f
        private const val AUTO_BLEND_RATE: Float = 0.055f
        private const val DONOR_TRIES: Int = 6
        private const val MAX_STEP_SECONDS: Float = 1f / 20f
        private const val SIZE_BASE_PX: Float = 2.6f
        private const val SIZE_ENERGY_PX: Float = 15f
        private const val SIZE_BASS_PX: Float = 8f
        private const val PHASE_WRAP: Float = 628.31853f
    }

    var field: Int = EmergenceField.AUTO
    var swarm: Float = 0.6f
    var growthMu: Float = 0.5f
    var speed: Float = 1f
    var audioDrive: Float = 1f
    var beatResponse: Float = 1f
    var turbulence: Float = 0.5f
    var flowStrength: Float = 0f
    var flowGrid: FlowField.CpuGrid? = null

    val records: FloatArray = FloatArray(count * FLOATS_PER_PARTICLE)

    private val random = Random(seed)
    private val px = FloatArray(count)
    private val py = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private val energy = FloatArray(count)
    private val hue = FloatArray(count)

    private val cellHeads = IntArray(MAX_CELLS_PER_AXIS * MAX_CELLS_PER_AXIS)
    private val cellNext = IntArray(count)
    private val fieldSample = FloatArray(2)
    private val flowSample = FloatArray(2)

    private var phase = random.nextFloat() * 20f
    private var beatKick = 0f
    private var fieldA = EmergenceField.THOMAS
    private var fieldB = EmergenceField.DEJONG
    private var blend = 0f
    private var autoIndex = 0
    private var centroidX = 0f
    private var centroidY = 0f

    var lastRadius: Float = RADIUS_BASE
        private set

    private var pcmSpark = 0f

    fun acceptPcm(
        samples: FloatArray,
        count: Int,
    ) {
        var peak = 0f
        var i = 0
        while (i < count) {
            val v = samples[i]
            if (v.isFinite()) {
                val a = kotlin.math.abs(v)
                if (a > peak) peak = a
            }
            i++
        }
        if (peak > pcmSpark) pcmSpark = peak.coerceAtMost(1.5f)
    }

    init {
        for (i in 0 until count) {
            val angle = random.nextFloat() * (2f * Math.PI.toFloat())
            val radius = 0.15f + random.nextFloat() * 0.6f
            px[i] = kotlin.math.cos(angle) * radius
            py[i] = kotlin.math.sin(angle) * radius
            vx[i] = 0f
            vy[i] = 0f
            energy[i] = SPAWN_ENERGY + random.nextFloat() * 0.3f
            hue[i] = bandHue(i)
        }
    }

    private fun bandHue(i: Int): Float = (i % HUE_BANDS) / HUE_BANDS.toFloat() + random.nextFloat() * 0.06f

    fun step(
        features: AudioFeatures,
        dtRaw: Float,
    ) {
        val dt = dtRaw.coerceIn(0f, MAX_STEP_SECONDS)
        if (dt <= 0f) return
        val bass = (features.bass * audioDrive).coerceIn(0f, 1.5f)
        val mid = (features.mid * audioDrive).coerceIn(0f, 1.5f)
        val treble = (features.treble * audioDrive).coerceIn(0f, 1.5f)
        beatKick = maxOf(features.motionImpulse * beatResponse, beatKick - dt * KICK_DECAY_PER_SECOND).coerceAtLeast(0f)

        phase = (phase + dt * speed * (0.45f + bass * 1.1f + beatKick * 0.8f)) % PHASE_WRAP
        advanceFieldChoice(dt)

        val radius = (RADIUS_BASE + RADIUS_MID_SPAN * (mid - 0.4f)).coerceAtLeast(RADIUS_MIN)
        lastRadius = radius
        binParticles(radius)

        val gain = swarm * (0.55f + bass * 1.25f + pcmSpark * 0.6f) * speed
        val friction = exp(-dt / FRICTION_TIME_CONSTANT)
        pcmSpark = (pcmSpark - dt * 4f).coerceAtLeast(0f)
        val jitter = (JITTER_BASE + turbulence * 0.35f) * (0.3f + treble + pcmSpark * 0.7f)
        val grid = flowGrid
        val flowK = if (grid != null) flowStrength.coerceIn(0f, 1f) else 0f
        val mu = 0.08f + growthMu.coerceIn(0f, 1f) * 0.5f
        val cx = centroidX
        val cy = centroidY
        var sumX = 0f
        var sumY = 0f

        for (i in 0 until count) {
            var x = px[i]
            var y = py[i]
            sampleVelocity(x, y)
            var ax = fieldSample[0] * gain
            var ay = fieldSample[1] * gain
            if (flowK > 0f && grid != null) {
                grid.sample(x * 0.5f + 0.5f, y * 0.5f + 0.5f, flowSample)
                ax += flowSample[0] * flowK * 2f
                ay += flowSample[1] * flowK * 2f
            }
            if (beatKick > 0.01f) {
                val dx = x - cx
                val dy = y - cy
                val d = sqrt(dx * dx + dy * dy) + 1e-4f
                val kick = beatKick * 1.6f
                ax += dx / d * kick
                ay += dy / d * kick
            }
            ax += (random.nextFloat() - 0.5f) * jitter * 4f
            ay += (random.nextFloat() - 0.5f) * jitter * 4f
            val r2 = x * x + y * y
            if (r2 > WORLD_EDGE * WORLD_EDGE) {
                val r = sqrt(r2)
                val pull = (r - WORLD_EDGE) * 6f / r
                ax -= x * pull
                ay -= y * pull
            }

            var nvx = (vx[i] + ax * dt) * friction
            var nvy = (vy[i] + ay * dt) * friction
            var nx = x + nvx * dt
            var ny = y + nvy * dt

            val u = densityAt(i, nx, ny, radius)
            val g = EmergenceField.growth(u, mu)
            var e = energy[i] + (g * GROWTH_RATE + (0.5f - energy[i]) * SETTLE_RATE) * dt
            e = e.coerceIn(0f, 1f)

            val bad =
                !nx.isFinite() || !ny.isFinite() || nx < -RESPAWN_EDGE || nx > RESPAWN_EDGE ||
                    ny < -RESPAWN_EDGE || ny > RESPAWN_EDGE
            if (bad || e <= DEATH_ENERGY) {
                val donor = pickDonor()
                nx = px[donor] + (random.nextFloat() - 0.5f) * radius
                ny = py[donor] + (random.nextFloat() - 0.5f) * radius
                nvx = vx[donor] * 0.5f
                nvy = vy[donor] * 0.5f
                e = SPAWN_ENERGY
                hue[i] = bandHue(i)
            }

            px[i] = nx
            py[i] = ny
            vx[i] = nvx
            vy[i] = nvy
            energy[i] = e
            sumX += nx
            sumY += ny

            val o = i * FLOATS_PER_PARTICLE
            records[o] = nx
            records[o + 1] = ny
            records[o + 2] = SIZE_BASE_PX + e * SIZE_ENERGY_PX + bass * SIZE_BASS_PX
            records[o + 3] = hue[i] + e * 0.08f
            records[o + 4] = e
            records[o + VELOCITY_OFFSET] = nvx
            records[o + VELOCITY_OFFSET + 1] = nvy
        }
        centroidX = sumX / count
        centroidY = sumY / count
    }

    private fun sampleVelocity(
        x: Float,
        y: Float,
    ) {
        val breathe = growthMu
        if (field != EmergenceField.AUTO) {
            EmergenceField.velocity(field, x, y, phase, breathe, fieldSample)
            return
        }
        EmergenceField.velocity(fieldA, x, y, phase, breathe, fieldSample)
        if (blend <= 0f) return
        val axv = fieldSample[0]
        val ayv = fieldSample[1]
        EmergenceField.velocity(fieldB, x, y, phase, breathe, fieldSample)
        fieldSample[0] = axv + (fieldSample[0] - axv) * blend
        fieldSample[1] = ayv + (fieldSample[1] - ayv) * blend
    }

    private fun advanceFieldChoice(dt: Float) {
        if (field != EmergenceField.AUTO) {
            blend = 0f
            return
        }
        blend += dt * (AUTO_BLEND_RATE + beatKick * 0.25f)
        if (blend >= 1f) {
            blend = 0f
            autoIndex = (autoIndex + 1) % EmergenceField.CONCRETE_FIELDS.size
            fieldA = fieldB
            fieldB = EmergenceField.CONCRETE_FIELDS[(autoIndex + 1) % EmergenceField.CONCRETE_FIELDS.size]
        }
    }

    private fun binParticles(radius: Float) {
        val cells = (GRID_SPAN / radius).toInt().coerceIn(4, MAX_CELLS_PER_AXIS)
        cellHeads.fill(-1, 0, cells * cells)
        for (i in 0 until count) {
            val cxI = cellIndex(px[i], cells)
            val cyI = cellIndex(py[i], cells)
            val cell = cyI * cells + cxI
            cellNext[i] = cellHeads[cell]
            cellHeads[cell] = i
        }
        activeCells = cells
    }

    private var activeCells = 4

    private fun cellIndex(
        v: Float,
        cells: Int,
    ): Int = (((v + RESPAWN_EDGE) / GRID_SPAN) * cells).toInt().coerceIn(0, cells - 1)

    private fun densityAt(
        self: Int,
        x: Float,
        y: Float,
        radius: Float,
    ): Float {
        val cells = activeCells
        val cxI = cellIndex(x, cells)
        val cyI = cellIndex(y, cells)
        var u = 0f
        for (gy in (cyI - 1).coerceAtLeast(0)..(cyI + 1).coerceAtMost(cells - 1)) {
            for (gx in (cxI - 1).coerceAtLeast(0)..(cxI + 1).coerceAtMost(cells - 1)) {
                u += cellDensity(cellHeads[gy * cells + gx], self, x, y, radius)
            }
        }
        return u / DENSITY_NORM
    }

    private fun cellDensity(
        head: Int,
        self: Int,
        x: Float,
        y: Float,
        radius: Float,
    ): Float {
        val r2 = radius * radius
        var u = 0f
        var j = head
        while (j >= 0) {
            if (j != self) {
                val dx = px[j] - x
                val dy = py[j] - y
                val d2 = dx * dx + dy * dy
                if (d2 < r2) u += EmergenceField.kernel(sqrt(d2) / radius)
            }
            j = cellNext[j]
        }
        return u
    }

    private fun pickDonor(): Int {
        repeat(DONOR_TRIES) {
            val j = random.nextInt(count)
            if (energy[j] > 0.5f) return j
        }
        return random.nextInt(count)
    }

    fun centroid(out: FloatArray) {
        out[0] = centroidX
        out[1] = centroidY
    }

    fun beatEnvelope(): Float = beatKick
}
