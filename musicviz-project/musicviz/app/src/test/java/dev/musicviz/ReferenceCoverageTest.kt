package dev.musicviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The coverage ledger has one job: make "did we already look at that?" a
 * question with an answer.
 *
 * MASTER_PLAN §3.2 asks for every researched effect to be enumerated, not only
 * the ones chosen for the first release, because the failure it prevents is
 * silent — a later session re-researches a source, or ships a fourth tunnel
 * because four repositories each had one. So the gate below is the strict one:
 * every name §8.1 lists must appear in the ledger, checked against the plan
 * text itself rather than against a copy someone kept in step by hand.
 */
class ReferenceCoverageTest {
    private val entries = ReferenceCoverage.entries

    /** The §8.1 lists, flattened to the individual names they enumerate. */
    private val catalogueNames: List<String> by lazy {
        val section =
            File(ParamSurface.moduleRoot, "docs/visualizer-v2/MASTER_PLAN.md")
                .readText()
                .substringAfter("### 8.1")
                .substringBefore("### 8.2")
        Regex("""\*\*(.+?):\*\*([\s\S]*?)(?=\n\n)""")
            .findAll(section)
            .flatMap { match ->
                match.groupValues[2]
                    .replace("\n", " ")
                    .substringBefore("These are a feature checklist")
                    .split(",", " and ", ";", " plus ")
                    .map { it.trim().trimEnd('.').trim() }
                    .filter { it.isNotEmpty() }
            }.toList()
    }

    @Test
    fun `every concept the plan catalogues has a ledger row`() {
        val recorded = entries.map { it.upstreamName.lowercase() }.toSet()
        // Names are matched loosely on purpose: §8.1 writes "spectrum
        // bars/orbit/terrain/wave" as one phrase and "post effects" as a
        // trailing qualifier, so a row is a match when the plan's words are
        // contained in it or the other way round.
        val uncovered =
            catalogueNames.filterNot { name ->
                val n = name.lowercase()
                recorded.any { it == n || it.contains(n) || n.contains(it) } ||
                    n.split("/").all { part -> recorded.any { it.contains(part.trim()) } }
            }
        assertEquals("§8.1 names with no ledger row", emptyList<String>(), uncovered)
    }

    @Test
    fun `the ledger is complete enough to act on`() {
        assertTrue("the ledger is empty", entries.size > 100)
        val badFamily = entries.filter { it.family != null && it.family !in ReferenceCoverage.families }
        assertEquals(emptyList<String>(), badFamily.map { "${it.upstreamName}: ${it.family}" })
        val badDisposition = entries.filterNot { it.disposition in ReferenceCoverage.dispositions }
        assertEquals(emptyList<String>(), badDisposition.map { "${it.upstreamName}: ${it.disposition}" })
        val noRationale = entries.filter { it.rationale.length < 20 }
        assertEquals("a disposition with no reasoning is a guess", emptyList<String>(), noRationale.map { it.upstreamName })
    }

    @Test
    fun `an unassigned row is deferred, never quietly scheduled`() {
        assertEquals(
            "a row with no family cannot claim a disposition that implies one",
            emptyList<String>(),
            entries.filter { it.family == null && it.disposition != "DEFER" }.map { it.upstreamName },
        )
    }

    @Test
    fun `every row is traceable to a registered source`() {
        val known =
            when (val check = ProvenanceRegistry.current) {
                is ProvenanceCheck.Valid -> check.sources.associateBy { it.id }
                is ProvenanceCheck.Invalid -> throw AssertionError(check.problems.joinToString("\n"))
            }
        val unregistered = entries.map { it.provenanceEntry }.toSet() - known.keys
        assertEquals("MASTER_PLAN §2.1 rule 9", emptySet<String>(), unregistered)
        val wrongTier =
            entries.filterNot { it.licenseTier == known.getValue(it.provenanceEntry).tier }
        assertEquals(
            "a ledger row's tier must be the registry's, or the two can disagree about what may be copied",
            emptyList<String>(),
            wrongTier.map { it.upstreamName },
        )
    }

    @Test
    fun `nothing is scheduled to be ported out of a source that forbids copying`() {
        // PORT means "becomes its own engine". Under a no-code tier that is
        // still legal - the maths is reimplemented - but it is the row most
        // likely to be read as permission, so it must name the constraint.
        val silent =
            entries.filter {
                it.disposition == "PORT" &&
                    it.licenseTier in ProvenanceRegistry.NO_CODE_TIERS &&
                    !it.rationale.contains("reimplement", ignoreCase = true) &&
                    !it.rationale.contains("independent", ignoreCase = true) &&
                    !it.rationale.contains("published", ignoreCase = true)
            }
        assertEquals(
            "a PORT from a no-code source must say in its rationale that it is reimplemented",
            emptyList<String>(),
            silent.map { "${it.source}/${it.upstreamName}" },
        )
    }

    @Test
    fun the_coverage_document_is_current() {
        val doc = ReferenceCoverage.document
        val generated = ReferenceCoverage.render()
        val current = doc.takeIf { it.isFile }?.readText()?.replace("\r\n", "\n")
        if (current != generated) {
            doc.writeText(generated)
            throw AssertionError(
                "docs/visualizer-v2/REFERENCE_COVERAGE.md was out of date and has been regenerated " +
                    "from reference-coverage.json - review the diff and commit it.",
            )
        }
    }
}
