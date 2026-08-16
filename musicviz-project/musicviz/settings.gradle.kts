pluginManagement {
    // Convention plugins live in an included build so they are compiled and
    // type-checked, not copied between module scripts.
    includeBuild("build-logic")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "musicviz"
include(":app")

// MASTER_PLAN §4.1. Empty at first by design: V2-1-02 creates the boundaries,
// and the code moves across them in later slices, one seam at a time.
include(
    ":engine:audio-core",
    ":engine:visual-core",
    ":engine:gl",
    ":engine:scenes",
    ":engine:audio-android",
    ":engine:runtime",
)
