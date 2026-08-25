package dev.synesthesia.core.common

/**
 * Local crash ring buffer (last N traces), D-SAFE-4 sanitized at write time:
 * paths hashed/relativized, track titles never included. Backup-excluded.
 */
interface CrashRing {
    fun record(error: Throwable)
    fun exportSanitized(): String
}
