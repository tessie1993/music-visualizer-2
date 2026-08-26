plugins {
    id("synesthesia.android.library")
}

android {
    namespace = "dev.synesthesia.core.export"
}

dependencies {
    implementation(project(":core:billing"))
}
