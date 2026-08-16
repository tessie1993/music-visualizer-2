// MASTER_PLAN §3.3: the provenance gate runs on `check`, in every module.
//
// "Every module" is the part that matters. The rules used to live in one
// module's unit test, scanning a hardcoded `app/src/main` - so the day adapted
// code landed in :engine:scenes the scan would have gone on passing while
// covering none of it. That is the same failure the repo already has with
// source-text gates, and applying the plugin per module is what avoids
// repeating it.
val provenanceFile = rootProject.file("docs/visualizer-v2/provenance.json")
val noticesFile = rootProject.file("THIRD_PARTY_NOTICES")

val checkEngineProvenance =
    tasks.register("checkEngineProvenance") {
        description = "Fails if a source file's origin markers disagree with the provenance registry."
        val sourceRoot = layout.projectDirectory.dir("src/main").asFile
        inputs.file(provenanceFile)
        inputs.file(noticesFile)
        doLast {
            val scanned =
                sourceRoot
                    .walkTopDown()
                    .filter { it.isFile && it.extension in SCANNED_EXTENSIONS }
                    .map { ScannedFile(it.relativeTo(rootProject.projectDir).path, it.readText()) }
                    .toList()
            val violations =
                ProvenanceRules.check(
                    files = scanned,
                    sources = readProvenanceRegistry(provenanceFile.readText()),
                    notices = noticesFile.readText(),
                )
            if (violations.isNotEmpty()) {
                throw GradleException(
                    "provenance check failed in ${project.path} — see docs/visualizer-v2/SOURCE_ARCHIVE.md:\n" +
                        violations.joinToString("\n") { "  ${it.where}: $it" },
                )
            }
        }
    }

tasks.matching { it.name == "check" }.configureEach { dependsOn(checkEngineProvenance) }
