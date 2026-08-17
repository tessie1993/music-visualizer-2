// Settings every Geode module shares, whether it is the Android application
// or one of the pure-JVM engine modules: one JDK target, one Kotlin style, one
// place to change either.
//
// MASTER_PLAN §4.1 makes this a prerequisite for the module split rather than a
// tidy-up after it. Six modules configured by copy-paste diverge, and the
// divergence surfaces as a source-text gate that silently stops applying to a
// module whose ktlint never ran.
plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("geode.provenance")
}

ktlint {
    // Matches what :app already resolved to, so applying this changes nothing
    // about how existing code is formatted.
    android.set(true)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
