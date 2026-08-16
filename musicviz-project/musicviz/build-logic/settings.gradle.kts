// The build-logic build resolves plugins and the version catalog the same way
// the main build does, so a convention plugin can depend on the AGP and Kotlin
// versions the app already pins rather than a second set that drifts.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "build-logic"
