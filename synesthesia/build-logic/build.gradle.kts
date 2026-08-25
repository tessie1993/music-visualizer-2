import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)
    implementation(libs.ktlint.gradle)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "synesthesia.android.application"
            implementationClass = "SynesthesiaAndroidApplicationPlugin"
        }
        register("androidLibrary") {
            id = "synesthesia.android.library"
            implementationClass = "SynesthesiaAndroidLibraryPlugin"
        }
        register("moduleDag") {
            id = "synesthesia.module.dag"
            implementationClass = "SynesthesiaModuleDagPlugin"
        }
    }
}
