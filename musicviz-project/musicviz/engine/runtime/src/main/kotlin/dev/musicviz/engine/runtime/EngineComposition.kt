package dev.musicviz.engine.runtime

/**
 * The hand-written composition root of MASTER_PLAN §4.4: it holds the
 * lifetimes and it is the only thing that knows the order to tear them down
 * in.
 *
 * Deliberately not a DI container. §4.4 sets the threshold for reconsidering
 * that - roughly forty independently constructed production objects, or a
 * third lifetime needing scoped composition - and until then a framework would
 * hide the one thing that matters here, which is who closes what and when.
 */
class EngineComposition {
    private val owned = mutableListOf<EngineLifetime>()
    private var closed = false

    /** Takes ownership of [lifetime] and returns it, so calls can be chained. */
    fun <T : EngineLifetime> own(lifetime: T): T {
        check(!closed) { "the composition root is closed; ${lifetime.id} would never be released" }
        owned += lifetime
        return lifetime
    }

    /**
     * Closes everything in reverse order of ownership and reports what failed.
     *
     * Reverse, because a later lifetime may hold something an earlier one owns:
     * the GL context outlives the surfaces drawn on it, and the playback
     * session outlives the visual session reading its features.
     *
     * A failure does not stop the sweep. One driver throwing on teardown must
     * not strand the encoder and the audio session behind it - so the failures
     * are collected and returned rather than thrown, and the caller decides
     * whether a half-clean teardown is worth reporting.
     */
    fun closeAll(): List<Pair<LifetimeId, Throwable>> {
        closed = true
        val failures = mutableListOf<Pair<LifetimeId, Throwable>>()
        owned.asReversed().forEach { lifetime ->
            runCatching { lifetime.close() }
                .onFailure { failures += lifetime.id to it }
        }
        owned.clear()
        return failures
    }
}
