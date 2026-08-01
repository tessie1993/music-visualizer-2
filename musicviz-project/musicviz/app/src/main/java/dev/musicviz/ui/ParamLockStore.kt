package dev.musicviz.ui

import android.content.Context
import dev.musicviz.render.scene.ParamKeys

/**
 * Preferences-backed [ParamLockRepository].
 *
 * The file is named "musicviz-mod" for historical reasons — it was introduced
 * for the modulation settings and only ever held the lock set, while the LFO
 * and ADSR configs went to [LfoStore]. The name is kept because renaming it
 * would strand every existing user's locks.
 *
 * Locks are handed to the rest of the app as control **labels**, which is what
 * the lock chips and [dev.musicviz.render.scene.ParamRandomizer] match on, but
 * they are written to disk as the stable ids in [ParamKeys]. Rewording a label
 * used to silently orphan every lock a user had set, because the reworded
 * control no longer matched the stored string; now the stored string does not
 * contain the wording at all.
 *
 * Sets written by older builds hold labels. They need no migration pass: no id
 * equals any label, so [ParamKeys.labelsOf] leaves a stored label untouched
 * and the set is rewritten in id form the next time a lock is toggled.
 */
class ParamLockStore(
    context: Context,
) : ParamLockRepository {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Copied out of preferences: `getStringSet` returns an instance the caller
     * must not modify and whose contents are undefined afterwards, so holding
     * it in a StateFlow would be borrowing something the framework can reclaim.
     */
    override fun load(): Set<String> = ParamKeys.labelsOf(prefs.getStringSet(KEY, null)?.toSet() ?: emptySet())

    override fun save(locked: Set<String>) {
        prefs.edit().putStringSet(KEY, ParamKeys.idsOf(locked)).apply()
    }

    private companion object {
        const val PREFS = "musicviz-mod"
        const val KEY = "locked_params"
    }
}
