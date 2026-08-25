import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * Module DAG law enforcement (docs/ARCHITECTURE_BLUEPRINT.md section 1).
 * Surface-gate style: scans THIS module's build file text for illegal
 * dependency declarations.
 *   core:*    must not depend on feature:* or :app
 *   feature:* must not depend on feature:* or :app
 */
class SynesthesiaModuleDagPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val layer = when {
            path == ":app" -> "app"
            path.startsWith(":core:") -> "core"
            path.startsWith(":feature:") -> "feature"
            else -> "other"
        }

        val forbidden = mutableListOf<String>()
        if (layer == "core" || layer == "feature") {
            forbidden += """project\(":feature:"""
            forbidden += """project\(":app"""
        }

        val checkModuleDag = tasks.register("checkModuleDag") {
            val buildFile: File = project.buildFile
            val patterns = forbidden.toList()
            doLast {
                if (patterns.isEmpty()) return@doLast
                val hits = patterns.filter { Regex(it).containsMatchIn(buildFile.readText()) }
                check(hits.isEmpty()) {
                    "Module DAG violation in $path: illegal dependency declaration(s) $hits." +
                        "\nSee docs/ARCHITECTURE_BLUEPRINT.md section 1 for the allowed graph."
                }
            }
        }

        tasks.matching { it.name == "preBuild" }.configureEach {
            dependsOn(checkModuleDag)
        }
    }
}
