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
            // arm64-v8a is what phones run; x86_64 exists so emulators (the
            // CI instrumented suite included) can load libprojectM and
            // actually exercise MilkDrop - without it the engine probe fails
            // there and the whole pipeline is untestable off a phone.
            abiFilters += listOf("arm64-v8a", "x86_64")
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

dependencies {
    // The only engine edge :app is allowed (§4.1). Empty today; the seams move
    // across it one slice at a time.
    implementation(project(":engine:runtime"))

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
}
