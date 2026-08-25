import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("org.jlleitschuh.gradle.ktlint")
    id("synesthesia.module-dag")
}

extensions.configure<ApplicationExtension> {
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        targetSdk = 37
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
