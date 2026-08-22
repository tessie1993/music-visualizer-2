package dev.geode.render.scene

import dev.geode.R

object SceneCapabilities {
    val SHADER_SCENES: Map<String, Int> =
        linkedMapOf(
            SceneIds.JULIA to R.raw.julia_frag,
            SceneIds.TUNNEL to R.raw.tunnel_frag,
            SceneIds.MANDEL to R.raw.mandel_frag,
            SceneIds.KALEIDO to R.raw.kaleido_frag,
            SceneIds.PLASMA to R.raw.plasma_frag,
            SceneIds.BARS to R.raw.bars_frag,
            SceneIds.RING to R.raw.ring_frag,
            SceneIds.SCOPE to R.raw.scope_frag,
            SceneIds.LISS to R.raw.liss_frag,
            SceneIds.WARP to R.raw.warp_frag,
            SceneIds.GRID to R.raw.grid_frag,
            SceneIds.VORONOI to R.raw.voronoi_frag,
            SceneIds.METABALLS to R.raw.metaballs_frag,
            SceneIds.RIPPLES to R.raw.ripples_frag,
            SceneIds.STARFIELD to R.raw.starfield_frag,
            SceneIds.WAVES to R.raw.waves_frag,
            SceneIds.HEXGRID to R.raw.hexgrid_frag,
            SceneIds.SPIRAL to R.raw.spiral_frag,
            SceneIds.AURORA to R.raw.aurora_frag,
            SceneIds.SOLAR to R.raw.solar_frag,
            SceneIds.WINTER to R.raw.winter_frag,
            SceneIds.LAVA to R.raw.lava_frag,
        )

    fun isFluid(sceneId: String): Boolean = sceneId == SceneIds.FLUID

    fun isWater(sceneId: String): Boolean = sceneId == SceneIds.WATER

    fun isBeam(sceneId: String): Boolean = sceneId == SceneIds.BEAM

    fun isCymatics(sceneId: String): Boolean = VisualStyleCatalog.isCymatics(sceneId)

    fun isHyperspace(sceneId: String): Boolean = VisualStyleCatalog.isHyperspace(sceneId)

    fun hasJourney(sceneId: String): Boolean = sceneId == SceneIds.FLUID || sceneId == SceneIds.CURLFLOW || sceneId == SceneIds.WATER

    fun hasEmitters(sceneId: String): Boolean = sceneId == SceneIds.FLUID || sceneId == SceneIds.WATER

    fun hasParticleLayer(sceneId: String): Boolean = sceneId == SceneIds.FLUID || sceneId == SceneIds.CURLFLOW

    fun usesPointSprites(sceneId: String): Boolean = hasParticleLayer(sceneId)

    fun hasShaderLook(sceneId: String): Boolean = sceneId in SHADER_SCENES
}
