plugins {
    id("geode.jvm-library")
}

dependencies {
    // The analysis graph's FFT (§5.3). Already an :app dependency at the same
    // version, so this is a module edge rather than a new library or a new
    // licence obligation - and JTransforms is pure JVM, so audio-core stays
    // Android-free. §V2-3-02's third bullet leaves the choice open by
    // benchmarking it; nothing here assumes this stays the implementation.
    implementation(libs.jtransforms)
}
