plugins {
    id("geode.android-library")
}

android {
    namespace = "dev.geode.engine.gl"
}

dependencies {
    api(project(":engine:visual-core"))
}
