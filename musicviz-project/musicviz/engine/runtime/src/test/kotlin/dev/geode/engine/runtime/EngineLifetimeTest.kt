package dev.geode.engine.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * MASTER_PLAN §4.3 gives six lifetimes and one sentence that costs the most to
 * get wrong: "All lifetimes have explicit start, reset/rebind, and close
 * behavior."
 *
 * The current engine has the failure this prevents, and V2-0-01 fixed one
 * instance of it: a player released while a live ViewModel still pointed at
 * it, because two owners disagreed about who was last. Writing that rule once,
 * with the transitions tested, is cheaper than six owners each inventing it.
 *
 * Closing is idempotent because teardown races are normal - a surface goes
 * away while an export is finishing - and a second close must be boring
 * rather than a crash. Starting after close is NOT idempotent: it is a use
 * after free, and returning quietly would hand the caller an object that
 * looks alive and owns nothing.
 */
class EngineLifetimeTest {
    private class Recording(
        id: LifetimeId,
        private val log: MutableList<String>,
        private val failOnClose: Boolean = false,
    ) : ManagedLifetime(id) {
        override fun onStart() {
            log += "start:${id.name}"
        }

        override fun onReset() {
            log += "reset:${id.name}"
        }

        override fun onClose() {
            log += "close:${id.name}"
            if (failOnClose) error("teardown failed")
        }
    }

    private val log = mutableListOf<String>()

    private fun lifetime(
        id: LifetimeId = LifetimeId.VISUAL_SESSION,
        failOnClose: Boolean = false,
    ) = Recording(id, log, failOnClose)

    @Test
    fun `a new lifetime owns nothing yet`() {
        assertEquals(LifetimePhase.IDLE, lifetime().phase)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `starting twice acquires once`() {
        val l = lifetime()
        l.start()
        l.start()
        assertEquals(listOf("start:VISUAL_SESSION"), log)
        assertEquals(LifetimePhase.RUNNING, l.phase)
    }

    @Test
    fun `closing twice releases once`() {
        val l = lifetime()
        l.start()
        l.close()
        l.close()
        assertEquals(listOf("start:VISUAL_SESSION", "close:VISUAL_SESSION"), log)
        assertEquals(LifetimePhase.CLOSED, l.phase)
    }

    @Test
    fun `closing something never started releases nothing`() {
        val l = lifetime()
        l.close()
        assertEquals("nothing was acquired, so there is nothing to release", emptyList<String>(), log)
        assertEquals(LifetimePhase.CLOSED, l.phase)
    }

    @Test
    fun `resetting is for a running lifetime only`() {
        val l = lifetime()
        l.reset()
        assertEquals("nothing to reset before start", emptyList<String>(), log)
        l.start()
        l.reset()
        l.reset()
        assertEquals(listOf("start:VISUAL_SESSION", "reset:VISUAL_SESSION", "reset:VISUAL_SESSION"), log)
    }

    @Test
    fun `starting after close is a bug, not a restart`() {
        val l = lifetime()
        l.start()
        l.close()
        assertThrows(IllegalStateException::class.java) { l.start() }
    }

    /**
     * A throwing acquire must land in the terminal phase. Left IDLE, the
     * owner could retry start() over a half-acquired carcass, and a later
     * close() would report success while releasing nothing. onClose stays
     * un-called: unwinding a partial acquire belongs to the thrower, the
     * same contract a failing constructor has.
     */
    @Test
    fun `a failed acquire is terminal, not retryable`() {
        val log = mutableListOf<String>()
        val l =
            object : ManagedLifetime(LifetimeId.OUTPUT) {
                override fun onStart(): Unit = throw IllegalStateException("driver refused the surface")

                override fun onClose() {
                    log += "close"
                }
            }
        assertThrows(IllegalStateException::class.java) { l.start() }
        assertEquals(LifetimePhase.CLOSED, l.phase)
        assertThrows(IllegalStateException::class.java) { l.start() }
        l.close()
        assertEquals("nothing was acquired, so nothing may be released", emptyList<String>(), log)
    }

    @Test
    fun `resetting after close is a bug`() {
        val l = lifetime()
        l.start()
        l.close()
        assertThrows(IllegalStateException::class.java) { l.reset() }
    }

    @Test
    fun `the composition root closes what it owns in reverse order`() {
        // Reverse, because a later lifetime may hold something an earlier one
        // owns - the GL context outlives the surfaces drawn on it, and the
        // playback session outlives the visual session reading its features.
        val root = EngineComposition()
        root.own(lifetime(LifetimeId.PLAYBACK_SESSION)).start()
        root.own(lifetime(LifetimeId.GL_CONTEXT)).start()
        root.own(lifetime(LifetimeId.OUTPUT)).start()
        log.clear()
        root.closeAll()
        assertEquals(listOf("close:OUTPUT", "close:GL_CONTEXT", "close:PLAYBACK_SESSION"), log)
    }

    @Test
    fun `one failing teardown does not strand the rest`() {
        val root = EngineComposition()
        root.own(lifetime(LifetimeId.PLAYBACK_SESSION)).start()
        root.own(lifetime(LifetimeId.GL_CONTEXT, failOnClose = true)).start()
        root.own(lifetime(LifetimeId.OUTPUT)).start()
        log.clear()
        val failures = root.closeAll()
        assertEquals(listOf("close:OUTPUT", "close:GL_CONTEXT", "close:PLAYBACK_SESSION"), log)
        assertEquals("the failure is reported, not swallowed", listOf(LifetimeId.GL_CONTEXT), failures.map { it.first })
    }

    @Test
    fun `closing the root twice is safe`() {
        val root = EngineComposition()
        root.own(lifetime()).start()
        root.closeAll()
        log.clear()
        assertEquals(emptyList<Pair<LifetimeId, Throwable>>(), root.closeAll())
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `owning after the root is closed is a bug`() {
        val root = EngineComposition()
        root.closeAll()
        assertThrows(IllegalStateException::class.java) { root.own(lifetime()) }
    }
}
