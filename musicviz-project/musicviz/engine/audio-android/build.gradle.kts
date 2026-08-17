plugins {
    id("geode.android-library")
}

android {
    namespace = "dev.geode.engine.audioandroid"
}

dependencies {
    api(project(":engine:audio-core"))
    // §4.1: this is the module allowed to see Media3. The tap implements
    // TeeAudioProcessor.AudioBufferSink; C carries the PCM encoding constants.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
}
