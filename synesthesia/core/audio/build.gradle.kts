plugins {
    id("synesthesia.android.library")
}

android {
    namespace = "dev.synesthesia.core.audio"
}

dependencies {
    api(libs.media3.common)
}

