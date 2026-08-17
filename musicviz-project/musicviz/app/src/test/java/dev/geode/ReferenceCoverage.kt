package dev.geode

import org.json.JSONObject
import java.io.File

/**
 * Renders `docs/visualizer-v2/REFERENCE_COVERAGE.md` from `reference-coverage.json`.
 *
 * Same reasoning as [ParamMatrix]: a ledger of 160-odd rows maintained by hand
 * drifts, and a drifted ledger is worse than none — it reads as though every
 * researched effect has been accounted for. The JSON is the record; this is a
 * view of it, grouped by the family that owns each idea, because the question a
 * session actually asks is "what else belongs to the engine I am building".
 */
object ReferenceCoverage {
    private val docs = File(ParamSurface.moduleRoot, "docs/visualizer-v2")

    val file: File = File(docs, "reference-coverage.json")

    val document: File = File(docs, "REFERENCE_COVERAGE.md")

    data class Entry(
        val source: String,
        val upstreamName: String,
        val upstreamCommit: String?,
        val licenseTier: String,
        val family: String?,
        val recipeId: String?,
        val disposition: String,
        val rationale: String,
        val provenanceEntry: String,
        val tests: String?,
        val screenshots: String?,
        val shippedVersion: String?,
    )

    private val root: JSONObject by lazy { JSONObject(file.readText()) }

    val families: Map<String, String> by lazy {
        root.getJSONObject("families").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    }

    val dispositions: Map<String, String> by lazy {
        root.getJSONObject("dispositions").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    }

    val entries: List<Entry> by lazy {
        val array = root.getJSONArray("entries")
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)

            fun opt(key: String): String? = o.optString(key).takeIf { it.isNotEmpty() && it != "null" }
            Entry(
                source = o.getString("source"),
                upstreamName = o.getString("upstreamName"),
                upstreamCommit = opt("upstreamCommit"),
                licenseTier = o.getString("licenseTier"),
                family = opt("family"),
                recipeId = opt("recipeId"),
                disposition = o.getString("disposition"),
                rationale = o.getString("rationale"),
                provenanceEntry = o.getString("provenanceEntry"),
                tests = opt("tests"),
                screenshots = opt("screenshots"),
                shippedVersion = opt("shippedVersion"),
            )
        }
    }

    private fun cell(value: String?): String = value ?: "—"

    fun render(): String =
        buildString {
            appendLine("# Reference coverage ledger")
            appendLine()
            appendLine("**Generated — do not edit.** `ReferenceCoverageTest` rewrites this file from")
            appendLine("`reference-coverage.json` whenever the two drift, and fails until the new version is")
            appendLine("committed. Edit the JSON.")
            appendLine()
            appendLine("Every effect and concept named in [`MASTER_PLAN.md`](MASTER_PLAN.md) §8.1, with the")
            appendLine("family that owns it and what is being done with it. §3.2 requires the enumeration to")
            appendLine("be complete rather than limited to the first release: without it the same source gets")
            appendLine("re-researched, a shader gets borrowed with no traceable origin, and the catalogue")
            appendLine("fills with four near-duplicates of one idea found in four repositories.")
            appendLine()
            appendLine("A row is **complete** only once its implementation, rejection or merge is evidenced.")
            appendLine("Being named here means nothing has been incorporated yet.")
            appendLine()
            appendLine("## Totals")
            appendLine()
            appendLine("| Disposition | Meaning | Rows |")
            appendLine("|---|---|---:|")
            dispositions.forEach { (key, meaning) ->
                appendLine("| **$key** | $meaning | ${entries.count { it.disposition == key }} |")
            }
            appendLine("| | **total** | **${entries.size}** |")
            appendLine()
            appendLine("| Source | Rows | Licence tier |")
            appendLine("|---|---:|---|")
            entries
                .groupBy { it.source }
                .forEach { (source, rows) ->
                    appendLine("| `$source` | ${rows.size} | ${rows.first().licenseTier} |")
                }
            appendLine()
            appendLine("## Coverage by family")
            appendLine()
            appendLine("Columns are the §3.2 schema. `recipe`, `tests`, `captures` and `shipped` fill in as")
            appendLine("the owning family's slices land.")
            val order = families.keys.toList() + listOf<String?>(null)
            for (family in order) {
                val rows = entries.filter { it.family == family }
                if (rows.isEmpty()) continue
                appendLine()
                appendLine("### ${family?.let { "`$it` — ${families.getValue(it)}" } ?: "Unassigned"}")
                appendLine()
                if (family == null) {
                    appendLine("Named in §8.1, not yet attributable to a family from the plan alone. Each needs")
                    appendLine("the upstream look characterised before a family can own it — and until then it")
                    appendLine("is not evidence that the catalogue is short of anything.")
                    appendLine()
                }
                appendLine("| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |")
                appendLine("|---|---|---|---|---|---|---|---|---|---|")
                rows
                    .sortedBy { it.upstreamName.lowercase() }
                    .forEach {
                        appendLine(
                            "| ${it.upstreamName} | `${it.source}` | `${it.upstreamCommit?.take(7) ?: "—"}` | " +
                                "${it.licenseTier} | ${cell(it.recipeId)} | **${it.disposition}** | ${it.rationale} | " +
                                "${cell(it.tests)} | ${cell(it.screenshots)} | ${cell(it.shippedVersion)} |",
                        )
                    }
            }
        }
}
