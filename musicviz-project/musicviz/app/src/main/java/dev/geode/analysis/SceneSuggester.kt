package dev.geode.analysis

import dev.geode.render.scene.SceneIds

/** Intelligence modes selectable by the user. */
enum class IntelligenceMode { MANUAL, SUGGEST, AUTO }

/**
 * Maps track/section characteristics to a scene recommendation.
 *
 * v2: every candidate scene declares an affinity profile - the tempo range,
 * energy band and spectral character it looks best under, plus how much it
 * favors percussive over harmonic material and wide over narrow mixes - and
 * every scene is scored against the music instead of four rules picking from
 * four scenes. Transparent on purpose: a suggestion is the argmax of
 * [score] over [AFFINITIES], nothing hidden, and fully overridable by the
 * user. The extra signals (pulse/chroma confidence, stereo width) default to
 * "unknown" so callers with only the classic bpm/energy/centroid triple still
 * get a sensible answer.
 */
object SceneSuggester {
    // Aliases of the renderer's stable ids, NOT free literals: a renamed scene
    // id must fail to compile here rather than silently suggest a scene the
    // renderer no longer knows.
    const val SCENE_JULIA = SceneIds.JULIA
    const val SCENE_TUNNEL = SceneIds.TUNNEL

    /**
     * Catalog substyles have no [SceneIds] constant, so the alias resolves
     * through the catalog and fails at class-init - caught by any test that
     * touches this object - if the style is ever renamed.
     */
    val SCENE_MYCELIUM: String =
        requireNotNull(dev.geode.render.scene.VisualStyleCatalog.myco("myco_polycephalum")).id

    /**
     * How strongly a scene wants each measurable character of the music.
     * Ranges score 1 inside and fall off linearly outside (see [fit]).
     */
    data class Affinity(
        val sceneId: String,
        /** Comfortable tempo range, bpm. */
        val tempoBpm: ClosedFloatingPointRange<Float> = 60f..180f,
        /** Preferred energy band (rms, 0..~0.5 in practice). */
        val energy: ClosedFloatingPointRange<Float> = 0f..1f,
        /** Spectral-centroid preference: 0 = dark, 1 = bright. */
        val brightness: ClosedFloatingPointRange<Float> = 0f..1f,
        /** +1 favors percussive material, -1 favors harmonic; 0 = agnostic. */
        val percussiveBias: Float = 0f,
        /** Bonus per unit of stereo width above a narrow mix. */
        val widthBias: Float = 0f,
    )

    /**
     * The candidate pool with each scene's declared character. Curated, not
     * exhaustive: MilkDrop needs a user-loaded .milk to show anything, and
     * near-duplicate characters would only flap the argmax between twins.
     */
    val AFFINITIES: List<Affinity> =
        listOf(
            // Calm, dark, ambient.
            Affinity(
                SceneIds.AURORA,
                tempoBpm = 50f..110f,
                energy = 0f..0.18f,
                brightness = 0.1f..0.55f,
                percussiveBias = -0.4f,
                widthBias = 0.6f,
            ),
            Affinity(SceneIds.WATER, tempoBpm = 60f..115f, energy = 0.05f..0.22f, brightness = 0.1f..0.5f, percussiveBias = -0.5f),
            // Flowing mid-energy.
            Affinity(SceneIds.CURLFLOW, tempoBpm = 70f..130f, energy = 0.1f..0.3f, brightness = 0.15f..0.6f, percussiveBias = -0.2f),
            Affinity(SceneIds.JULIA, tempoBpm = 70f..140f, energy = 0.1f..0.3f, brightness = 0.15f..0.5f),
            Affinity(SceneIds.CYMATICS, tempoBpm = 60f..130f, energy = 0.08f..0.3f, brightness = 0.1f..0.5f, percussiveBias = -0.8f),
            Affinity(
                SceneIds.FLUID,
                tempoBpm = 90f..150f,
                energy = 0.18f..0.45f,
                brightness = 0.2f..0.7f,
                percussiveBias = 0.3f,
                widthBias = 0.4f,
            ),
            // Bright.
            Affinity(SceneIds.TUNNEL, tempoBpm = 90f..160f, energy = 0.15f..0.4f, brightness = 0.45f..1f),
            Affinity(SceneIds.HYPERSPACE, tempoBpm = 120f..200f, energy = 0.2f..0.5f, brightness = 0.4f..1f, percussiveBias = 0.2f),
            // Loud, percussive: the mycelium network reorganizes on every
            // hit (the beat breathes its sensors and scatters spores), which
            // is the character percussive material rewards.
            Affinity(
                SCENE_MYCELIUM,
                tempoBpm = 95f..200f,
                energy = 0.16f..0.7f,
                brightness = 0.1f..0.75f,
                percussiveBias = 0.6f,
                widthBias = 0.2f,
            ),
            // Flowing, wide: the silk filaments stretch with sustained energy
            // and paint the stereo width into their band lanes.
            Affinity(
                SceneIds.DEFAULT,
                tempoBpm = 60f..128f,
                energy = 0.08f..0.5f,
                brightness = 0.15f..0.8f,
                widthBias = 0.5f,
            ),
        )

    /** 1 inside [range], falling to 0 one range-width outside it. */
    internal fun fit(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
    ): Float {
        if (value in range) return 1f
        val span = (range.endInclusive - range.start).coerceAtLeast(1e-3f)
        val d = if (value < range.start) range.start - value else value - range.endInclusive
        return (1f - d / span).coerceAtLeast(0f)
    }

    /** The transparent scoring rule; energy weighs most because it is what a listener hears first. */
    internal fun score(
        a: Affinity,
        bpm: Float,
        energy: Float,
        centroid: Float,
        pulseConfidence: Float,
        chromaConfidence: Float,
        stereoWidth: Float,
    ): Float =
        1.5f * fit(energy, a.energy) +
            1.0f * fit(bpm, a.tempoBpm) +
            1.0f * fit(centroid, a.brightness) +
            a.percussiveBias * (pulseConfidence - chromaConfidence) +
            a.widthBias * (stereoWidth - 0.25f)

    fun suggestForTrack(timeline: FeatureTimeline): String {
        // Track-level medians of the newer signals, so a whole-track
        // suggestion sees the same characters a section-level one does.
        val frames = timeline.frames

        fun avg(pick: (AudioFeatures) -> Float): Float =
            if (frames.isEmpty()) 0f else frames.map { pick(it.features).toDouble() }.average().toFloat()
        return suggest(
            bpm = timeline.bpm,
            energy = timeline.averageEnergy,
            centroid = timeline.averageCentroid,
            pulseConfidence = avg { it.pulseConfidence },
            chromaConfidence = avg { it.chromaConfidence },
            stereoWidth = avg { it.stereoWidth },
        )
    }

    fun suggest(
        bpm: Float,
        energy: Float,
        centroid: Float,
        pulseConfidence: Float = 0f,
        chromaConfidence: Float = 0f,
        stereoWidth: Float = 0f,
    ): String = AFFINITIES.maxBy { score(it, bpm, energy, centroid, pulseConfidence, chromaConfidence, stereoWidth) }.sceneId
}
