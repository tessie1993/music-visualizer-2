package dev.musicviz.ui

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic geometry for the crystal background texture: mineral veins
 * (jittered polylines) and sparkle flecks, in normalized 0..1 coordinates so
 * the composable can scale them to any canvas size. Pure Kotlin (no android
 * types) so it runs in the headless JUnit gate; a given seed always yields
 * the same texture, which keeps recompositions stable frame to frame.
 */
object CrystalMath {
    /** A single sparkle fleck: position in 0..1 plus a size/alpha weight in 0..1. */
    data class Fleck(
        val x: Float,
        val y: Float,
        val weight: Float,
    )

    /**
     * Generates [count] veins seeded by [seed]. Each vein is a random walk
     * with a persistent heading plus per-step jitter, which reads as mineral
     * strands rather than pure noise. Every vein has [segments] + 1 points
     * (the start plus one per step), all clamped into 0..1.
     */
    fun veins(
        seed: Int,
        count: Int,
        segments: Int,
    ): List<List<Pair<Float, Float>>> {
        val rng = Random(seed)
        return List(count) {
            var x = rng.nextFloat()
            var y = rng.nextFloat()
            var heading = rng.nextFloat() * TWO_PI
            val step = 0.05f + rng.nextFloat() * 0.06f
            val points = ArrayList<Pair<Float, Float>>(segments + 1)
            points.add(x to y)
            repeat(segments) {
                heading += (rng.nextFloat() - 0.5f) * 1.4f
                x = (x + cos(heading) * step).coerceIn(0f, 1f)
                y = (y + sin(heading) * step).coerceIn(0f, 1f)
                points.add(x to y)
            }
            points
        }
    }

    /** Generates [count] flecks seeded by [seed], positions and weights in 0..1. */
    fun flecks(
        seed: Int,
        count: Int,
    ): List<Fleck> {
        val rng = Random(seed)
        return List(count) { Fleck(rng.nextFloat(), rng.nextFloat(), rng.nextFloat()) }
    }

    private const val TWO_PI = (2.0 * Math.PI).toFloat()
}
