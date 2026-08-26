plugins {
    id("synesthesia.android.library")
}

android {
    namespace = "dev.synesthesia.core.billing"
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
}
