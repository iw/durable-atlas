pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "sample-monorepo"

include(
    ":activities",
    ":helpers",
    ":nexus-api",
    ":unrelated",
    ":workflows",
)
