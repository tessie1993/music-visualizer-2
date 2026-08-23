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
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    buildUponDefaultConfig = true
}

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
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkReleaseBuilds = true
        abortOnError = true
        fatal += "HardcodedText"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

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
    implementation(project(":engine:runtime"))

    implementation(libs.core.splashscreen)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.documentfile)
    implementation(libs.jtransforms)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.adaptive)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}
