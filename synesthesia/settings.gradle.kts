pluginManagement {
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

rootProject.name = "synesthesia"

include(":app")

include(
    ":core:common",
    ":core:audio",
    ":core:visualizer",
    ":core:database",
    ":core:billing",
    ":core:designsystem",
    ":core:navigation",
    ":core:export",
)

include(
    ":feature:player",
    ":feature:library",
    ":feature:visuals",
    ":feature:studio",
    ":feature:settings",
)
