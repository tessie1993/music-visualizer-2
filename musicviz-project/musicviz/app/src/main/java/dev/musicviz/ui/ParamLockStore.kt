package dev.musicviz.ui

import android.content.Context

/**
 * Preferences-backed [ParamLockRepository].
 *
 * The file is named "musicviz-mod" for historical reasons — it was introduced
 * for the modulation settings and only ever held the lock set, while the LFO
 * and ADSR configs went to [LfoStore]. The name is kept because renaming it
 * would strand every existing user's locks.
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
    override fun load(): Set<String> = prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()

    override fun save(locked: Set<String>) {
        prefs.edit().putStringSet(KEY, locked).apply()
    }

    private companion object {
        const val PREFS = "musicviz-mod"
        const val KEY = "locked_params"
    }
}
