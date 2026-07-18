package dev.musicviz.analysis

/** Intelligence modes selectable by the user. */
enum class IntelligenceMode { MANUAL, SUGGEST, AUTO }

/**
 * Maps track/section characteristics to a scene recommendation.
 * Deliberately simple, transparent rules; fully overridable by the user.
 */
object SceneSuggester {
    const val SCENE_NEBULA = "nebula"
    const val SCENE_BURSTS = "bursts"
    const val SCENE_JULIA = "julia"
    const val SCENE_TUNNEL = "tunnel"

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
