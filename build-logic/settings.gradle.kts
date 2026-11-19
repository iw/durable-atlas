// `durable-atlas.repositories`, when set, replaces the public repositories for plugins and modules alike:
// see the settings of the main build.
pluginManagement {
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
    repositories {
        val mirrors = providers.gradleProperty("durable-atlas.repositories").orNull
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (mirrors.isNotEmpty()) {
            mirrors.forEach { url ->
                maven(url) { isAllowInsecureProtocol = url.startsWith("http:", ignoreCase = true) }
            }
        } else {
            mavenCentral()
            gradlePluginPortal()
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
