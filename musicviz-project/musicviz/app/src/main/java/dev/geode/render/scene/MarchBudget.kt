package dev.geode.render.scene

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
