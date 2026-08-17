package dev.geode

import dev.geode.render.scene.SceneIds
import dev.geode.render.scene.VisualStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualStyleCatalogTest {
    @Test
    fun hyperspaceHasOneOriginalAndTenStableSubstyles() {
        val styles = VisualStyleCatalog.hyperspace

        assertEquals(11, styles.size)
        assertEquals(SceneIds.HYPERSPACE, styles.first().id)
        assertEquals((0..10).toList(), styles.map { it.shaderStyle })
        assertEquals(styles.size, styles.map { it.id }.distinct().size)
        assertEquals(styles.size, styles.map { it.label }.distinct().size)
        assertTrue(styles.drop(1).all { it.id.startsWith("hyper_") })
        styles.forEach { style -> assertEquals(style, VisualStyleCatalog.hyperspace(style.id)) }
    }

    @Test
    fun cymaticsHasOneOriginalAndTenStableSubstyles() {
        val styles = VisualStyleCatalog.cymatics

        assertEquals(11, styles.size)
        assertEquals(SceneIds.CYMATICS, styles.first().id)
        assertEquals((0..10).toList(), styles.map { it.shaderStyle })
        assertEquals(styles.size, styles.map { it.id }.distinct().size)
        assertEquals(styles.size, styles.map { it.label }.distinct().size)
        styles.forEach { style -> assertEquals(style, VisualStyleCatalog.cymatics(style.id)) }
    }

    @Test
    fun familyIdsNeverCollideAndUnknownIdsStayUnknown() {
        assertTrue(
            VisualStyleCatalog.hyperspaceIds
                .toSet()
                .intersect(VisualStyleCatalog.cymaticsIds.toSet())
                .isEmpty(),
        )
        assertEquals(null, VisualStyleCatalog.hyperspace("not-a-style"))
        assertEquals(null, VisualStyleCatalog.cymatics("not-a-style"))
        assertNotEquals("not-a-style", VisualStyleCatalog.label(SceneIds.HYPERSPACE))
        assertEquals("not-a-style", VisualStyleCatalog.label("not-a-style"))
    }
}
