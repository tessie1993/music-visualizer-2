plugins {
    id("musicviz.android-library")
}

android {
    namespace = "dev.musicviz.engine.audioandroid"
}

dependencies {
    api(project(":engine:audio-core"))
}
