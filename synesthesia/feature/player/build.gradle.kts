plugins {
    id("synesthesia.android.library")
}

android {
    namespace = "dev.synesthesia.feature.player"
}

dependencies {
    implementation(project(":core:audio"))
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
}

