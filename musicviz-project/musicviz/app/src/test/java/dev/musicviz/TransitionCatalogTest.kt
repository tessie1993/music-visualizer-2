package dev.musicviz

import androidx.test.core.app.ApplicationProvider
import dev.musicviz.render.TransitionCatalog
import dev.musicviz.render.TransitionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the vendored gl-transitions corpus and the splice that puts one on
 * screen.
 *
 * Three things here can go wrong silently, which is why they are pinned:
 *
 * 1. **Licence drift.** The corpus is re-vendorable from a newer upstream
 *    (`tools/vendor_gl_transitions.py`), and a future version could add a
 *    transition under a licence this app cannot ship. Every entry must carry
 *    one of the three licences audited for the shipped asset.
 * 2. **A transition that cannot be driven.** An entry with no `transition()`
 *    entry point, or one needing a sampler the engine has no image for, links
 *    but renders nothing - a black screen for the length of every switch.
 * 3. **A splice that does not splice.** If the marker or the `#version` line
 *    moves in `composite_frag.glsl`, the substitution quietly no-ops and every
 *    library transition falls back to a cut. The shader compile itself is
 *    checked outside the JVM (a real GLSL ES 3.00 compiler, over all 123);
 *    what this can check is that the text transformation is still correct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransitionCatalogTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** The licences audited for the shipped asset; anything else must fail. */
    private val allowedLicences = setOf("MIT", "BSD 3 Clause", "BSD 2 Clause")

    @Test
    fun the_corpus_loads_and_every_entry_is_shippable() {
        val library = TransitionCatalog.library(context)
        assertTrue("the corpus asset did not load", library.size > 100)
        for (def in library) {
            assertTrue("${def.name} has no licence", def.license.isNotBlank())
            assertTrue("${def.name} is licensed '${def.license}', which was never audited", def.license in allowedLicences)
            assertTrue("${def.name} has no transition() entry point", def.glsl.contains("transition"))
            // Excluded at vendoring time; an entry needing an image the engine
            // cannot bind renders black for the whole switch.
            assertFalse("${def.name} needs a sampler2D the engine cannot supply", def.glsl.contains("sampler2D"))
        }
    }

    @Test
    fun ids_are_unique_and_the_built_ins_come_first() {
        val ids = TransitionCatalog.allIds(context)
        assertEquals("duplicate transition ids", ids.size, ids.toSet().size)
        assertEquals(TransitionCatalog.BUILT_IN_IDS, ids.take(TransitionCatalog.BUILT_IN_IDS.size))
        for (style in TransitionStyle.entries) {
            val id = style.name.lowercase()
            assertEquals("built-in '$id' does not resolve back to its enum", style, TransitionCatalog.builtIn(id))
            // A built-in is implemented by the base shader, so it must NOT also
            // resolve to a corpus definition - that would splice a variant for
            // a style the base program already handles.
            assertEquals(null, TransitionCatalog.definition(context, id))
        }
    }

    @Test
    fun splicing_produces_a_shader_that_would_run_the_transition() {
        val base = context.resources.openRawResource(dev.musicviz.R.raw.composite_frag).bufferedReader().use { it.readText() }
        assertTrue("the splice marker is gone from composite_frag", base.contains("// __GL_TRANSITION_SOURCE__"))
        val def = TransitionCatalog.library(context).first { it.name == "crosswarp" }
        val spliced = TransitionCatalog.spliceInto(base, def)

        // #version must stay the first line: anything before it is a compile
        // error in every GLSL implementation.
        assertTrue("#version is no longer first", spliced.trimStart().startsWith("#version"))
        assertEquals("#define MV_TRANSITION 1", spliced.lines()[1].trim())
        assertFalse("the marker survived, so nothing was spliced", spliced.contains("// __GL_TRANSITION_SOURCE__"))
        assertTrue("the transition body is missing", spliced.contains(def.glsl.trim().lines().last().trim()))
        // The base shader must be unharmed: the variant is a superset.
        assertTrue(spliced.contains("vec3 blended()"))
        assertTrue(spliced.length > base.length)
    }

    @Test
    fun tunable_parameters_carry_their_upstream_defaults() {
        val library = TransitionCatalog.library(context)
        // The published corpus documents a default for every declared param;
        // uploading 0 for a missing one would render a degenerate transition
        // (a zero pixel size, a zero-radius circle) that looks like a bug.
        val withParams = library.filter { it.params.isNotEmpty() }
        assertTrue("no transition declared a parameter - the params table was dropped", withParams.size > 50)
        for (def in withParams) {
            for (p in def.params) {
                assertTrue("${def.name}.${p.name} has no value", p.values.isNotEmpty())
                assertTrue("${def.name}.${p.name} has an unhandled type '${p.type}'", p.type.isNotBlank())
            }
        }
        val zoom = library.first { it.name == "SimpleZoom" }
        val quickness = zoom.params.first { it.name == "zoom_quickness" }
        assertEquals(0.8f, quickness.values[0], 1e-6f)
    }
}
