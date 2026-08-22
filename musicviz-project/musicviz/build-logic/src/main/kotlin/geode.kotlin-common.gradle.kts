plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("geode.provenance")
}

ktlint {
    android.set(true)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
