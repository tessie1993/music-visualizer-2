package dev.geode.render.fluid

internal object FluidQuality {
    data class Tier(
        val label: String,
        val simRes: Int,
        val dyeRes: Int,
        val particleSide: Int,
        val iterations: Int,
    )

    val TIERS: List<Tier> =
        listOf(
            Tier("Ultra", 256, 1024, 1024, 28),
            Tier("High", 192, 768, 768, 24),
            Tier("Medium", 128, 512, 512, 20),
            Tier("Low", 96, 384, 320, 16),
            Tier("Min", 64, 256, 160, 12),
        )

    val LABELS: List<String> = TIERS.map { it.label }

    fun tier(index: Int): Tier = TIERS[index.coerceIn(0, TIERS.size - 1)]

    fun effectiveIndex(
        userIndex: Int,
        autoDowngradeSteps: Int,
    ): Int = (userIndex.coerceIn(0, TIERS.size - 1) + autoDowngradeSteps.coerceAtLeast(0)).coerceAtMost(TIERS.size - 1)
}
