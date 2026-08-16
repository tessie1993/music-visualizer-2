plugins {
    id("musicviz.android-library")
}

android {
    namespace = "dev.musicviz.engine.gl"
}

dependencies {
    api(project(":engine:visual-core"))
}
