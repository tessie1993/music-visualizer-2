/**
 * The source-tree half of MASTER_PLAN §3.3: does the code obey the registry.
 *
 * Deliberately free of Gradle types so it can be unit-tested directly. The
 * task that calls it only finds files and reports; every decision is here.
 *
 * The registry document's own validity - schema, ledger coverage, licence
 * hashes - is a different question and stays in `EngineProvenanceRegistryTest`.
 */
data class ProvenanceSourceRecord(
    val id: String,
    val url: String?,
    val tier: String,
    val licence: String,
    val commit: String?,
    val importedFiles: List<String>,
)

data class ScannedFile(
    val path: String,
    val text: String,
)

/** Everything a scan can find wrong, as data rather than as a thrown string. */
sealed interface ProvenanceViolation {
    val where: String

    data class OriginWithoutSpdx(override val where: String) : ProvenanceViolation

    data class UnknownOrigin(override val where: String, val url: String) : ProvenanceViolation

    data class OriginCommitMismatch(
        override val where: String,
        val cited: String,
        val pinned: String,
    ) : ProvenanceViolation

    data class ForbiddenTier(override val where: String, val id: String, val tier: String) : ProvenanceViolation

    data class LicenceMismatch(
        override val where: String,
        val declared: String,
        val registry: String,
    ) : ProvenanceViolation

    data class ForbiddenSourceMentioned(override val where: String, val id: String) : ProvenanceViolation

    data class MissingNotice(override val where: String) : ProvenanceViolation
}

object ProvenanceRules {
    /** Tiers that may legitimately contribute upstream text to the tree. */
    private val ADOPTABLE = setOf("ADAPT", "RETAIN")

    /**
     * Licence strings that are plain SPDX identifiers, so a marker claiming a
     * different one is a real mistake rather than a formatting difference.
     * Anything else in the registry - "Prosperity Public License 3.0.0", or
     * RDPE's "MIT declared in Cargo.toml; LICENSE file absent" - is prose, and
     * comparing against it would fail honest files.
     */
    private val SPDX_IDS = setOf("MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "Unlicense", "ISC", "LGPL-2.1", "GPL-3.0", "AGPL-3.0")

    private val ORIGIN = Regex("""Origin:\s*(\S+?)(?:@([0-9a-f]{7,40}))?\s*$""", RegexOption.MULTILINE)
    private val SPDX = Regex("""SPDX-License-Identifier:\s*(\S+)""")

    fun check(
        files: List<ScannedFile>,
        sources: List<ProvenanceSourceRecord>,
        notices: String,
    ): List<ProvenanceViolation> {
        val byUrl = sources.filter { it.url != null }.associateBy { normalise(it.url!!) }
        return files.flatMap { file -> checkFile(file, byUrl) } + noticeViolations(sources, notices)
    }

    private fun checkFile(
        file: ScannedFile,
        byUrl: Map<String, ProvenanceSourceRecord>,
    ): List<ProvenanceViolation> {
        val cited = ORIGIN.findAll(file.text).toList()
        val citedUrls = cited.map { normalise(it.groupValues[1]) }.toSet()
        // Naming a no-code source anywhere in shipped source is a claim of
        // origin whether or not it uses the marker - checked even when the file
        // also carries a legitimate citation, because a correctly attributed
        // SwissGL kernel is not a licence to mention a GPL repository beside it.
        // Sources reached through a marker are excluded so they are reported
        // once, as the more specific ForbiddenTier.
        val mentioned =
            byUrl.values.filter { it.tier !in ADOPTABLE && normalise(it.url.orEmpty()) !in citedUrls && mentions(file.text, it) }
        return mentioned.map { ProvenanceViolation.ForbiddenSourceMentioned(file.path, it.id) } +
            cited.flatMap { match -> citationViolations(file, match.groupValues[1], match.groupValues[2], byUrl) }
    }

    private fun citationViolations(
        file: ScannedFile,
        url: String,
        commit: String,
        byUrl: Map<String, ProvenanceSourceRecord>,
    ): List<ProvenanceViolation> {
        val source =
            byUrl[normalise(url)]
                ?: return listOf(ProvenanceViolation.UnknownOrigin(file.path, url))
        val out = mutableListOf<ProvenanceViolation>()
        if (source.tier !in ADOPTABLE) out += ProvenanceViolation.ForbiddenTier(file.path, source.id, source.tier)
        if (commit.isNotEmpty() && source.commit != null && !source.commit.startsWith(commit)) {
            out += ProvenanceViolation.OriginCommitMismatch(file.path, commit, source.commit)
        }
        val declared = SPDX.find(file.text)?.groupValues?.get(1)
        if (declared == null) {
            out += ProvenanceViolation.OriginWithoutSpdx(file.path)
        } else if (source.licence in SPDX_IDS && declared != source.licence) {
            out += ProvenanceViolation.LicenceMismatch(file.path, declared, source.licence)
        }
        return out
    }

    private fun noticeViolations(
        sources: List<ProvenanceSourceRecord>,
        notices: String,
    ): List<ProvenanceViolation> =
        sources
            .filter { it.importedFiles.isNotEmpty() }
            .filterNot { source -> source.url?.let { notices.contains(it) } == true }
            .map { ProvenanceViolation.MissingNotice(it.id) }

    private fun mentions(
        text: String,
        source: ProvenanceSourceRecord,
    ): Boolean = source.url?.let { text.contains(normalise(it)) } == true

    /** Compares repositories, not URL spellings: scheme and trailing slash vary. */
    private fun normalise(url: String): String = url.substringAfter("://").trimEnd('/').removeSuffix(".git")
}
