import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("synesthesia.module-dag")
}

extensions.configure<LibraryExtension> {
    compileSdk = 37
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    "testImplementation"("junit:junit:4.13.2")
    "testImplementation"("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
