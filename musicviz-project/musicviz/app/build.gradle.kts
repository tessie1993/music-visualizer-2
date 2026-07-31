import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

// Upload-key material for the Play Store build. Resolved from (in order):
//   1) keystore.properties next to settings.gradle.kts (local dev; git-ignored)
//   2) MUSICVIZ_KEYSTORE / _PASSWORD / _KEY_ALIAS / _KEY_PASSWORD env vars (CI)
// When neither is present, the release build type is left unsigned so that
// `assembleRelease` still works for local smoke tests.
val keystoreProps =
    Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun releaseSecret(
    propKey: String,
    envKey: String,
): String? = keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val releaseStorePath = releaseSecret("storeFile", "MUSICVIZ_KEYSTORE")
val releaseStorePassword = releaseSecret("storePassword", "MUSICVIZ_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSecret("keyAlias", "MUSICVIZ_KEY_ALIAS")
val releaseKeyPassword = releaseSecret("keyPassword", "MUSICVIZ_KEY_PASSWORD")
val hasReleaseSigning =
    releaseStorePath != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

android {
    namespace = "dev.musicviz"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.musicviz"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "1.0.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Keep debug installable next to a Play build of the same app.
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // Uncompressed + page-aligned .so in the APK/AAB. Required for the
            // 16 KB page-size devices Play mandates support for; also lets the
            // loader mmap libprojectM instead of extracting it.
            useLegacyPackaging = false
        }
    }

    bundle {
        // MusicViz ships a single locale and its own GL assets; splitting by
        // language/density only adds ways for a device to end up missing
        // resources at runtime.
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // A release upload that fails lint is a wasted review cycle.
        checkReleaseBuilds = true
        abortOnError = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// LGPL-2.1 (libprojectM) requires the notice to reach the user, not just sit in
// the repository, so app/src/main/assets/third_party_notices.txt ships a copy of
// the root THIRD_PARTY_NOTICES for the in-app "Open source licenses" screen.
// This task refreshes that copy; `checkThirdPartyNotices` (also run in CI)
// fails the build if the two ever drift.
tasks.register<Copy>("syncThirdPartyNotices") {
    description = "Refreshes the bundled copy of THIRD_PARTY_NOTICES."
    from(rootProject.file("THIRD_PARTY_NOTICES")) {
        rename { "third_party_notices.txt" }
    }
    into(file("src/main/assets"))
}

tasks.register("checkThirdPartyNotices") {
    description = "Fails if the bundled notices asset has drifted from THIRD_PARTY_NOTICES."
    val source = rootProject.file("THIRD_PARTY_NOTICES")
    val bundled = file("src/main/assets/third_party_notices.txt")
    inputs.file(source)
    inputs.file(bundled)
    doLast {
        if (source.readText() != bundled.readText()) {
            throw GradleException(
                "third_party_notices.txt is out of date — run ./gradlew :app:syncThirdPartyNotices",
            )
        }
    }
}

tasks.named("check") { dependsOn("checkThirdPartyNotices") }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.core.splashscreen)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.documentfile)
    implementation(libs.jtransforms)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(kotlin("reflect"))
    testImplementation(libs.junit)
    // Real org.json for plain (non-Robolectric) unit tests: the mockable
    // android.jar's org.json classes throw "Stub!" (TrackLibraryMigrationTest).
    testImplementation(libs.json)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
