package dev.geode

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reads and validates `docs/visualizer-v2/provenance.json`.
 *
 * The registry is what stands between "we read a lot of repositories" and a
 * shipped app that can say, per file, where an idea came from and what it owes.
 * MASTER_PLAN §3.3 makes that a Gradle task (`checkEngineProvenance`, slice
 * V2-1-04) once adapted source starts landing; until then the same rules run
 * here, so the registry cannot rot in the gap between the two slices.
 *
 * Kept as a parser over the raw text rather than a typed deserializer because
 * the point is to reject documents that do not fit the schema, and a
 * deserializer that throws tells the reader less than a list of problems does.
 */
object ProvenanceRegistry {
    /** MASTER_PLAN §3 plus the two tiers §3.1 uses in its table but not its prose. */
    val TIERS: Set<String> =
        setOf("ADAPT", "REIMPLEMENT", "ORACLE", "BENCHMARK", "RETAIN", "STUDY", "EXCLUDE")

    /**
     * Tiers under which no upstream file may enter the tree, so a non-empty
     * `importedFiles` is a contradiction the registry rejects rather than
     * records. That is every tier except the two that may carry upstream text:
     * ADAPT, which adapts files under attribution, and RETAIN, which is code
     * already shipped. REIMPLEMENT belongs here - §3 permits the algorithm and
     * forbids the code, shader text, constant tables, names and layout.
     */
    val NO_CODE_TIERS: Set<String> = TIERS - setOf("ADAPT", "RETAIN")

    private val EVIDENCE_STATES = setOf("verified", "none-published", "unresolved")

    private val docs = File(ParamSurface.moduleRoot, "docs/visualizer-v2")

    val file: File = File(docs, "provenance.json")

    /** The registry as it stands in the repository. */
    val current: ProvenanceCheck by lazy { validate(file.readText()) }

    /**
     * The §3.1 ledger rows, by the bolded name in the first column. The plan is
     * the specification and the registry is the implementation; a row with no
     * entry is a source nobody has pinned, and an entry with no row is a source
     * the plan never admitted.
     */
    val ledgerRows: Set<String> by lazy {
        val section =
            File(docs, "MASTER_PLAN.md")
                .readText()
                .substringAfter("### 3.1")
                .substringBefore("### 3.2")
        Regex("""^\| \*\*(.+?)\*\*""", RegexOption.MULTILINE)
            .findAll(section)
            .map { it.groupValues[1] }
            .toSet()
    }

    fun validate(text: String): ProvenanceCheck {
        val problems = mutableListOf<String>()
        val root =
            runCatching { JSONObject(text) }
                .getOrElse { return ProvenanceCheck.Invalid(listOf("not JSON: ${it.message}")) }

        if (root.optInt("schemaVersion", -1) != 2) {
            problems += "schemaVersion must be 2, found ${root.opt("schemaVersion")}"
        }
        val declaredTiers = root.optJSONObject("tiers")?.keys()?.asSequence()?.toSet().orEmpty()

        val array = root.optJSONArray("sources") ?: JSONArray()
        if (array.length() == 0) problems += "no sources"

        val sources = mutableListOf<ProvenanceSource>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val id = o.optString("id")
            val where = if (id.isEmpty()) "source[$i]" else id
            if (id.isEmpty()) problems += "$where: no id"
            if (!seen.add(id)) problems += "$where: duplicate id"
            if (o.optString("planLedger").isEmpty()) problems += "$where: no planLedger row"

            val tier = o.optString("tier")
            if (tier !in TIERS) problems += "$where: unknown tier '$tier'"
            if (tier in TIERS && tier !in declaredTiers) problems += "$where: tier '$tier' is undeclared"

            val imported = o.optJSONArray("importedFiles")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            if (tier in NO_CODE_TIERS && imported.isNotEmpty()) {
                problems += "$where: tier $tier forbids adopted files, found ${imported.size}"
            }

            val evidence = o.optJSONObject("licenceEvidence")
            val state = evidence?.optString("state").orEmpty()
            if (state !in EVIDENCE_STATES) problems += "$where: licence evidence state '$state'"
            if (state == "verified") {
                val sha = evidence?.optString("sha256").orEmpty()
                if (!sha.matches(Regex("[0-9a-f]{64}"))) problems += "$where: licence sha256 is not a hash"
                if (evidence?.optString("file").isNullOrEmpty()) problems += "$where: no licence file name"
                val pin = o.optJSONObject("pin")?.optString("commit").orEmpty()
                if (!pin.matches(Regex("[0-9a-f]{40}"))) problems += "$where: verified licence needs a pinned commit"
            }
            sources += ProvenanceSource(id, o.optString("planLedger"), o.optString("url").ifEmpty { null }, tier, state, imported)
        }
        return if (problems.isEmpty()) ProvenanceCheck.Valid(sources) else ProvenanceCheck.Invalid(problems)
    }
}

/** One repository's row in the registry, reduced to what the gates check. */
data class ProvenanceSource(
    val id: String,
    val planLedger: String,
    val url: String?,
    val tier: String,
    val licenceState: String,
    val importedFiles: List<String>,
)

sealed interface ProvenanceCheck {
    data class Valid(
        val sources: List<ProvenanceSource>,
    ) : ProvenanceCheck

    data class Invalid(
        val problems: List<String>,
    ) : ProvenanceCheck
}
