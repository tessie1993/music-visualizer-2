package dev.geode

import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.VisualStyleCatalog
import dev.geode.ui.sceneDisplayLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Style tiles never show a raw persistence id: every catalogued substyle
 * resolves to its authored label, and everything else opens the identifier up
 * into words rather than leaking underscores or lowercase-first ids.
 */
class SceneDisplayLabelTest {
    @Test
    fun cataloguedSubstylesResolveToTheirAuthoredLabels() {
        assertEquals("Liquid Warp", sceneDisplayLabel("hyper_liquid_warp"))
        assertEquals("Chladni Sand", sceneDisplayLabel("chladni_sand"))
        (VisualStyleCatalog.hyperspaceIds + VisualStyleCatalog.cymaticsIds).forEach { id ->
            assertEquals(VisualStyleCatalog.label(id), sceneDisplayLabel(id))
        }
    }

    @Test
    fun uncataloguedIdsFallBackToReadableTitleCase() {
        assertEquals("Emergence", sceneDisplayLabel(SceneIds.EMERGENCE))
        assertEquals("Curlflow", sceneDisplayLabel(SceneIds.CURLFLOW))
        assertEquals("Some Future Style", sceneDisplayLabel("some_future_style"))
    }

    @Test
    fun noTileLabelEverLooksLikeAnIdentifier() {
        val everyKnownId =
            VisualStyleCatalog.hyperspaceIds +
                VisualStyleCatalog.cymaticsIds +
                listOf(SceneIds.EMERGENCE, SceneIds.FLUID, SceneIds.CURLFLOW, SceneIds.WATER, SceneIds.BEAM, SceneIds.MILKDROP)
        everyKnownId.forEach { id ->
            val label = sceneDisplayLabel(id)
            assertFalse(label, label.contains('_'))
            assertTrue(label, label.first().isUpperCase())
        }
    }
}
