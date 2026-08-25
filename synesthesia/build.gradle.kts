// Root of the Synesthesia greenfield build.
// All plugin versions are pinned in gradle/libs.versions.toml and wired through build-logic.
plugins {
    alias(libs.plugins.ktlint) apply false
}
