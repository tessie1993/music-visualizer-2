plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// One command that covers every module, present and future.
//
// `./gradlew check` at the root already aggregates subprojects, but only the
// ones that exist when it is written. This is declared over `subprojects` so a
// module added by MASTER_PLAN §4.1 is covered the day it appears rather than
// the day somebody remembers to add it to CI - which is the failure mode §4.1
// warns about, where a gate stops applying to code nobody noticed it stopped
// applying to.
tasks.register("checkAll") {
    group = "verification"
    description = "Runs check in every module."
    // Only projects that actually carry a build file: `:engine` is a
    // container Gradle creates from the `:engine:*` paths and has no tasks.
    dependsOn(subprojects.filter { it.buildFile.exists() }.map { "${it.path}:check" })
}
