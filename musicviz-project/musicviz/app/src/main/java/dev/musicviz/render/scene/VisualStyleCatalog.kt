package dev.musicviz.render.scene

/**
 * Stable, user-facing variants for the two authored visual families.
 *
 * The renderer keeps one implementation per family. A style is therefore a
 * compact recipe: it selects a shader branch and biases the family's existing
 * controls without duplicating the audio plumbing, lifecycle, fluid solver or
 * export path. IDs are persisted by presets, so append new entries and never
 * rename an existing id.
 */
internal object VisualStyleCatalog {
    data class HyperspaceStyle(
        val id: String,
        val label: String,
        val shaderStyle: Int,
        val bodyScale: Float = 1f,
        val cameraScale: Float = 1f,
        val fieldScale: Float = 1f,
        val glowScale: Float = 1f,
        val neonScale: Float = 1f,
        val hazeScale: Float = 1f,
        val meltScale: Float = 1f,
        val stainScale: Float = 1f,
        val liquidScale: Float = 1f,
        val ridgeScale: Float = 1f,
        val forcedSpecies: Int? = null,
    )

    data class CymaticsStyle(
        val id: String,
        val label: String,
        val shaderStyle: Int,
        /** null keeps the user's geometry choice; 0 dish, 1 plate. */
        val geometryOverride: Int? = null,
        val scale: Float = 1f,
        val fill: Float = 1f,
        val line: Float = 1f,
        val glow: Float = 1f,
        val iridescence: Float = 1f,
        val caustic: Float = 1f,
        val flow: Float = 1f,
        val swirl: Float = 1f,
    )

    val hyperspace: List<HyperspaceStyle> =
        listOf(
            // Backwards-compatible original. It remains selectable beside the
            // ten substyles and keeps every saved v1.4-v1.7 preset valid.
            HyperspaceStyle(SceneIds.HYPERSPACE, "Original · Living Fractals", 0),
            HyperspaceStyle(
                "hyper_polytope",
                "Polytope",
                1,
                bodyScale = 0.82f,
                cameraScale = 1.08f,
                fieldScale = 0.72f,
                neonScale = 1.28f,
                forcedSpecies = 3,
            ),
            HyperspaceStyle(
                "hyper_liquid_warp",
                "Liquid Warp",
                2,
                bodyScale = 0.78f,
                fieldScale = 0.82f,
                glowScale = 1.18f,
                meltScale = 1.35f,
                liquidScale = 1.35f,
                ridgeScale = 0.55f,
            ),
            HyperspaceStyle(
                "hyper_caduceus",
                "Caduceus",
                3,
                bodyScale = 0.72f,
                cameraScale = 1.12f,
                fieldScale = 0.58f,
                neonScale = 1.38f,
                meltScale = 0.85f,
                forcedSpecies = 4,
            ),
            HyperspaceStyle(
                "hyper_cortex",
                "Cortex",
                4,
                bodyScale = 1.14f,
                cameraScale = 0.94f,
                fieldScale = 1.15f,
                hazeScale = 0.82f,
                ridgeScale = 1.45f,
                stainScale = 1.2f,
                forcedSpecies = 4,
            ),
            HyperspaceStyle(
                "hyper_reliquary",
                "Reliquary",
                5,
                bodyScale = 0.68f,
                cameraScale = 1.18f,
                fieldScale = 0.45f,
                glowScale = 0.82f,
                neonScale = 1.5f,
                hazeScale = 0.72f,
                forcedSpecies = 2,
            ),
            HyperspaceStyle(
                "hyper_moire",
                "Moiré",
                6,
                bodyScale = 0.92f,
                fieldScale = 1.5f,
                glowScale = 0.92f,
                neonScale = 0.72f,
                meltScale = 0.45f,
                liquidScale = 0.42f,
            ),
            HyperspaceStyle(
                "hyper_foam",
                "Foam",
                7,
                bodyScale = 1.32f,
                cameraScale = 0.9f,
                fieldScale = 0.38f,
                glowScale = 1.22f,
                neonScale = 0.72f,
                hazeScale = 1.15f,
                stainScale = 1.3f,
                forcedSpecies = 1,
            ),
            HyperspaceStyle(
                "hyper_dustskin",
                "Dustskin",
                8,
                bodyScale = 0.9f,
                fieldScale = 0.82f,
                glowScale = 1.3f,
                neonScale = 0.55f,
                hazeScale = 1.3f,
                liquidScale = 0.75f,
                ridgeScale = 0.65f,
            ),
            HyperspaceStyle(
                "hyper_plume",
                "Plume",
                9,
                bodyScale = 0.62f,
                cameraScale = 1.2f,
                fieldScale = 0.5f,
                glowScale = 1.35f,
                neonScale = 0.48f,
                hazeScale = 1.42f,
                meltScale = 1.7f,
                stainScale = 1.2f,
                liquidScale = 1.75f,
                ridgeScale = 0.3f,
            ),
            HyperspaceStyle(
                "hyper_resonant_wormhole",
                "Resonant Wormhole",
                10,
                bodyScale = 1.18f,
                cameraScale = 0.92f,
                fieldScale = 0.92f,
                glowScale = 1.12f,
                neonScale = 0.9f,
                hazeScale = 0.84f,
                meltScale = 1.15f,
                stainScale = 1.25f,
                liquidScale = 1.15f,
            ),
        )

