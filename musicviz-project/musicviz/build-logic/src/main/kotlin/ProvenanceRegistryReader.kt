import groovy.json.JsonSlurper

/** File types that can carry an origin marker into a shipped artifact. */
val SCANNED_EXTENSIONS = setOf("kt", "kts", "java", "c", "h", "cpp", "glsl")

/**
 * Reads `provenance.json` into the records the rules need.
 *
 * Tolerant on purpose: a field this does not understand is not this file's
 * business, and `EngineProvenanceRegistryTest` is what fails on a malformed
 * document. Two things failing the same way for different reasons makes the
 * second report useless.
 */
@Suppress("UNCHECKED_CAST")
fun readProvenanceRegistry(json: String): List<ProvenanceSourceRecord> {
    // Safe casts throughout: a malformed registry returns nothing here and is
    // reported by EngineProvenanceRegistryTest, which exists to say why.
    val root = JsonSlurper().parseText(json) as? Map<String, Any?> ?: return emptyList()
    val sources = root["sources"] as? List<Map<String, Any?>> ?: emptyList()
    return sources.map { entry ->
        ProvenanceSourceRecord(
            id = entry["id"] as? String ?: "",
            url = entry["url"] as? String,
            tier = entry["tier"] as? String ?: "",
            licence = entry["license"] as? String ?: "",
            commit = (entry["pin"] as? Map<String, Any?>)?.get("commit") as? String,
            importedFiles = (entry["importedFiles"] as? List<String>).orEmpty(),
        )
    }
}
