package dev.musicviz

import dev.musicviz.render.scene.CymaticsMath
import dev.musicviz.render.scene.VisualStyleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every CYMATICS substyle has to be a genuinely different apparatus, not the
 * same field wearing a different sheen - the family shipped with four
 * near-identical pairs (caustic_sheet ~ original, bessel_drum ~ rosensweig,
 * levitator ~ standing_chamber, chladni_sand ~ kundt_tube).
 *
 * Two layers of pin:
 *  - CATALOG distinctness: the per-style recipe (the extended
 *    [VisualStyleCatalog.CymaticsStyle] fields) must differ pairwise in
 *    several knobs, and each style must own a unique palette offset - the
 *    constants live in the one data class, never in parallel lists.
 *  - SHADER topology: each substyle's branch must exist, and the signature
 *    recompositions that give the four formerly-twinned styles their own
 *    figure (curvature caustics, room-mode products, the hex spike lattice,
 *    the droplet bank, the bead lattice) must still be there. Checked at
 *    source level, the way [RecoveredShaderStylesTest] pins shader content.
 */
class CymaticsStyleIdentityTest {
    private val shader: String by lazy { repoFile("src/main/res/raw/cymatics_field_frag.glsl") }

    /** The tunable identity of a style - everything but id/label/branch. */
    private fun knobs(s: VisualStyleCatalog.CymaticsStyle): List<Any?> =
        listOf(
            s.geometryOverride,
            s.scale,
            s.fill,
            s.line,
            s.glow,
            s.iridescence,
            s.caustic,
            s.flow,
            s.swirl,
            s.hueOffset,
            s.hueSpan,
            s.modeCap,
        )

    @Test
    fun everyPairOfStylesDiffersInSeveralKnobs() {
        val styles = VisualStyleCatalog.cymatics
        assertEquals(11, styles.size)
        for (i in styles.indices) {
            for (j in i + 1 until styles.size) {
                val a = knobs(styles[i])
                val b = knobs(styles[j])
                val differing = a.indices.count { a[it] != b[it] }
                assertTrue(
                    "${styles[i].id} and ${styles[j].id} differ in only $differing catalog knobs - " +
                        "two substyles this close are the same look with two names",
                    differing >= 3,
                )
            }
        }
    }

    @Test
    fun everyStyleOwnsItsPointOnThePalette() {
        val styles = VisualStyleCatalog.cymatics
        assertEquals(
            "hue offsets collide - the colliding styles paint from the same ramp",
            styles.size,
            styles.map { it.hueOffset }.distinct().size,
        )
        // The offset is a NUDGE around the user's chosen palette, not a
        // replacement for it: uBaseHue/uHueSpan must keep mattering.
        assertTrue(styles.all { kotlin.math.abs(it.hueOffset) < 0.5f })
        assertTrue(styles.all { it.hueSpan in 0.2f..2f })
    }

    @Test
    fun modeCapsAreWithinTheShaderArrayAndUsedWhereTheyMatter() {
        val styles = VisualStyleCatalog.cymatics
        assertTrue(styles.all { it.modeCap in 1..CymaticsMath.MAX_RENDERED_MODES })
        // The drum's identity IS the low cap: one or two clean Bessel
        // figures, not the original's eight-mode interference field.
        val drum = requireNotNull(VisualStyleCatalog.cymatics("bessel_drum"))
        assertTrue("Drumhead lost its clean-mode cap", drum.modeCap <= 2)
        // The chamber's cap matches the shader's room-mode loop.
        val chamber = requireNotNull(VisualStyleCatalog.cymatics("standing_chamber"))
        assertEquals(CymaticsMath.ROOM_MODES, chamber.modeCap)
    }

    @Test
    fun everySubstyleBranchExistsInTheShader() {
        assertTrue(shader.contains("uniform int uStyle;"))
        for (style in 1..10) {
            assertTrue("cymatics shader lost its uStyle == $style branch", shader.contains("uStyle == $style"))
        }
    }

    @Test
    fun theFormerTwinsNowHaveTheirOwnTopology() {
        // caustic_sheet vs original: real caustics need the curvature probe
        // and the convergence web, not another tinted height ramp.
        assertTrue("Caustic Sheet lost its curvature probe", shader.contains("caustLap"))
        assertTrue("Caustic Sheet lost its convergence web", shader.contains("abs(1.0 + 2.6 * bend)"))
        // bessel_drum vs rosensweig: the ferrofluid is a hex spike lattice on
        // smoothed h^2; the drum is a rim-clamped membrane.
        assertTrue("Rosensweig lost its hex spike lattice", shader.contains("hexLattice(p * 5.2)"))
        assertTrue("Drumhead lost its rim clamp", shader.contains("smoothstep(1.04, 0.88, length(uv))"))
        // levitator vs standing_chamber: beads in a pressure column vs
        // product-cosine room cells.
        assertTrue("Levitator lost its bead lattice", shader.contains("length(fract(bp) - centre)"))
        assertTrue(
            "Standing Chamber lost its product room modes",
            shader.contains("cos(M.x * PI * (p.x + uDriftShift)) * cos(M.y * PI * p.y)"),
        )
        // chladni_sand vs kundt_tube: gathering grains vs axial dust bands in
        // a bore vignette.
        assertTrue("Chladni Sand lost its gathering grains", shader.contains("float gather = exp(-az"))
        assertTrue("Kundt Tube lost its dust piles", shader.contains("float piles = exp(-az * az"))
        // faraday: the beat-spawned droplet bank and the treble capillary
        // lattice are what set it apart from the original's remap alone.
        assertTrue("Faraday lost its droplet bank", shader.contains("uDrops[i]"))
        assertTrue("Faraday lost its capillary lattice", shader.contains("sin(p.x * 21.0 + uTravelPhase * 3.0)"))
    }

    @Test
    fun theSharedHueRampStillDrivesEveryStyle() {
        // Family rule: every substyle paints from uBaseHue + uHueSpan * h.
        // Offsets rotate it and materials tint over it, but the ramp itself
        // must stay shared or the Palette controls die on some substyles.
        assertTrue(shader.contains("float hue = uBaseHue + uHueSpan * h;"))
    }

    private fun repoFile(relative: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
