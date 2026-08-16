plugins {
    id("musicviz.android-library")
}

android {
    namespace = "dev.musicviz.engine.runtime"
}

dependencies {
    // The composition root: it sees every engine module so that :app sees
    // none of them directly.
    api(project(":engine:scenes"))
    api(project(":engine:audio-android"))
}
