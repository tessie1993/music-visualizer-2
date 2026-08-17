import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("geode.kotlin-common")
    alias(libs.plugins.detekt)
}

// Static analysis tuned for this codebase: MagicNumber is graphics tuning
// here (thousands of shader/scene constants), Compose functions are
// PascalCase by convention, and the bug-finding rule sets stay on. Current
// findings are baselined; new SwallowedException/UnusedParameter-class bugs
// fail the build from now on.
detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    buildUponDefaultConfig = true
}

// Upload-key material for the Play Store build. Resolved from (in order):
//   1) keystore.properties next to settings.gradle.kts (local dev; git-ignored)
//   2) GEODE_KEYSTORE / _PASSWORD / _KEY_ALIAS / _KEY_PASSWORD env vars (CI)
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

val releaseStorePath = releaseSecret("storeFile", "GEODE_KEYSTORE")
val releaseStorePassword = releaseSecret("storePassword", "GEODE_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSecret("keyAlias", "GEODE_KEY_ALIAS")
val releaseKeyPassword = releaseSecret("keyPassword", "GEODE_KEY_PASSWORD")
val hasReleaseSigning =
    releaseStorePath != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null

android {
    namespace = "dev.geode"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.geode"
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "1.7.0"
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
        // Geode ships a single locale and its own GL assets; splitting by
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

// --- 16 KB page-size gate on the packaged artifact ----------------------------
//
// Android 15 ships devices with 16 KB memory pages, and a library laid out for
// 4 KB pages will not load there. native-libs.yml verifies what it builds; this
// verifies what actually got packaged, which is the part that ships. Wired to
// the release outputs rather than to `check`, because a debug build on a 4 KB
// emulator is still a legitimate thing to produce while the rebuild is pending.
val checkNativePageAlignment =
    tasks.register("checkNativePageAlignment") {
        description = "Fails if a packaged .so is not 16 KB page aligned."
        val outputs = layout.buildDirectory.dir("outputs")
        doLast {
            val archives =
                outputs
                    .get()
                    .asFile
                    .walkTopDown()
                    .filter { it.isFile && (it.extension == "apk" || it.extension == "aab") }
                    .toList()
            if (archives.isEmpty()) return@doLast
            val bad = mutableListOf<String>()
            for (archive in archives) {
                ZipFile(archive).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { it.name.endsWith(".so") }
                        .forEach { entry ->
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            val align = maxLoadAlignment(bytes)
                            if (align in 1 until 16384) bad += "${archive.name}!${entry.name} aligned to $align"
                        }
                }
            }
            if (bad.isNotEmpty()) {
                throw GradleException(
                    "16 KB page-size check failed — rebuild through .github/workflows/native-libs.yml:\n" +
                        bad.joinToString("\n"),
                )
            }
        }
    }

/** Largest `p_align` across the ELF64 PT_LOAD segments, or 0 if not an ELF64. */
fun maxLoadAlignment(bytes: ByteArray): Long {
    if (bytes.size < 0x40 || bytes[0] != 0x7F.toByte() || bytes[4].toInt() != 2) return 0
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val phoff = buf.getLong(0x20)
    val phentsize = buf.getShort(0x36).toInt()
    val phnum = buf.getShort(0x38).toInt()
    var max = 0L
    for (i in 0 until phnum) {
        val at = (phoff + i.toLong() * phentsize).toInt()
        if (at + 0x38 > bytes.size) return max
        if (buf.getInt(at) == 1) max = maxOf(max, buf.getLong(at + 0x30))
    }
    return max
}

listOf("assembleRelease", "bundleRelease").forEach { name ->
    tasks.matching { it.name == name }.configureEach { finalizedBy(checkNativePageAlignment) }
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
    // The only engine edge :app is allowed (§4.1). Empty today; the seams move
    // across it one slice at a time.
    implementation(project(":engine:runtime"))

    robolectricSdk34(libs.android.all.sdk34)
    robolectricSdk35(libs.android.all.sdk35)

    implementation(libs.core.splashscreen)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    // Background playback: the MediaSession is what the lock screen, the
    // notification transport, headset and Bluetooth buttons all talk to, and
    // MediaSessionService is what keeps the player alive with no Activity.
    implementation(libs.media3.session)
    // Export Studio: trim, effects and re-encode over the same MediaCodec
    // stack the visualizer exporter already uses.
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
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
    // Virtual time for the suspend loops the UI drives (the Player's spectrum
    // sampler): a test that has to wait out real delays is a test that either
    // takes seconds or is flaky about how many ticks it saw.
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for plain (non-Robolectric) unit tests: the mockable
    // android.jar's org.json classes throw "Stub!" (TrackLibraryMigrationTest).
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.junit.ktx)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.ui.test.junit4)
}
