package dev.geode.di

import android.app.Application
import dev.geode.ui.PlayerSession
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands out the shared [PlayerSession] and counts holders.
 *
 * The session is deliberately NOT a Hilt-managed singleton. Its teardown flushes pending store
 * writes, releases the playback engine's UI hold and stops the foreground service, all of which must
 * happen when the last ViewModel is cleared — not when the process dies. So Hilt injects this
 * provider, and each ViewModel acquires in its initialiser and releases in `onCleared`.
 */
@Singleton
class PlayerSessionProvider
    @Inject
    constructor(
        private val application: Application,
    ) {
        fun acquire(): PlayerSession = PlayerSession.acquire(application)

        fun release() = PlayerSession.release()
    }
