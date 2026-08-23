package dev.geode.render.scene

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

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
