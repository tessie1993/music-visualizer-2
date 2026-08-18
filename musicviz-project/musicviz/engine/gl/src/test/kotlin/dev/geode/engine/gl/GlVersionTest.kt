package dev.geode.engine.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GL_VERSION` on ES is prose with a version buried in it, and every driver
 * family writes different prose. These strings are the shapes the prober will
 * actually meet: Adreno, Mali, the emulator's desktop-GL passthrough and
 * ANGLE.
 */
class GlVersionTest {
    @Test
    fun `parses an Adreno version string`() {
        assertEquals(
            GlVersion(3, 2),
            GlVersion.parse("OpenGL ES 3.2 V@0502.0 (GIT@09fef2b, Ie1c1d1a708) (Date:11/10/23)"),
        )
    }

    @Test
    fun `parses a Mali version string`() {
        assertEquals(
            GlVersion(3, 2),
            GlVersion.parse("OpenGL ES 3.2 v1.r32p1-00pxl1.a3ba8c1d05b3e0d466a05fd2eaeaaba7"),
        )
    }

    @Test
    fun `parses the emulator's passthrough string`() {
        assertEquals(
            GlVersion(3, 1),
            GlVersion.parse("OpenGL ES 3.1 (4.5.0 NVIDIA 535.183.01)"),
        )
    }

    @Test
    fun `parses an ANGLE string`() {
        assertEquals(
            GlVersion(3, 0),
            GlVersion.parse("OpenGL ES 3.0.0 (ANGLE 2.1.0 git hash: abcdef)"),
        )
    }

    @Test
    fun `a string with no ES version in it parses to nothing`() {
        assertNull(GlVersion.parse(""))
        assertNull(GlVersion.parse("3.1"))
        assertNull(GlVersion.parse("Vulkan 1.3"))
    }

    @Test
    fun `versions order by major then minor`() {
        assertTrue(GlVersion(3, 1) > GlVersion(3, 0))
        assertTrue(GlVersion(3, 2) > GlVersion(3, 1))
        assertTrue(GlVersion(3, 0) >= GlVersion(3, 0))
    }
}
