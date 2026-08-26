plugins {
    id("synesthesia.android.library")
}

android {
    namespace = "dev.synesthesia.core.visualizer"
}

dependencies {
    implementation(project(":core:audio"))
}
