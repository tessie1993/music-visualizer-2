package dev.geode.render.scene

import kotlin.math.max

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
