package dev.musicviz.render.space

import dev.musicviz.render.fluid.FluidQuality

/**
 * One tier ladder for every style in both new families, on the same index as
 * `FluidQuality` so the existing "Quality" control and the existing
 * `PerformanceMonitor` keep meaning what they already mean.
 *
 * Two properties of that existing machinery every budget in this package is
 * written against, both easy to forget:
 *
 *  - **The target is 50 fps, not 60** (`PerformanceMonitor(targetFps = 50f)`).
 *    A budget computed against 60 is 20 percent optimistic before anything
 *    else has been counted.
 *  - **The auto latch only ever LOWERS** ([effectiveIndex] delegates to
 *    `FluidQuality.effectiveIndex`, and a scene's downgrade counter never
 *    decreases within a session). A style that only reads correctly at its top
 *    tier will therefore spend most of a session looking wrong with no path
 *    back, so every style states what its lowest tier actually looks like and
 *    the numbers here are chosen so that the bottom of the ladder is a style
 *    that is coarser, not a style that is broken.
 *
 * The tiers are a ceiling, not an instruction: a style asks for what it wants
 * and takes the smaller of the two ([resTargetScale] is the model). A cheap
 * style is not obliged to spend Ultra's budget.
 */
internal object QualityLadder {
    /**
     * @param meshSide vertices per side of a [SpaceMesh.grid]. Squared, so
     *   this is the axis that moves cost fastest.
     * @param marchSteps ceiling on a raymarch loop.
     * @param marchRelaxation multiplier on each step of a sphere trace. Never
     *   above 1: over-relaxation needs the miss-detection-and-backtrack
     *   machinery of enhanced sphere tracing, which no style here ships, and
     *   without it a step longer than the distance estimate walks through thin
     *   geometry and shows up as holes rather than as an overshoot.
     * @param grainSide side of a square GPGPU particle state texture; the live
     *   grain count is its square.
     * @param atlasSlices z-slices in a flat-3D volume atlas.
     * @param resScale the largest internal render scale this tier allows,
     *   BEFORE the supersample correction in [ResTarget.scaleFor].
     */
    data class Tier(
        val label: String,
        val meshSide: Int,
        val marchSteps: Int,
        val marchRelaxation: Float,
        val grainSide: Int,
        val atlasSlices: Int,
        val resScale: Float,
    ) {
        val grainCount: Int get() = grainSide * grainSide
    }

    /**
     * Index for index with `FluidQuality.TIERS`, and labelled identically:
     * they are the same user-facing setting, and a "Low" that meant two
     * different things in one app would be a bug report nobody could reproduce.
     *
     * The anchors: Medium is the tier the app ships at
     * (`SceneParams.fluidQuality` defaults to 2) and its numbers are the ones
     * the per-style budgets were computed against - a 192-vertex sheet, 80
     * march steps, 0.6 internal scale. Low is the rescue tier the auto latch
     * drops to and it is a real step down (96 vertices, 65k grains) because a
     * tier that saves ten percent is not worth latching to.
     */
    val TIERS: List<Tier> =
        listOf(
            // label, meshSide, marchSteps, marchRelaxation, grainSide, atlasSlices, resScale
            Tier("Ultra", 256, 128, 0.85f, 512, 48, 0.8f),
            Tier("High", 224, 104, 0.90f, 448, 40, 0.7f),
            Tier("Medium", 192, 80, 0.95f, 384, 32, 0.6f),
            Tier("Low", 96, 56, 1.00f, 256, 24, 0.45f),
            Tier("Min", 64, 32, 1.00f, 128, 16, 0.33f),
        )

    fun tier(index: Int): Tier = TIERS[index.coerceIn(0, TIERS.size - 1)]

    /**
     * The tier actually run. Delegates so there is ONE latch law in the app: a
     * second implementation of "manual choice, further downgraded by the
     * monitor, never upgraded" would be a second thing to keep in step with
     * the fluid family's behaviour, and the two are the same control.
     */
    fun effectiveIndex(
        userIndex: Int,
        autoDowngradeSteps: Int,
    ): Int = FluidQuality.effectiveIndex(userIndex, autoDowngradeSteps)

    /**
     * The internal render scale for a style that wants [requested] at full
     * quality: capped by the tier, then corrected for supersampling.
     *
     * The cap is what makes a tier change visible on a style that already
     * renders small - a volumetric asking for 0.2 keeps 0.2 at every tier,
     * because 0.2 of the screen is its design and not its budget, while a
     * style asking for 0.8 loses scale as the ladder drops.
     */
    fun resTargetScale(
        requested: Float,
        tierIndex: Int,
        supersample: Float,
    ): Float = ResTarget.scaleFor(minOf(requested, tier(tierIndex).resScale), supersample)
}
