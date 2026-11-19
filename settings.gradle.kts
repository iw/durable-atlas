pluginManagement {
    includeBuild("build-logic")
    // The plugins of build-logic bring the Kotlin Gradle plugins with them, which are resolved from the
    // plugin repositories of this build: the Gradle Plugin Portal, unless `durable-atlas.repositories`
    // (see below) names others.
    val mirrors = providers.gradleProperty("durable-atlas.repositories").orNull
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    if (mirrors.isNotEmpty()) {
        repositories {
            mirrors.forEach { url ->
                maven(url) { isAllowInsecureProtocol = url.startsWith("http:", ignoreCase = true) }
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        fun listed(property: String): List<String> = providers.gradleProperty(property).orNull
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

        // The Kotlin Analysis API and the IntelliJ platform modules it runs on are published outside Maven Central,
        // in two JetBrains repositories. Only those are asked for these modules, and they are asked for nothing
        // else. `durable-atlas.jetbrainsRepositories` names others to use in their place, separated by commas:
        // for example a repository manager's mirrors of the two.
        val analysisApi: InclusiveRepositoryContentDescriptor.() -> Unit = {
            includeModuleByRegex("org\\.jetbrains\\.kotlin", ".+-for-ide")
            includeGroup("com.intellij.platform")
            includeGroupByRegex("org\\.jetbrains\\.intellij\\.deps(\\..+)?")
        }
        val intellijPlatform: InclusiveRepositoryContentDescriptor.() -> Unit = {
            includeGroup("com.jetbrains.intellij.platform")
            includeGroup("com.jetbrains.intellij.java")
        }
        val jetbrainsMirrors = listed("durable-atlas.jetbrainsRepositories")
        if (jetbrainsMirrors.isNotEmpty()) {
            exclusiveContent {
                jetbrainsMirrors.forEach { url ->
                    forRepository {
                        maven(url) { isAllowInsecureProtocol = url.startsWith("http:", ignoreCase = true) }
                    }
                }
                filter {
                    analysisApi()
                    intellijPlatform()
                }
            }
        } else {
            exclusiveContent {
                forRepository {
                    maven("https://redirector.kotlinlang.org/maven/intellij-dependencies") {
                        name = "intellijDependencies"
                    }
                }
                filter(analysisApi)
            }
            exclusiveContent {
                forRepository {
                    maven("https://www.jetbrains.com/intellij-repository/releases") {
                        name = "intellijReleases"
                    }
                }
                filter(intellijPlatform)
            }
        }

        // On a machine that cannot reach Maven Central, `durable-atlas.repositories` names the repositories to
        // use in its place, separated by commas: usually the mirrors of a repository manager. Every other module
        // is then looked for in each of them, in the order given. build-logic/settings.gradle.kts reads it too.
        val mirrors = listed("durable-atlas.repositories")
        if (mirrors.isNotEmpty()) {
            mirrors.forEach { url ->
                maven(url) { isAllowInsecureProtocol = url.startsWith("http:", ignoreCase = true) }
            }
        } else {
            mavenCentral()
        }
    }
}

rootProject.name = "durable-atlas"

include(
    "ir",
    "model-extractor",
    "analyser",
    "render",
    "overlay",
    "cli",
)
