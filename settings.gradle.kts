rootProject.name = "quizacrowd"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    "common",
    "services:quiz-api",
    "services:ingest",
    "services:scorer",
    "services:gateway",
)
