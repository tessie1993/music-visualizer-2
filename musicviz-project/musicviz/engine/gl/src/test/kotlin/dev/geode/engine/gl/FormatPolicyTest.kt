package dev.geode.engine.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.3's format commitments as behaviour: state defaults to RGBA32UI with
 * float-bit packing, linear accumulation gets R16F only after a
 * renderability-and-blend probe, and every miss lands on a *named* fallback
 * rather than a black frame.
 */
class FormatPolicyTest {
    @Test
    fun `a proven device gets the float formats`() {
        val plan = FormatPolicy.resolve(ProbeFixtures.report())
        assertEquals(ProbedFormat.RGBA32UI, plan.simulationState.format)
        assertEquals(TexelEncoding.FLOAT_BITS_IN_UINT, plan.simulationState.encoding)
        assertEquals(ProbedFormat.RG16F, plan.filterableField.format)
        assertTrue(plan.filterableField.filterable)
        assertEquals(ProbedFormat.R16F, plan.linearAccumulation.format)
        assertEquals(TexelEncoding.LINEAR, plan.linearAccumulation.encoding)
        assertEquals(ProbedFormat.R16F, plan.audioTexture.format)
        assertEquals(ProbedFormat.RGBA16F, plan.linearColorTarget.format)
    }

    @Test
    fun `a strict baseline device gets the packed and pre-scaled fallbacks`() {
        val plan = FormatPolicy.resolve(ProbeFixtures.baseline())
        assertEquals(ProbedFormat.RGBA32UI, plan.simulationState.format)
        // No half-float render targets: fields sampled with filtering fall
        // back to packed state and manual interpolation.
        assertEquals(ProbedFormat.RGBA32UI, plan.filterableField.format)
        assertFalse(plan.filterableField.filterable)
        assertEquals(ProbedFormat.RGBA8, plan.linearAccumulation.format)
        assertEquals(TexelEncoding.PRE_SCALED, plan.linearAccumulation.encoding)
        // Half-float *filtering* is core ES 3.0, and audio textures are
        // uploaded rather than rendered, so R16F survives on baseline.
        assertEquals(ProbedFormat.R16F, plan.audioTexture.format)
        assertEquals(ProbedFormat.RGBA8, plan.linearColorTarget.format)
    }

    @Test
    fun `accumulation needs the blend proof, not just attachability`() {
        val attachButNoBlend =
            ProbeFixtures.report(
                formats =
                    ProbedFormat.entries.associateWith { ProbeFixtures.PROVEN } +
                        (
                            ProbedFormat.R16F to
                                ProbeFixtures.PROVEN.copy(blendsAdditively = false)
                        ),
            )
        val plan = FormatPolicy.resolve(attachButNoBlend)
        assertEquals(ProbedFormat.RGBA8, plan.linearAccumulation.format)
        assertEquals(TexelEncoding.PRE_SCALED, plan.linearAccumulation.encoding)
    }

    @Test
    fun `accumulation is linear or pre-scaled on both branches, never log-packed`() {
        // §6.3: "never log-pack a field that receives additive deposits". The
        // encoding type has no log member, so the property holds by
        // construction; this pins the two branches all the same.
        for (report in listOf(ProbeFixtures.report(), ProbeFixtures.baseline())) {
            val encoding = FormatPolicy.resolve(report).linearAccumulation.encoding
            assertTrue(
                "additive deposits must stay linear, got $encoding",
                encoding == TexelEncoding.LINEAR || encoding == TexelEncoding.PRE_SCALED,
            )
        }
    }

    @Test
    fun `audio textures fall back when the filter probe fails`() {
        val filterBroken =
            ProbeFixtures.baseline().let { base ->
                base.copy(
                    formats =
                        base.formats +
                            (ProbedFormat.R16F to ProbeFixtures.UNATTACHABLE.copy(filtersLinearly = false)),
                )
            }
        val plan = FormatPolicy.resolve(filterBroken)
        assertEquals(ProbedFormat.RGBA8, plan.audioTexture.format)
        assertEquals(TexelEncoding.PRE_SCALED, plan.audioTexture.encoding)
    }

    @Test
    fun `a driver that fails even RGBA32UI still gets a named fallback`() {
        val broken =
            ProbeFixtures.baseline().let { base ->
                base.copy(
                    formats = base.formats + (ProbedFormat.RGBA32UI to ProbeFixtures.UNATTACHABLE),
                )
            }
        val plan = FormatPolicy.resolve(broken)
        assertEquals(ProbedFormat.RGBA8, plan.simulationState.format)
        assertEquals(TexelEncoding.PRE_SCALED, plan.simulationState.encoding)
    }

    @Test
    fun `a report with no probe outcomes claims nothing`() {
        val unprobed = ProbeFixtures.report(formats = emptyMap())
        val plan = FormatPolicy.resolve(unprobed)
        assertEquals(ProbedFormat.RGBA8, plan.simulationState.format)
        assertEquals(ProbedFormat.RGBA8, plan.linearAccumulation.format)
        assertEquals(ProbedFormat.RGBA8, plan.audioTexture.format)
    }

    @Test
    fun `every resolution records why, for the debug capability screen`() {
        for (report in listOf(ProbeFixtures.report(), ProbeFixtures.baseline())) {
            val plan = FormatPolicy.resolve(report)
            val all =
                listOf(
                    plan.simulationState,
                    plan.filterableField,
                    plan.linearAccumulation,
                    plan.audioTexture,
                    plan.linearColorTarget,
                )
            all.forEach { resolved ->
                assertTrue("${resolved.format} resolution has no reason", resolved.because.isNotBlank())
            }
        }
    }
}
