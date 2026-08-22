package dev.geode.render.scene

import kotlin.random.Random

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

    @Suppress("NestedBlockDepth", "LoopWithTooManyJumpStatements")
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

    @Suppress("LoopWithTooManyJumpStatements")
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
