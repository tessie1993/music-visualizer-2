plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("geode.provenance")
}

ktlint {
    android.set(true)
    // The PLUGIN moves to 14.x because that is what supports Gradle 9; the
    // ENGINE is pinned separately, because a newer engine is a newer set of
    // style rules, not a compatibility requirement. Unpinned it reports 454
    // violations across 52 files (argument-list-wrapping,
    // chain-method-continuation, class-signature) in a tree that has always
    // passed this gate. Adopting that reformat is a change worth making on its
    // own; it is not part of moving the build to JDK 25 and API 37.
    version.set("1.0.1")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
