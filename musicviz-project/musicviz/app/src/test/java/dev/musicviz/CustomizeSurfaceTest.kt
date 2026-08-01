package dev.musicviz

import dev.musicviz.render.scene.CustomizeTab
import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The whole customization surface, checked in one place.
 *
 * A Customize parameter is only real when four things line up: a control
 * writes it, the randomizer rolls it (or says why not), the preset JSON
 * persists it, and some scene reads it. Every one of those has been broken
 * separately in this repository - sliders that moved nothing, lock chips keyed
 * to labels no control rendered, parameters missing from the preset document,
 * controls shown on styles that ignore them - and each was found by hand, one
 * at a time, against a `docs/PARAM_MATRIX.md` that eventually went stale and
 * was deleted.
 *
 * These are that document's gates, made executable, plus the document itself:
 * [the_param_matrix_document_is_current] REGENERATES `docs/PARAM_MATRIX.md`
 * from the sources whenever it drifts, so it cannot rot again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomizeSurfaceTest {
    @Test
    fun every_parameter_has_a_control() {
        // A parameter no control writes can only be reached by editing a
        // preset file by hand.
        val unreachable = ParamSurface.fields.filterNot { it in ParamSurface.controlledFields }
        assertEquals("parameters with no Customize control", emptyList<String>(), unreachable)
    }

    @Test
    fun every_parameter_is_rolled_or_declared_unrollable() {
        // Both directions: a parameter that is neither rolled nor declared is
        // an oversight, and a declaration for a parameter that IS rolled is a
        // stale comment about behaviour that changed.
        val unrolled = ParamSurface.fields.filterNot { it in ParamSurface.rolledBy }.toSet()
        assertEquals(
            "parameters the randomizer skips without saying why",
            emptyList<String>(),
            (unrolled - ParamRandomizer.NEVER_ROLLED.keys).sorted(),
        )
        assertEquals(
            "NEVER_ROLLED entries for parameters the randomizer does roll",
            emptyList<String>(),
            (ParamRandomizer.NEVER_ROLLED.keys - unrolled).sorted(),
        )
    }

    @Test
    fun every_parameter_is_persisted_in_a_preset() {
        val dropped = ParamSurface.fields.filterNot { it in ParamSurface.presetKeys }
        assertEquals("parameters missing from the preset document", emptyList<String>(), dropped)
    }

    @Test
    fun every_parameter_is_read_by_something() {
        // The dead-parameter gate: a field no scene, composite pass or export
        // ever references is a control that moves nothing on any style. The
        // exceptions are declared next to the fields, and checked both ways so
        // a declaration cannot outlive the reason for it.
        val unread = ParamSurface.fields.filterNot { it in ParamSurface.readersByFamily.values.flatten().toSet() }.toSet()
        assertEquals(
            "parameters nothing renders and nothing explains",
            emptyList<String>(),
            (unread - SceneParams.NOT_RENDERED.keys).sorted(),
        )
        assertEquals(
            "NOT_RENDERED entries for parameters a scene does read",
            emptyList<String>(),
            (SceneParams.NOT_RENDERED.keys - unread).sorted(),
        )
    }

    @Test
    fun every_rolled_parameter_is_reachable_from_its_own_tab() {
        // The randomizer's key belongs to the tab that renders the control,
        // and that control has to be the one that writes the parameter -
        // otherwise "Randomize <tab>" moves a slider from somewhere else.
        val misplaced =
            CustomizeTab.entries.flatMap { tab ->
                val controls = ParamSurface.controlsByTab.getValue(tab)
                ParamRandomizer
                    .keysFor(tab)
                    .flatMap { key -> ParamSurface.rolledBy.filterValues { it == key }.keys }
                    .filterNot { it in controls }
                    .map { "$it rolls with ${tab.title} but no control there writes it" }
            }
        assertEquals(emptyList<String>(), misplaced)
    }

    @Test
    fun the_param_matrix_document_is_current() {
        // Regenerates on drift rather than only complaining: the document is
        // derived, so the fix is never anything but "take the new one".
        val doc = File(ParamSurface.moduleRoot, "docs/PARAM_MATRIX.md")
        val generated = ParamMatrix.render()
        if (!doc.isFile || doc.readText() != generated) {
            doc.parentFile?.mkdirs()
            doc.writeText(generated)
            throw AssertionError(
                "docs/PARAM_MATRIX.md was out of date and has been regenerated from the " +
                    "sources - review the diff and commit it.",
            )
        }
    }
}
