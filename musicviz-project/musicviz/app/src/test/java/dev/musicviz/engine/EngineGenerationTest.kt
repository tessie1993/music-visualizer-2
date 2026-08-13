package dev.musicviz.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 0.2. The generation switch is what makes the 2.0 strangler migration
 * reversible, so its default, its persistence and - above all - its behaviour
 * when V2 is unavailable are pinned before anything is built on top of it.
 *
 * The failure this guards against is the one the master plan calls out by name:
 * selecting an engine that cannot run and getting a silent black screen instead
 * of a visible, explained fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineGenerationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun store() = EngineGenerationStore(context)

    @Test
    fun `production default is legacy`() {
        assertEquals(EngineGeneration.LEGACY, store().load())
    }

    @Test
    fun `selection round-trips`() {
        val s = store()
        s.save(EngineGeneration.V2)
        assertEquals(EngineGeneration.V2, s.load())
        s.save(EngineGeneration.LEGACY)
        assertEquals(EngineGeneration.LEGACY, s.load())
    }

    @Test
    fun `an unreadable stored value falls back to legacy rather than throwing`() {
        context
            .getSharedPreferences("musicviz-prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("engine_generation", "V3_FROM_THE_FUTURE")
            .apply()
        assertEquals(EngineGeneration.LEGACY, store().load())
    }

    @Test
    fun `requesting an available generation activates it`() {
        val resolved =
            EngineGeneration.resolve(
                requested = EngineGeneration.V2,
                v2Available = true,
            )
        assertEquals(EngineSelection.Active(EngineGeneration.V2), resolved)
        assertEquals(EngineGeneration.V2, resolved.active)
    }

    @Test
    fun `requesting an unavailable V2 falls back visibly and keeps the reason`() {
        val resolved =
            EngineGeneration.resolve(
                requested = EngineGeneration.V2,
                v2Available = false,
            )
        // The point of the slice: the fallback is representable and carries a
        // reason, so the UI can say why instead of rendering black.
        assertTrue("expected a FellBack selection, got $resolved", resolved is EngineSelection.FellBack)
        val fell = resolved as EngineSelection.FellBack
        assertEquals(EngineGeneration.V2, fell.requested)
        assertEquals(EngineGeneration.LEGACY, fell.actual)
        assertEquals(EngineGeneration.LEGACY, resolved.active)
        assertTrue("reason must be non-blank", fell.reason.isNotBlank())
    }

    @Test
    fun `legacy is always available so it never falls back`() {
        val resolved =
            EngineGeneration.resolve(
                requested = EngineGeneration.LEGACY,
                v2Available = false,
            )
        assertEquals(EngineSelection.Active(EngineGeneration.LEGACY), resolved)
    }

    @Test
    fun `exactly one generation is active for any resolution`() {
        // Guards the plan's "do not run both full engines continuously" rule at
        // the type level: resolve() returns one active generation, never a set.
        for (requested in EngineGeneration.entries) {
            for (available in listOf(true, false)) {
                val active = EngineGeneration.resolve(requested, available).active
                assertTrue(active == EngineGeneration.LEGACY || active == EngineGeneration.V2)
            }
        }
    }
}
