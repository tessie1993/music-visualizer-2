package dev.musicviz.engine.runtime

/** The six lifetimes of MASTER_PLAN §4.3, by what they own. */
enum class LifetimeId {
    /** Source registry, shader cache, capability database, recipe catalogue. */
    PROCESS,

    /** PCM ring, analysis graph, sample clock, normalization state, feature ring. */
    PLAYBACK_SESSION,

    /** Scene instances, simulation and modulation state, transitions, seed. */
    VISUAL_SESSION,

    /** Programs, FBOs, textures, buffers, timer queries, pools. */
    GL_CONTEXT,

    /** EGL surface, viewport, output policy, presentation schedule. */
    OUTPUT,

    /** Deterministic frame schedule, fixed quality, encoder bridge. */
    EXPORT,
}

/** Where a lifetime is. `CLOSED` is terminal. */
enum class LifetimePhase { IDLE, RUNNING, CLOSED }

/**
 * Something that acquires resources, can be returned to a known state, and
 * releases them exactly once.
 *
 * Written once rather than six times because the failure it prevents already
 * happened here: V2-0-01 fixed a player released while a live consumer still
 * pointed at it, two owners having disagreed about who was last.
 */
interface EngineLifetime {
    val id: LifetimeId
    val phase: LifetimePhase

    fun start()

    fun reset()

    fun close()
}

/**
 * The transition rules, so an owner writes only what it acquires and releases.
 *
 * `close` is idempotent because teardown races are ordinary - a surface goes
 * away while an export is finishing - and the second close should be boring.
 * `start` after `close` is not: that is a use after free, and returning
 * quietly would hand back an object that looks alive and owns nothing.
 */
abstract class ManagedLifetime(
    final override val id: LifetimeId,
) : EngineLifetime {
    final override var phase: LifetimePhase = LifetimePhase.IDLE
        private set

    final override fun start() {
        check(phase != LifetimePhase.CLOSED) { "$id was closed and cannot be started again" }
        if (phase == LifetimePhase.RUNNING) return
        onStart()
        phase = LifetimePhase.RUNNING
    }

    final override fun reset() {
        check(phase != LifetimePhase.CLOSED) { "$id was closed and cannot be reset" }
        if (phase == LifetimePhase.RUNNING) onReset()
    }

    final override fun close() {
        if (phase == LifetimePhase.CLOSED) return
        val acquired = phase == LifetimePhase.RUNNING
        phase = LifetimePhase.CLOSED
        if (acquired) onClose()
    }

    /** Acquire. Called once, on the first [start]. */
    protected open fun onStart() = Unit

    /** Return to a known state without releasing. Called only while running. */
    protected open fun onReset() = Unit

    /** Release. Called once, and only if [onStart] ran. */
    protected open fun onClose() = Unit
}
