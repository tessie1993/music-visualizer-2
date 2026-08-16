plugins {
    id("musicviz.android-library")
}

android {
    namespace = "dev.musicviz.engine.scenes"
}

dependencies {
    api(project(":engine:gl"))
    api(project(":engine:audio-core"))
}
