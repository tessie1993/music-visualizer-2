package dev.geode.render.scene

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

object EmergenceField {
    const val AUTO: Int = 0
    const val THOMAS: Int = 1
    const val DEJONG: Int = 2
    const val CLIFFORD: Int = 3
    const val BLOOM: Int = 4

    val CONCRETE_FIELDS: IntArray = intArrayOf(THOMAS, DEJONG, CLIFFORD, BLOOM)

    const val GROWTH_SIGMA: Float = 0.13f
    const val KERNEL_MU: Float = 0.5f
    const val KERNEL_SIGMA: Float = 0.16f

    fun growth(
        u: Float,
        mu: Float,
    ): Float {
        val d = (u - mu) / GROWTH_SIGMA
        return 2f * exp(-0.5f * d * d) - 1f
    }

    fun kernel(rOverR: Float): Float {
        val d = (rOverR - KERNEL_MU) / KERNEL_SIGMA
        return exp(-0.5f * d * d)
    }

    fun velocity(
        field: Int,
        x: Float,
        y: Float,
        phase: Float,
        breathe: Float,
        out: FloatArray,
    ) {
        when (field) {
            THOMAS -> {
                out[0] = sin(3.1f * y + phase) - 0.55f * x
                out[1] = sin(3.1f * x - phase * 0.9f) - 0.55f * y
            }
            DEJONG -> {
                val a = 1.641f + 0.35f * sin(phase * 0.31f)
                val b = 1.902f + 0.35f * sin(phase * 0.23f + 2f)
                val tx = (sin(a * y) - cos(b * x) * 0.9f) * 0.72f
                val ty = (sin(1.4f * x + phase * 0.17f) - cos(2.2f * y) * 0.9f) * 0.72f
                out[0] = (tx - x) * 2.1f
                out[1] = (ty - y) * 2.1f
            }
            CLIFFORD -> {
                val a = -1.4f + 0.25f * sin(phase * 0.19f)
                val b = 1.6f + 0.25f * cos(phase * 0.29f)
                val tx = (sin(a * y) + 0.7f * cos(a * x)) * 0.68f
                val ty = (sin(b * x) + 0.7f * cos(b * y)) * 0.68f
                out[0] = (tx - x) * 2.4f
                out[1] = (ty - y) * 2.4f
            }
            else -> {
                val r2 = x * x + y * y
                val ring = 0.35f + 0.3f * breathe
                val pull = (ring * ring - r2) * 1.8f
                val swirl = 0.9f + 0.6f * sin(phase * 0.5f)
                out[0] = -y * swirl + x * pull
                out[1] = x * swirl + y * pull
            }
        }
    }
}
