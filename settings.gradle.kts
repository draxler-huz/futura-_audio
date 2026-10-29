pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "WakeUpMusic"

// :app      Kotlin + Compose UI, alarm and player logic. Depends on :engine.
// :engine   C++ audio engine (Oboe, CMake). Owns every native library.
// :net      Placeholder. The only module allowed to contain network code. Empty at Stage 0.
include(":app")
include(":engine")
include(":net")
