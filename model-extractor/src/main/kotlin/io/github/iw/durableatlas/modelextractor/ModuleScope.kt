package io.github.iw.durableatlas.modelextractor

import io.github.iw.durableatlas.modelextractor.DropReasons.NOT_A_JVM_MODULE
import io.github.iw.durableatlas.modelextractor.DropReasons.NO_TEMPORAL_USAGE
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** The outcome of deciding which modules are worth analysing. */
internal class ModuleScope(
    /** Paths of the modules to keep, sorted. */
    val kept: List<String>,
    /** Modules to leave out, sorted by path. */
    val dropped: List<DroppedModule>,
)

/**
 * Narrows a build down to the modules that matter for Temporal workflows: those that use
 * `io.temporal`, and every module they depend on, directly or through other modules.
 */
internal object ModuleScoping {
    private const val MARKER = "io.temporal"
    private val SOURCE_EXTENSIONS = setOf("kt", "java")

    /**
     * [dependencies] maps each described module to the modules on its compile classpath;
     * [usesTemporal] is the subset whose sources mention the SDK. Dependencies that are not
     * themselves described are reported as dropped when a kept module needs them.
     */
    fun scope(dependencies: Map<String, List<String>>, usesTemporal: Set<String>): ModuleScope {
        val reached = linkedSetOf<String>()
        val pending = ArrayDeque(usesTemporal.filter { it in dependencies })
        while (pending.isNotEmpty()) {
            val module = pending.removeFirst()
            if (reached.add(module)) pending.addAll(dependencies[module].orEmpty())
        }
        val kept = reached.filter { it in dependencies }.sorted()
        val dropped = dependencies.keys.filterNot { it in reached }.map { DroppedModule(it, NO_TEMPORAL_USAGE) } +
            reached.filterNot { it in dependencies }.map { DroppedModule(it, NOT_A_JVM_MODULE) }
        return ModuleScope(kept, dropped.sortedBy { it.path })
    }

    /** Whether any Kotlin or Java file under [sourceDirectories] mentions the Temporal SDK's packages. */
    fun usesTemporal(sourceDirectories: List<Path>): Boolean =
        sourceDirectories.any { directory ->
            Files.walk(directory).use { files ->
                files.anyMatch { file ->
                    file.extension in SOURCE_EXTENSIONS && file.isRegularFile() &&
                        Files.readAllBytes(file).toString(Charsets.ISO_8859_1).contains(MARKER)
                }
            }
        }
}
