package dev.musicviz.engine

import android.content.Context

/**
 * Which visual/analysis engine generation is in charge.
 *
 * The 2.0 overhaul is a strangler migration: V2 subsystems are built beside the
 * legacy ones and this switch decides which of them actually runs. Exactly one
 * generation is active at a time - the two full engines are never run
 * concurrently, because two live analysis schedulers and two GL resource owners
 * would compete for the same audio and the same context.
 *
 * [LEGACY] stays the production default until the V2 vertical slice clears its
 * gates. See `docs/v2/MASTER_PLAN.md` and ADR-0001.
 */
enum class EngineGeneration {
    LEGACY,
    V2,
    ;

    companion object {
        /** The generation a fresh install runs. */
        val DEFAULT: EngineGeneration = LEGACY

        /**
         * Decides what actually runs, given what the user asked for and whether
         * V2 can run here.
         *
         * V2 availability is a runtime property - it depends on GPU capability
         * probes that can fail on a specific driver - so a request is not a
         * guarantee. When V2 cannot run, this degrades to [LEGACY] and says so
         * through [EngineSelection.FellBack]: a silent black frame is the one
         * outcome the switch exists to prevent.
         */
        fun resolve(
            requested: EngineGeneration,
            v2Available: Boolean,
        ): EngineSelection =
            when (requested) {
                // Legacy is the shipped engine: it is always available, so a
                // request for it can never fall back.
                LEGACY -> EngineSelection.Active(LEGACY)
                V2 ->
                    if (v2Available) {
                        EngineSelection.Active(V2)
                    } else {
                        EngineSelection.FellBack(
                            requested = V2,
                            actual = LEGACY,
                            reason = UNAVAILABLE_REASON,
                        )
                    }
            }

        /**
         * Shown to the user verbatim. Phrased as a capability statement rather
         * than an error: on a device whose driver fails the V2 probes this is
         * the expected outcome, not a fault the user can fix.
         */
        const val UNAVAILABLE_REASON: String =
            "The 2.0 engine cannot run on this device's GPU. Using the current engine instead."
    }
}

/**
 * The outcome of [EngineGeneration.resolve]: either the requested generation is
 * running, or it is not and the reason is carried with it.
 *
 * Modelled as a sealed type so "fell back" cannot be represented as a silently
 * ignored flag - a caller must handle both cases to read [active].
 */
sealed interface EngineSelection {
    /** The generation actually driving analysis and rendering. */
    val active: EngineGeneration

    /** The requested generation is running. */
    data class Active(
        override val active: EngineGeneration,
    ) : EngineSelection

    /** The requested generation could not run; [actual] is running instead. */
    data class FellBack(
        val requested: EngineGeneration,
        val actual: EngineGeneration,
        /** User-facing explanation. Never blank - the UI shows this. */
        val reason: String,
    ) : EngineSelection {
        override val active: EngineGeneration get() = actual
    }
}

/**
 * Persists the requested [EngineGeneration] (same shared-preferences pattern as
 * [dev.musicviz.data.ExportPrefsStore]).
 *
 * An unreadable or unknown stored value resolves to [EngineGeneration.DEFAULT]
 * rather than throwing: a preference file written by a newer build, or edited by
 * hand, must not stop the app from starting.
 */
class EngineGenerationStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("musicviz-prefs", Context.MODE_PRIVATE)

    fun load(): EngineGeneration {
        val stored = prefs.getString(KEY_GENERATION, null) ?: return EngineGeneration.DEFAULT
        return runCatching { EngineGeneration.valueOf(stored) }.getOrDefault(EngineGeneration.DEFAULT)
    }

    fun save(generation: EngineGeneration) {
        prefs.edit().putString(KEY_GENERATION, generation.name).apply()
    }

    private companion object {
        const val KEY_GENERATION = "engine_generation"
    }
}
