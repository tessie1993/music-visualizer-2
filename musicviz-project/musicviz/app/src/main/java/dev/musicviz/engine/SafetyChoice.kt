package dev.musicviz.engine

/**
 * Why the final frame is, or is not, being clamped for photosensitivity.
 *
 * Distinguishing the last two matters for truthfulness: an export or a
 * performance take that ran unclamped because an adult asked for that is a
 * different artefact from one that ran unclamped because nobody was asked.
 */
sealed interface SafetyPolicy {
    /** The user opted in; the clamp is active. */
    data object Clamped : SafetyPolicy

    /** No v2 choice yet, so the clamp is active by default and a prompt is owed. */
    data object ClampedPendingChoice : SafetyPolicy

    /** The user explicitly opted out, with the warning shown. */
    data object UnrestrictedByUserChoice : SafetyPolicy
}

/**
 * Whether the user has made the MusicViz 2.0 photosensitivity choice, and what
 * follows from it.
 *
 * ## The defect this exists to fix
 *
 * Before 2.0, `GuiPrefs.safeVisuals` defaulted to `false` and the persisted
 * default matched. A 9 Hz full-frame strobe was therefore reachable by a user
 * who had never been asked anything. [dev.musicviz.render.VisualSafety] was not
 * at fault - the clamp is sound - the fault was that **absence of an answer was
 * being treated as an answer**.
 *
 * So the stored boolean alone is not enough to decide anything. It is only
 * meaningful alongside [CURRENT_VERSION]: a stored `false` with no version is an
 * unanswered question and resolves to protected-and-prompt, while a stored
 * `false` carrying the version is a real opt-out and is honoured.
 *
 * The version marker also lets a future change to *what the choice means*
 * re-ask, instead of inheriting consent given for a different question.
 */
sealed interface SafetyChoice {
    /** Safe visuals as the engine should run them. */
    val safeVisuals: Boolean

    /** Whether the user still owes an answer, i.e. show the prompt. */
    val mustPrompt: Boolean

    /** How this state should be reported in exports, takes and diagnostics. */
    val policy: SafetyPolicy

    /** No v2 choice on record: protect first, ask before any visuals. */
    data object NotChosen : SafetyChoice {
        override val safeVisuals: Boolean get() = true
        override val mustPrompt: Boolean get() = true
        override val policy: SafetyPolicy get() = SafetyPolicy.ClampedPendingChoice
    }

    /** The user answered, at [version]. */
    data class Chosen(
        val version: Int,
        override val safeVisuals: Boolean,
    ) : SafetyChoice {
        override val mustPrompt: Boolean get() = false
        override val policy: SafetyPolicy
            get() =
                if (safeVisuals) SafetyPolicy.Clamped else SafetyPolicy.UnrestrictedByUserChoice
    }

    companion object {
        /**
         * The choice generation. Bump only when the question itself changes
         * enough that an older answer no longer covers it - bumping re-prompts
         * every user, which is a cost, not a formality.
         */
        const val CURRENT_VERSION: Int = 1

        /**
         * Resolves stored preferences into a choice.
         *
         * [storedVersion] is null when the preference has never been written.
         * A version below [CURRENT_VERSION] is treated as unanswered; a version
         * above it is respected rather than downgraded, so a preference file
         * from a newer build does not cause a re-prompt loop or quietly flip the
         * user's setting back on.
         */
        fun resolve(
            storedVersion: Int?,
            storedSafeVisuals: Boolean,
        ): SafetyChoice =
            if (storedVersion == null || storedVersion < CURRENT_VERSION) {
                NotChosen
            } else {
                Chosen(version = storedVersion, safeVisuals = storedSafeVisuals)
            }
    }
}
