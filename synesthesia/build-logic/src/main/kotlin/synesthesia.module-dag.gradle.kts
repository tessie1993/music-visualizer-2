// Module DAG law enforcement (docs/ARCHITECTURE_BLUEPRINT.md §1).
// Surface-gate style (repo tradition): scans THIS module's build file text.
//   core:*   must not depend on feature:* or :app
//   feature:* must not depend on feature:* or :app
// Violations fail every task via preBuild wiring below.
import java.io.File

val selfPath = project.path
val selfLayer = when {
    selfPath == ":app" -> "app"
    selfPath.startsWith(":core:") -> "core"
    selfPath.startsWith(":feature:") -> "feature"
    else -> "other"
}

val forbidden = mutableListOf<String>()
when (selfLayer) {
    "core" -> {
        forbidden += """project\(":feature:"""
        forbidden += """project\(":app"""
    }
    "feature" -> {
        forbidden += """project\(":feature:"""
        forbidden += """project\(":app"""
    }
}

val checkModuleDag = tasks.register("checkModuleDag") {
    val buildFile = project.buildFile
    val patterns = forbidden.toList()
    doLast {
        if (patterns.isEmpty()) return@doLast
        val text = buildFile.readText()
        val hits = patterns.filter { Regex(it).containsMatchIn(text) }
        check(hits.isEmpty()) {
            "Module DAG violation in $selfPath: illegal dependency declaration(s) $hits." +
                "\nSee docs/ARCHITECTURE_BLUEPRINT.md section 1 for the allowed graph."
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(checkModuleDag)
}