    val cymatics: List<CymaticsStyle> =
        listOf(
            CymaticsStyle(SceneIds.CYMATICS, "Original · Resonant Field", 0),
            CymaticsStyle(
                "chladni_sand",
                "Chladni Sand",
                1,
                geometryOverride = 1,
                scale = 1.08f,
                fill = 0.2f,
                line = 1.28f,
                glow = 0.58f,
                iridescence = 0.18f,
                caustic = 0.25f,
                flow = 0.35f,
            ),
            CymaticsStyle(
                "bessel_drum",
                "Drumhead",
                2,
                geometryOverride = 0,
                scale = 0.92f,
                fill = 1.15f,
                line = 0.88f,
                glow = 0.85f,
                iridescence = 0.45f,
                caustic = 0.9f,
            ),
            CymaticsStyle(
                "harmonograph",
                "Harmonograph",
                3,
                scale = 0.82f,
                fill = 0.42f,
                line = 1.3f,
                glow = 1.25f,
                iridescence = 0.8f,
                caustic = 0.35f,
                flow = 1.35f,
                swirl = 1.4f,
            ),
            CymaticsStyle(
                "faraday",
                "Faraday",
                4,
                geometryOverride = 0,
                scale = 1.18f,
                fill = 1.1f,
                line = 0.78f,
                glow = 1.08f,
                iridescence = 0.65f,
                caustic = 1.2f,
                flow = 1.55f,
            ),
            CymaticsStyle(
                "harmonic_shell",
                "Harmonic Shell",
                5,
                geometryOverride = 0,
                scale = 0.72f,
                fill = 1.2f,
                line = 0.72f,
                glow = 0.95f,
                iridescence = 1.25f,
                caustic = 1.3f,
                swirl = 0.45f,
            ),
            CymaticsStyle(
                "caustic_sheet",
                "Caustic Sheet",
                6,
                scale = 1.05f,
                fill = 1.28f,
                line = 0.55f,
                glow = 0.88f,
                iridescence = 1.3f,
                caustic = 1.65f,
                flow = 1.2f,
            ),
            CymaticsStyle(
                "levitator",
                "Levitator",
                7,
                geometryOverride = 1,
                scale = 0.88f,
                fill = 0.45f,
                line = 0.92f,
                glow = 1.42f,
                iridescence = 0.6f,
                caustic = 0.72f,
                flow = 0.55f,
            ),
            CymaticsStyle(
                "standing_chamber",
                "Standing Chamber",
                8,
                geometryOverride = 1,
                scale = 0.78f,
                fill = 0.75f,
                line = 0.92f,
                glow = 1.18f,
                iridescence = 0.5f,
                caustic = 0.8f,
                flow = 0.65f,
            ),
            CymaticsStyle(
                "rosensweig",
                "Rosensweig Spikes",
                9,
                geometryOverride = 0,
                scale = 1.15f,
                fill = 1.35f,
                line = 0.65f,
                glow = 1.0f,
                iridescence = 0.35f,
                caustic = 1.45f,
                flow = 0.48f,
            ),
            CymaticsStyle(
                "kundt_tube",
                "Kundt Tube",
                10,
                geometryOverride = 1,
                scale = 0.7f,
                fill = 0.68f,
                line = 1.25f,
                glow = 0.9f,
                iridescence = 0.35f,
                caustic = 0.55f,
                flow = 1.1f,
            ),
        )

    val hyperspaceIds: List<String> = hyperspace.map { it.id }
    val cymaticsIds: List<String> = cymatics.map { it.id }

    private val hyperspaceById = hyperspace.associateBy { it.id }
    private val cymaticsById = cymatics.associateBy { it.id }

    fun hyperspace(id: String): HyperspaceStyle? = hyperspaceById[id]

    fun cymatics(id: String): CymaticsStyle? = cymaticsById[id]

    fun isHyperspace(id: String): Boolean = id in hyperspaceById

    fun isCymatics(id: String): Boolean = id in cymaticsById

    fun label(id: String): String = hyperspaceById[id]?.label ?: cymaticsById[id]?.label ?: id
}
