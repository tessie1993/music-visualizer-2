plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

tasks.register("checkAll") {
    group = "verification"
    description = "Runs check in every module."
    dependsOn(subprojects.filter { it.buildFile.exists() }.map { "${it.path}:check" })
}
