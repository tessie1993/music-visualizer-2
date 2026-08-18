package dev.geode.engine.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cache stores probe *facts* so startup skips the probe battery, and its
 * only failure mode is "re-probe": any schema change, any driver identity
 * change, any corruption decodes to null rather than to a guess.
 */
class CapabilityCacheTest {
    private val report = ProbeFixtures.report()

    private fun decodeAgainst(
        text: String,
        identity: GlProbeReport = report,
    ) = CapabilityCache.decode(text, identity.vendor, identity.renderer, identity.versionString)

    @Test
    fun `a report round-trips exactly`() {
        assertEquals(report, decodeAgainst(CapabilityCache.encode(report)))
    }

    @Test
    fun `the baseline report round-trips too`() {
        val baseline = ProbeFixtures.baseline()
        assertEquals(baseline, decodeAgainst(CapabilityCache.encode(baseline), identity = baseline))
    }

    @Test
    fun `an empty extension set survives the round trip`() {
        val bare = ProbeFixtures.report(extensions = emptySet())
        assertEquals(bare, decodeAgainst(CapabilityCache.encode(bare), identity = bare))
    }

    @Test
    fun `a schema bump invalidates the cache`() {
        val old = CapabilityCache.encode(report).replaceFirst("v${CapabilityCache.SCHEMA_VERSION}", "v0")
        assertNull(decodeAgainst(old))
    }

    @Test
    fun `a driver update invalidates the cache`() {
        // Same GPU, new driver build: the version string is the only place the
        // driver revision shows, so it is part of the identity.
        val updated = report.copy(versionString = "OpenGL ES 3.2 V@0615.0 (GIT@later)")
        assertNull(decodeAgainst(CapabilityCache.encode(report), identity = updated))
    }

    @Test
    fun `a different GPU invalidates the cache`() {
        val otherGpu = report.copy(renderer = "Adreno (TM) 750")
        assertNull(decodeAgainst(CapabilityCache.encode(report), identity = otherGpu))
    }

    @Test
    fun `truncation invalidates the cache`() {
        val text = CapabilityCache.encode(report)
        assertNull(decodeAgainst(text.substring(0, text.length / 2)))
    }

    @Test
    fun `a tampered field invalidates the cache`() {
        val text = CapabilityCache.encode(report).replace("maxTextureSize=16384", "maxTextureSize=big")
        assertNull(decodeAgainst(text))
    }

    @Test
    fun `garbage invalidates the cache`() {
        assertNull(decodeAgainst(""))
        assertNull(decodeAgainst("not a cache"))
    }
}
