package dev.geode.engine.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.3's rule made executable: "never infer support from GLES version alone".
 * Every capability below needs the version *and* a limit or behavioural probe
 * that backs it up; each test removes one leg and expects the capability gone.
 */
class GlCapabilitiesTest {
    @Test
    fun `a proven enhanced device gets the enhanced path`() {
        val caps = GlCapabilities.derive(ProbeFixtures.report())
        assertEquals(GlVersion(3, 2), caps.version)
        assertTrue(caps.computeShaders)
        assertTrue(caps.storageBuffersInCompute)
        assertTrue(caps.storageBuffersInFragment)
        assertTrue(caps.imageLoadStore)
        assertTrue(caps.vertexTextureFetch)
        assertEquals(TimerQuerySupport.TRUSTED, caps.timerQueries)
        assertTrue(caps.programBinaries)
    }

    @Test
    fun `a strict baseline device gets the baseline path`() {
        val caps = GlCapabilities.derive(ProbeFixtures.baseline())
        assertEquals(GlVersion(3, 0), caps.version)
        assertFalse(caps.computeShaders)
        assertFalse(caps.storageBuffersInCompute)
        assertFalse(caps.storageBuffersInFragment)
        assertFalse(caps.imageLoadStore)
        assertEquals(TimerQuerySupport.ABSENT, caps.timerQueries)
        assertFalse(caps.programBinaries)
    }

    @Test
    fun `a claimed 3_1 with sub-minimum compute limits enables no compute`() {
        // The ES 3.1 spec floor is 128 invocations; a driver reporting less is
        // not a slower 3.1, it is a context whose version string cannot be
        // trusted for this capability.
        val caps =
            GlCapabilities.derive(
                ProbeFixtures.report(
                    versionString = "OpenGL ES 3.1 v1.r18p0",
                    maxComputeWorkGroupInvocations = 64,
                ),
            )
        assertFalse(caps.computeShaders)
    }

    @Test
    fun `a 3_0 context reporting compute limits still has no compute`() {
        val caps =
            GlCapabilities.derive(
                ProbeFixtures.baseline().copy(maxComputeWorkGroupInvocations = 1024),
            )
        assertFalse(caps.computeShaders)
    }

    @Test
    fun `compute storage buffers need the spec floor of four blocks`() {
        val caps = GlCapabilities.derive(ProbeFixtures.report(maxComputeStorageBlocks = 3))
        assertFalse(caps.storageBuffersInCompute)
    }

    @Test
    fun `fragment storage buffers are genuinely optional even on 3_1`() {
        // GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS has a spec minimum of zero.
        val caps = GlCapabilities.derive(ProbeFixtures.report(maxFragmentStorageBlocks = 0))
        assertFalse(caps.storageBuffersInFragment)
        assertTrue(caps.storageBuffersInCompute)
    }

    @Test
    fun `vertex texture fetch needs the behavioural proof, not the unit count`() {
        val advertised =
            ProbeFixtures.report(
                maxVertexTextureImageUnits = 16,
                vertexTextureFetchProven = false,
            )
        assertFalse(GlCapabilities.derive(advertised).vertexTextureFetch)
    }

    @Test
    fun `vertex texture fetch needs units even when the probe claims proof`() {
        val contradictory =
            ProbeFixtures.report(
                maxVertexTextureImageUnits = 0,
                vertexTextureFetchProven = true,
            )
        assertFalse(GlCapabilities.derive(contradictory).vertexTextureFetch)
    }

    @Test
    fun `timer queries climb a trust ladder`() {
        val absent = ProbeFixtures.report(timerQueryPresent = false, timerQueryProven = false)
        val present = ProbeFixtures.report(timerQueryPresent = true, timerQueryProven = false)
        val proven = ProbeFixtures.report(timerQueryPresent = true, timerQueryProven = true)
        assertEquals(TimerQuerySupport.ABSENT, GlCapabilities.derive(absent).timerQueries)
        assertEquals(TimerQuerySupport.UNTRUSTED, GlCapabilities.derive(present).timerQueries)
        assertEquals(TimerQuerySupport.TRUSTED, GlCapabilities.derive(proven).timerQueries)
    }

    @Test
    fun `an unparseable version string enables nothing enhanced`() {
        val caps = GlCapabilities.derive(ProbeFixtures.report(versionString = "garbage"))
        assertEquals(null, caps.version)
        assertFalse(caps.computeShaders)
        assertFalse(caps.storageBuffersInCompute)
        assertFalse(caps.storageBuffersInFragment)
        assertFalse(caps.imageLoadStore)
    }
}
