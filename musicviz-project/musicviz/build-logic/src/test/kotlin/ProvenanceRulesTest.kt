import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every rule proved by a file that trips it.
 *
 * A provenance gate on a tree with no adapted code in it passes trivially, and
 * would go on passing if it checked nothing at all. These fixtures are the
 * only evidence that it does not.
 */
class ProvenanceRulesTest {
    private val swissgl =
        ProvenanceSourceRecord(
            id = "swissgl",
            url = "https://github.com/google/swissgl",
            tier = "ADAPT",
            licence = "Apache-2.0",
            commit = "489dfcf437702d6e2446f3e36beadecb34cc81ca",
            importedFiles = emptyList(),
        )
    private val velo =
        ProvenanceSourceRecord(
            id = "velo-visualiser",
            url = "https://github.com/rorygallagher2024/velo-visualiser",
            tier = "STUDY",
            licence = "GPL-3.0",
            commit = "bebf7233f97f19b0158391ff06a8dfa54caf896c",
            importedFiles = emptyList(),
        )
    private val sources = listOf(swissgl, velo)

    private fun check(vararg files: ScannedFile) = ProvenanceRules.check(files.toList(), sources, notices = "")

    private fun file(body: String) = ScannedFile("Kernel.kt", body.trimIndent())

    @Test
    fun `a correctly attributed file passes`() {
        val out =
            check(
                file(
                    """
                    // SPDX-License-Identifier: Apache-2.0
                    // Origin: https://github.com/google/swissgl@489dfcf
                    fun step() = Unit
                    """,
                ),
            )
        assertEquals(emptyList<ProvenanceViolation>(), out)
    }

    @Test
    fun `an unattributed file with no citation passes`() {
        assertEquals(emptyList<ProvenanceViolation>(), check(file("fun ours() = Unit")))
    }

    @Test
    fun `an origin without an SPDX line is rejected`() {
        val out = check(file("// Origin: https://github.com/google/swissgl@489dfcf"))
        assertTrue(out.toString(), out.any { it is ProvenanceViolation.OriginWithoutSpdx })
    }

    @Test
    fun `an origin nobody registered is rejected`() {
        val out =
            check(
                file(
                    """
                    // SPDX-License-Identifier: MIT
                    // Origin: https://github.com/somebody/unregistered@abc1234
                    """,
                ),
            )
        assertTrue(out.toString(), out.any { it is ProvenanceViolation.UnknownOrigin })
    }

    @Test
    fun `citing a commit the registry does not pin is rejected`() {
        // The pin is what the licence was read at. Adapting from a different
        // commit means the licence check was done against other text.
        val out =
            check(
                file(
                    """
                    // SPDX-License-Identifier: Apache-2.0
                    // Origin: https://github.com/google/swissgl@0000000
                    """,
                ),
            )
        assertTrue(out.toString(), out.any { it is ProvenanceViolation.OriginCommitMismatch })
    }

    @Test
    fun `adapting from a study-tier source is rejected`() {
        val out =
            check(
                file(
                    """
                    // SPDX-License-Identifier: GPL-3.0
                    // Origin: https://github.com/rorygallagher2024/velo-visualiser@bebf723
                    """,
                ),
            )
        assertTrue(out.toString(), out.any { it is ProvenanceViolation.ForbiddenTier })
    }

    @Test
    fun `declaring a licence the registry disagrees with is rejected`() {
        val out =
            check(
                file(
                    """
                    // SPDX-License-Identifier: MIT
                    // Origin: https://github.com/google/swissgl@489dfcf
                    """,
                ),
            )
        assertTrue(out.toString(), out.any { it is ProvenanceViolation.LicenceMismatch })
    }

    @Test
    fun `naming a forbidden source without a marker is still a claim of origin`() {
        // The marker is a convention; the GPL is not. Mentioning the repository
        // in shipped source is what a reviewer would read as where it came
        // from, so it is treated that way.
        val out = check(file("// ported the tunnel from github.com/rorygallagher2024/velo-visualiser"))
        assertTrue(out.toString(), out.any { it is ProvenanceViolation.ForbiddenSourceMentioned })
    }

    @Test
    fun `url spelling does not decide the outcome`() {
        val out = check(file("// see http://github.com/rorygallagher2024/velo-visualiser.git for the maths"))
        assertTrue("scheme and .git must not be a way around the rule", out.any { it is ProvenanceViolation.ForbiddenSourceMentioned })
    }

    @Test
    fun `adopted files must appear in the shipped notices`() {
        val adopted = swissgl.copy(importedFiles = listOf("engine/scenes/Kernel.kt"))
        val out = ProvenanceRules.check(emptyList(), listOf(adopted), notices = "")
        assertTrue(out.toString(), out.any { it is ProvenanceViolation.MissingNotice })
        val covered = ProvenanceRules.check(emptyList(), listOf(adopted), notices = "https://github.com/google/swissgl")
        assertEquals(emptyList<ProvenanceViolation>(), covered)
    }
}

/** Regression fixtures for holes found by re-reading the rules, not by a run. */
class ProvenanceRulesEdgeTest {
    private val sources =
        listOf(
            ProvenanceSourceRecord("swissgl", "https://github.com/google/swissgl", "ADAPT", "Apache-2.0", "489dfcf437702d6e2446f3e36beadecb34cc81ca", emptyList()),
            ProvenanceSourceRecord("velo-visualiser", "https://github.com/rorygallagher2024/velo-visualiser", "STUDY", "GPL-3.0", "bebf7233f97f19b0158391ff06a8dfa54caf896c", emptyList()),
        )

    @Test
    fun `a legitimate citation does not excuse a forbidden one beside it`() {
        // The hole this closes: the mention scan used to run only for files
        // with NO citation, so one correct attribution hid every other source
        // named in the same file.
        val out =
            ProvenanceRules.check(
                listOf(
                    ScannedFile(
                        "Kernel.kt",
                        """
                        // SPDX-License-Identifier: Apache-2.0
                        // Origin: https://github.com/google/swissgl@489dfcf
                        // and the tunnel maths from github.com/rorygallagher2024/velo-visualiser
                        """.trimIndent(),
                    ),
                ),
                sources,
                notices = "",
            )
        assertEquals(
            listOf("velo-visualiser"),
            out.filterIsInstance<ProvenanceViolation.ForbiddenSourceMentioned>().map { it.id },
        )
    }

    @Test
    fun `a source reached through a marker is reported once`() {
        val out =
            ProvenanceRules.check(
                listOf(ScannedFile("Bad.kt", "// SPDX-License-Identifier: GPL-3.0\n// Origin: https://github.com/rorygallagher2024/velo-visualiser@bebf723")),
                sources,
                notices = "",
            )
        assertTrue(out.none { it is ProvenanceViolation.ForbiddenSourceMentioned })
        assertTrue(out.any { it is ProvenanceViolation.ForbiddenTier })
    }

    @Test
    fun `a malformed registry yields no records rather than throwing`() {
        assertEquals(emptyList<ProvenanceSourceRecord>(), readProvenanceRegistry("[]"))
    }
}
