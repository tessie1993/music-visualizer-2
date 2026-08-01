package dev.musicviz

import dev.musicviz.render.scene.ParamKeys
import dev.musicviz.render.scene.ParamRandomizer
import dev.musicviz.render.scene.SceneParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.primaryConstructor

/**
 * The persistence contract for parameter locks.
 *
 * Locks used to be stored as the display label of the control, so rewording a
 * label silently orphaned every lock a user had set. They are now stored as
 * the ids in [ParamKeys]. These tests hold the properties that make that
 * substitution safe, and that make the stored form survive a label rename.
 */
class ParamKeysTest {
    private val sceneParamsFields: Set<String> =
        SceneParams::class
            .primaryConstructor!!
            .parameters
            .mapNotNull { it.name }
            .toSet()

    @Test
    fun everyRandomizerLockKeyHasAnEntry() {
        // A key the randomizer honours but the catalog does not know would be
        // written to disk as a raw label, i.e. the old fragile behaviour.
        val missing = ParamRandomizer.KEYS.filter { ParamKeys.idForLabel(it) == null }
        assertEquals("randomizer keys with no ParamKeys entry: $missing", emptyList<String>(), missing)
    }

    @Test
    fun catalogHasNoEntriesTheRandomizerDoesNotHonour() {
        val keys = ParamRandomizer.KEYS.toSet()
        val extra = ParamKeys.ALL.map { it.label }.filterNot { it in keys }
        assertEquals("catalog labels no randomizer key matches: $extra", emptyList<String>(), extra)
    }

    @Test
    fun idsAreUniqueAndAreRealSceneParamsFields() {
        val ids = ParamKeys.ALL.map { it.id }
        assertEquals("duplicate ParamKey ids", ids.size, ids.toSet().size)
        val unknown = ids.filterNot { it in sceneParamsFields }
        assertEquals("ids that are not SceneParams fields: $unknown", emptyList<String>(), unknown)
    }

    @Test
    fun labelsAreUnique() {
        val labels = ParamKeys.ALL.map { it.label }
        assertEquals("duplicate ParamKey labels", labels.size, labels.toSet().size)
    }

    @Test
    fun noIdCollidesWithAnyLabel() {
        // This is what lets a stored set be read without a version marker: a
        // legacy set of labels and a current set of ids cannot be confused.
        val ids = ParamKeys.ALL.map { it.id }.toSet()
        val labels = ParamKeys.ALL.map { it.label }.toSet()
        assertEquals("strings that are both an id and a label", emptySet<String>(), ids intersect labels)
    }

    @Test
    fun labelSetRoundTripsThroughTheStoredForm() {
        val locked = setOf("Speed", "Beat pulse", "Ripple glint", "Palette 2")
        assertEquals(locked, ParamKeys.labelsOf(ParamKeys.idsOf(locked)))
    }

    @Test
    fun everyKnownLabelSurvivesTheRoundTrip() {
        val all = ParamRandomizer.KEYS.toSet()
        assertEquals(all, ParamKeys.labelsOf(ParamKeys.idsOf(all)))
    }

    @Test
    fun aLegacyStoredLabelStillReadsAsThatLock() {
        // Sets written before ids existed hold labels; reading one must not
        // drop the lock just because it is not an id.
        assertEquals(setOf("Speed"), ParamKeys.labelsOf(setOf("Speed")))
    }

    @Test
    fun unknownEntriesPassThroughInsteadOfBeingDropped() {
        // A lock from a build with a control this one lacks is still the
        // user's data; discarding it on the next save would be a one-way loss.
        val stored = setOf("speed", "someFutureControl")
        assertTrue("unknown id must survive a read", "someFutureControl" in ParamKeys.labelsOf(stored))
        assertTrue(
            "unknown entry must survive a write",
            "someFutureControl" in ParamKeys.idsOf(setOf("Speed", "someFutureControl")),
        )
    }

    @Test
    fun storedFormIsIdsNotLabels() {
        // The point of the change: what lands on disk carries no wording.
        val stored = ParamKeys.idsOf(setOf("Beat pulse", "Dive speed"))
        assertEquals(setOf("pulse", "endlessZoomSpeed"), stored)
    }
}
