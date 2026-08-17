package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the provenance registry is for, and why it is a build gate.
 *
 * The V2 research corpus is nearly forty repositories under eight licences,
 * four of which forbid reuse outright. Nothing in a compile can tell that a
 * shader was reimplemented from a paper and that one was pasted from an AGPL
 * project — so the only defence is a registry that is complete, pinned, and
 * checked. MASTER_PLAN §2.1 rule 9 states the contract: a source must be in the
 * registry before adapted code enters production.
 *
 * These tests ask whether the REGISTRY is sound: it matches the plan's ledger
 * in both directions so neither can drift alone, every licence claim is a hash
 * of a file read at a named commit rather than a badge, and a source nobody may
 * copy from cannot claim adopted files.
 *
 * Whether the SOURCE TREE obeys it is the other half, and it moved to the
 * `checkEngineProvenance` Gradle task in V2-1-04 - because the checks here
 * scanned a hardcoded `app/src/main` and would have gone on passing while
 * covering none of the engine modules.
 */
class EngineProvenanceRegistryTest {
    private val valid: ProvenanceCheck.Valid
        get() =
            when (val check = ProvenanceRegistry.current) {
                is ProvenanceCheck.Valid -> check
                is ProvenanceCheck.Invalid ->
                    throw AssertionError("provenance.json is invalid:\n" + check.problems.joinToString("\n"))
            }

    private fun problemsOf(json: String): List<String> =
        when (val check = ProvenanceRegistry.validate(json)) {
            is ProvenanceCheck.Valid -> emptyList()
            is ProvenanceCheck.Invalid -> check.problems
        }

    private fun registryText(): String = ProvenanceRegistry.file.readText()

    @Test
    fun `the registry in the repository is valid`() {
        assertTrue("no sources recorded", valid.sources.isNotEmpty())
    }

    @Test
    fun `the registry and the plan's ledger cover each other exactly`() {
        val claimed = valid.sources.map { it.planLedger }.toSet()
        assertEquals(
            "a §3.1 row with no registry entry is a source nobody pinned",
            emptySet<String>(),
            ProvenanceRegistry.ledgerRows - claimed,
        )
        assertEquals(
            "a registry entry with no §3.1 row is a source the plan never admitted",
            emptySet<String>(),
            claimed - ProvenanceRegistry.ledgerRows,
        )
    }

    @Test
    fun `every licence claim is evidence, and every unresolved one says so`() {
        val unresolved = valid.sources.filter { it.licenceState == "unresolved" }
        // Not an error: MASTER_PLAN §2.1 rule 10 wants incomplete steps visibly
        // open rather than quietly asserted. What is an error is an unresolved
        // source that a later slice could still treat as adoptable.
        assertEquals(
            "an unresolved licence may only sit under a no-code tier",
            emptyList<String>(),
            unresolved.filterNot { it.tier in ProvenanceRegistry.NO_CODE_TIERS }.map { it.id },
        )
    }

    @Test
    fun `no source that forbids copying claims adopted files`() {
        assertEquals(
            emptyList<String>(),
            valid.sources.filter { it.tier in ProvenanceRegistry.NO_CODE_TIERS && it.importedFiles.isNotEmpty() }.map { it.id },
        )
    }

    @Test
    fun `a malformed registry is rejected rather than half-read`() {
        assertTrue(problemsOf("{ not json").any { it.startsWith("not JSON") })
    }

    @Test
    fun `a missing required field is named, not skipped`() {
        val text = registryText().replaceFirst(""""planLedger": """", """"planLedgerWasRenamed": """")
        assertTrue("a renamed key must surface as a missing row", problemsOf(text).any { it.contains("no planLedger row") })
    }

    @Test
    fun `an unknown tier is rejected`() {
        val text = registryText().replaceFirst(""""tier": "ADAPT"""", """"tier": "VIBES"""")
        assertTrue(problemsOf(text).any { it.contains("unknown tier 'VIBES'") })
    }

    @Test
    fun `a forbidden source claiming adopted files is rejected`() {
        val text =
            registryText().replaceFirst(
                Regex(""""id": "lygia",""" + """([\s\S]*?)"importedFiles": \[\]"""),
                """"id": "lygia",$1"importedFiles": ["lygia/color/blend.glsl"]""",
            )
        assertTrue(
            "the EXCLUDE tier must make an adopted file impossible to record",
            problemsOf(text).any { it.contains("lygia: tier EXCLUDE forbids adopted files") },
        )
    }

    @Test
    fun `an unverifiable licence hash is rejected`() {
        val text = registryText().replaceFirst(Regex(""""sha256": "[0-9a-f]{64}""""), """"sha256": "probably-mit"""")
        assertTrue(problemsOf(text).any { it.contains("licence sha256 is not a hash") })
    }
}
