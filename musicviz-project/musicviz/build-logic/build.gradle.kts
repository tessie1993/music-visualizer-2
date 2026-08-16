plugins {
    `kotlin-dsl`
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.ktlint.gradle)
    implementation(libs.kotlin.gradle.plugin)
}
