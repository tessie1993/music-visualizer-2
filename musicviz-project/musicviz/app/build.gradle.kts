import org.gradle.api.tasks.testing.logging.TestExceptionFormat
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
        versionCode = 28
        versionName = "1.4.0"
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

// --- Robolectric runtime jars, resolved by Gradle instead of at test time ----
//
// Robolectric does not get its `android-all` runtime jar from the test
// classpath. Unless told otherwise it downloads it, mid-test, with its own
// bundled Maven client (`MavenArtifactFetcher`) into ~/.m2/repository — a
// directory the CI Gradle cache does not restore, so every CI run re-fetched
// ~200 MB over the network while the tests were already running. That fetcher
// has no retry: one transient HTTP error fails *every* Robolectric test at
// once. That is exactly what happened on run 30590147361 attempt 1, where all
// 38 Robolectric tests failed with `AssertionError at
// MavenArtifactFetcher.java:129` / `Caused by: IOException` while the byte-
// identical tree passed on attempt 2 and on the branch run before it.
//
// Declaring the jars as ordinary Gradle dependencies moves the download into
// normal dependency resolution: cached in ~/.gradle/caches (which CI *does*
// restore), retried and checksum-verified by Gradle, and reported as a plain
// resolution error rather than as 38 mystery test failures. The tests
// themselves then run with no network access at all.
//
// One configuration is needed per SDK level named in an `@Config(sdk = [...])`
// under src/test. Version strings are the ones Robolectric 4.14.1 asks for
// (org.robolectric.plugins.DefaultSdkProvider): sdk 34 -> Android 14 build
// 10818077, sdk 35 -> Android 15 build 12650502, with the `-i7`
// preinstrumented-jar revision this Robolectric release pins. Adding a test on
// a new SDK level means adding its own configuration here; Robolectric will
// otherwise fail loudly with "Unable to locate dependency".
//
// It has to be one configuration *per* SDK level rather than one shared one:
// these jars are all versions of the same module
// (org.robolectric:android-all-instrumented), so in a single configuration
// Gradle's conflict resolution collapses them to the highest version and
// stages only that one jar, failing every test on every other SDK level.
val robolectricSdk34 by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val robolectricSdk35 by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

val robolectricSdkDir = layout.buildDirectory.dir("robolectric-sdks")

val stageRobolectricSdks =
    tasks.register<Sync>("stageRobolectricSdks") {
        description = "Stages Robolectric's android-all jars so unit tests never hit the network."
        from(robolectricSdk34, robolectricSdk35)
        into(robolectricSdkDir)
    }

tasks.withType<Test>().configureEach {
    dependsOn(stageRobolectricSdks)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricSdkDir.get().asFile.absolutePath)
    // Gradle's default (SHORT) prints only "AssertionError at Foo.java:12" with
    // no message at all, which is exactly what made the flake above so hard to
    // read from a CI log. FULL is only ever paid for on a failing build.
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showCauses = true
        showExceptions = true
        showStackTraces = true
    }
}

dependencies {
    robolectricSdk34("org.robolectric:android-all-instrumented:14-robolectric-10818077-i7")
    robolectricSdk35("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")

    implementation(libs.core.splashscreen)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
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
