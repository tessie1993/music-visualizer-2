package dev.musicviz.analysis

import dev.musicviz.render.scene.SceneIds

/** Intelligence modes selectable by the user. */
enum class IntelligenceMode { MANUAL, SUGGEST, AUTO }

/**
 * Maps track/section characteristics to a scene recommendation.
 * Deliberately simple, transparent rules; fully overridable by the user.
 */
object SceneSuggester {
    // Aliases of the renderer's stable ids, NOT free literals: a renamed scene
    // id must fail to compile here rather than silently suggest a scene the
    // renderer no longer knows.
    const val SCENE_NEBULA = SceneIds.NEBULA
    const val SCENE_BURSTS = SceneIds.BURSTS
    const val SCENE_JULIA = SceneIds.JULIA
    const val SCENE_TUNNEL = SceneIds.TUNNEL

    fun suggestForTrack(timeline: FeatureTimeline): String = suggest(timeline.bpm, timeline.averageEnergy, timeline.averageCentroid)

    fun suggest(
        bpm: Float,
        energy: Float,
        centroid: Float,
    ): String =
        when {
            bpm >= 125f && energy > 0.25f -> SCENE_BURSTS
            centroid > 0.45f -> SCENE_TUNNEL
            energy < 0.12f -> SCENE_NEBULA
            else -> SCENE_JULIA
        }
}
