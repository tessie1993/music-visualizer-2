// An Android engine module: GLES, Media3 and the platform types the JVM
// modules are forbidden from touching.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("musicviz.kotlin-common")
}

extensions.configure<com.android.build.gradle.LibraryExtension> {
    compileSdk = 36
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    "testImplementation"("junit:junit:4.13.2")
}
