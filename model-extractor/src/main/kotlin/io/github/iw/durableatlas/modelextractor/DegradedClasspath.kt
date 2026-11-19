package io.github.iw.durableatlas.modelextractor

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Finds the jars that stand in for a repository's compile classpath in degraded mode: the Temporal
 * SDK, its Kotlin extensions (`temporal-kotlin`), the Kotlin standard library, and what the API
 * of these needs.
 *
 * They are looked for in the directories and jars named by the system property
 * `durable-atlas.degradedClasspath` (separated like a class path), and otherwise in the `degraded`
 * directory next to the jar this class was loaded from, which is where the distribution keeps them.
 */
internal object DegradedClasspath {
    const val PROPERTY: String = "durable-atlas.degradedClasspath"
    const val DISTRIBUTION_DIRECTORY: String = "degraded"

    fun locate(): List<Path> {
        val configured = System.getProperty(PROPERTY)
        val jars = if (!configured.isNullOrBlank()) {
            configured.split(File.pathSeparator).filter { it.isNotBlank() }.flatMap { jarsAt(Path.of(it)) }
        } else {
            distributionDirectory()?.let { jarsAt(it) }.orEmpty()
        }
        if (jars.isEmpty()) {
            throw ModelExtractionException(
                "The degraded-mode classpath was not found. Run the tool from its distribution, or set -D$PROPERTY",
            )
        }
        return jars.map { it.toAbsolutePath().normalize() }.distinct()
    }

    private fun jarsAt(path: Path): List<Path> =
        when {
            path.isDirectory() -> path.listDirectoryEntries().filter { it.extension == "jar" }.sorted()
            Files.isRegularFile(path) -> listOf(path)
            else -> emptyList()
        }

    private fun distributionDirectory(): Path? {
        val location = DegradedClasspath::class.java.protectionDomain?.codeSource?.location ?: return null
        val jar = runCatching { Path.of(location.toURI()) }.getOrNull() ?: return null
        return jar.parent?.resolve(DISTRIBUTION_DIRECTORY)?.takeIf { it.isDirectory() }
    }
}
