package dev.synesthesia.core.visualizer

import dev.synesthesia.core.audio.AudioFeatures

enum class CostClass { BASE, HIGH, ULTRA }
enum class Statefulness { STATELESS, CHECKPOINTABLE }

data class StyleManifest(
    val styleId: String,
    val displayName: String,
    val costClass: CostClass,
    val statefulness: Statefulness,
    val glesFloor: Int = 30,
)

/** Injected clock: media-clock live | frame-index offline. One render fn both modes. */
interface RenderClock {
    val frameIndex: Long
    val seconds: Double
}

/** Stage-1 param-space clamp output (WCAG ceiling, non-defeatable, LAST in ModRouter). */
fun interface ParamClamp {
    fun clamp(resolved: Map<String, Float>): Map<String, Float>
}

fun interface ModulationRouter {
    fun resolve(base: Map<String, Float>, features: AudioFeatures): Map<String, Float>
}

/**
 * A visual style. Determinism law: all randomness via SeededRng registry;
 * adaptivity frozen offline; checkpointables serialize sim state at boundaries.
 */
interface Style {
    val manifest: StyleManifest
    fun initialize(width: Int, height: Int)
    fun render(clock: RenderClock, features: AudioFeatures, params: Map<String, Float>)
    fun dispose()
}
