package dev.musicviz.render.fluid

/**
 * F6 quality tiers per FLUID_SIM v2 section 10: one enum-like index sets all
 * quality axes at once (fluid grid, dye grid, particle count, solver
 * iterations) so the app reasons about a single value. The grid-scale-correct
 * Jacobi (alpha = -dx^2) is what makes tier changes alter fidelity, not the
 * character of the flow.
 */
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

    /**
     * The tier actually run: the user's chosen tier, further downgraded by
     * the automatic monitor's latch (larger index = lower quality). Manual
     * selection always wins in the upgrade direction is NOT allowed here -
     * the auto latch only ever lowers.
     */
    fun effectiveIndex(
        userIndex: Int,
        autoDowngradeSteps: Int,
    ): Int = (userIndex.coerceIn(0, TIERS.size - 1) + autoDowngradeSteps.coerceAtLeast(0)).coerceAtMost(TIERS.size - 1)
}
