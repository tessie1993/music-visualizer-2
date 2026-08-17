package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The V2 overhaul is a queue of small slices spread over many sessions, and an
 * agent with no memory of the last one reads `docs/visualizer-v2/` to find out
 * where it is. That makes the directory load-bearing: two documents that both
 * read as live instructions, or a slice log whose state field says nothing the
 * protocol recognises, and the next session resumes against the wrong plan.
 *
 * So the same things are checked here that a build checks about code:
 * exactly one document is the authority, every other plan says out loud what
 * superseded it, every relative link lands somewhere, and `STATUS.md` records
 * at most one slice that is not finished.
 */
class EngineV2PlanAuthorityTest {
    private val docs = File(ParamSurface.moduleRoot, "docs/visualizer-v2")

    private val plans: List<File>
        get() =
            docs
                .listFiles { file -> file.name.endsWith("_PLAN.md") }
                .orEmpty()
                .sortedBy { it.name }

    private fun statusOf(plan: File): String =
        Regex("""^\*\*Document status:\*\* (.+)$""", RegexOption.MULTILINE)
            .find(plan.readText())
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: fail("${plan.name} declares no **Document status:** line")

    private fun fail(why: String): Nothing = throw AssertionError(why)

    @Test
    fun `the required Phase A documents are in place`() {
        val required =
            listOf(
                "MASTER_PLAN.md",
                "STATUS.md",
                "DECISIONS.md",
                "LEGACY_DISPOSITION.md",
                "REFERENCE_COVERAGE.md",
                "SOURCE_ARCHIVE.md",
                "provenance.json",
            )
        val missing = required.filterNot { File(docs, it).isFile }
        assertEquals("MASTER_PLAN §2.2 requires these in docs/visualizer-v2/", emptyList<String>(), missing)
        assertTrue("the ADR index directory is missing", File(docs, "adr").isDirectory)
    }

    @Test
    fun `exactly one plan document is the execution authority`() {
        val authorities = plans.filter { statusOf(it) == "execution authority" }
        assertEquals(
            "two live plans means the next session can resume against the wrong one",
            listOf("MASTER_PLAN.md"),
            authorities.map { it.name },
        )
    }

    @Test
    fun `every superseded plan names what replaced it`() {
        plans
            .filterNot { it.name == "MASTER_PLAN.md" }
            .forEach { plan ->
                assertTrue(
                    "${plan.name} is not the authority, so its status must point at MASTER_PLAN.md",
                    statusOf(plan).contains("MASTER_PLAN.md"),
                )
            }
    }

    @Test
    fun `every relative link in the V2 docs resolves`() {
        val broken =
            docs
                .listFiles { file -> file.extension == "md" }
                .orEmpty()
                .flatMap { doc ->
                    Regex("""]\(([^)]+)\)""")
                        .findAll(doc.readText())
                        .map { it.groupValues[1] }
                        .filterNot { it.startsWith("http") || it.startsWith("#") }
                        .map { doc.name to it.substringBefore('#') }
                        .filterNot { (_, target) -> File(docs, target).exists() }
                        .toList()
                }
        assertEquals(emptyList<Pair<String, String>>(), broken)
    }

    @Test
    fun `STATUS records at most one unfinished slice, in a state the protocol knows`() {
        val states =
            listOf(
                "LOCKED", "DISCOVERY", "SPECIFIED", "RED", "IMPLEMENTING",
                "VERIFYING", "REVIEWING", "READY_TO_COMMIT", "COMPLETE",
            )
        val status = File(docs, "STATUS.md").readText()
        val slices =
            Regex("""^## (V2-[A-Za-z0-9-]+):.*$""", RegexOption.MULTILINE)
                .findAll(status)
                .map { it.groupValues[1] }
                .toList()
        assertTrue("STATUS.md records no slice at all", slices.isNotEmpty())

        val recorded =
            Regex("""^State: *(\S+)""", RegexOption.MULTILINE)
                .findAll(status)
                .map { it.groupValues[1] }
                .toList()
        assertEquals("every slice section needs exactly one State: line", slices.size, recorded.size)
        assertEquals("unknown state in STATUS.md", emptySet<String>(), recorded.toSet() - states.toSet())
        // MASTER_PLAN §2.1 rule 2 bans a second slice while one is unfinished.
        // LOCKED is exempt because it means "specified and not begun" - a
        // slice held on hardware this environment does not have would
        // otherwise stop the queue for everything behind it. See adr/0002.
        val active = recorded.filter { it != "COMPLETE" && it != "LOCKED" }
        assertTrue("only one slice may be in progress, found $active", active.size <= 1)
    }

    @Test
    fun `every recorded slice carries the full specification`() {
        val fields =
            listOf(
                "State", "Goal", "User-visible effect", "In scope", "Out of scope",
                "Files expected to change", "Compatibility contract",
                "External source/provenance entries", "Tests written first",
                "Benchmark or visual evidence", "Rollback", "Risks",
                "Commands and results", "Review findings", "Commit", "Next slice",
            )
        val sections =
            File(docs, "STATUS.md")
                .readText()
                .split(Regex("""^## (?=V2-)""", RegexOption.MULTILINE))
                .drop(1)
        sections.forEach { section ->
            val id = section.substringBefore(':')
            val missing = fields.filterNot { section.contains(Regex("""^$it:""", RegexOption.MULTILINE)) }
            assertEquals("slice $id is missing MASTER_PLAN §2.3 fields", emptyList<String>(), missing)
        }
    }
}
