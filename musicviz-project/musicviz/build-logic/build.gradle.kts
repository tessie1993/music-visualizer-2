plugins {
    `kotlin-dsl`
}

// Both halves are pinned: without the java block, compileJava silently follows
// whatever JDK is running Gradle (25 in CI) while compileKotlin stays on 21,
// and Kotlin reports the pair as inconsistent.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.ktlint.gradle)
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)
}
