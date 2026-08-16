plugins {
    id("musicviz.jvm-library")
}

dependencies {
    // §4.1: visual-core may depend on the audio feature ABI types, and on
    // nothing else. It describes what to draw, not how to draw it.
    api(project(":engine:audio-core"))
}
