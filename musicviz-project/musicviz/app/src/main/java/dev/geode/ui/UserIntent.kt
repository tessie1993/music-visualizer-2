package dev.geode.ui

import androidx.annotation.StringRes
import dev.geode.R

/**
 * What the person opening the app came here to do.
 *
 * This is the only question first run asks, and it earns its place by deciding two things at
 * once: which tab the app lands on, and whether Studio appears in the navigation at all. Someone
 * who only wants to listen should not have to walk past a render queue to reach their music.
 *
 * Changeable later in Settings — it is a starting point, not a commitment.
 */
enum class UserIntent(
    @param:StringRes val labelRes: Int,
    @param:StringRes val detailRes: Int,
) {
    LISTENING(R.string.first_run_intent_listening, R.string.first_run_intent_listening_detail),
    MAKING_VIDEOS(R.string.first_run_intent_videos, R.string.first_run_intent_videos_detail),
    BOTH(R.string.first_run_intent_both, R.string.first_run_intent_both_detail),
    ;

    /** Studio is hidden for pure listeners; everyone else gets it. */
    val showsStudio: Boolean get() = this != LISTENING

    /** Where the app opens. Video-first users land in Studio, everyone else on the Stage. */
    val landingDestination: GeodeDestination
        get() =
            when (this) {
                LISTENING, BOTH -> GeodeDestination.PLAYER
                MAKING_VIDEOS -> GeodeDestination.STUDIO
            }
}
