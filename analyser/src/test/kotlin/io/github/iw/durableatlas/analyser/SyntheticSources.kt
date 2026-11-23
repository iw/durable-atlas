package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.modelextractor.ExtractedModel
import io.github.iw.durableatlas.modelextractor.IndexedModule
import io.github.iw.durableatlas.modelextractor.ModelIndex
import io.github.iw.durableatlas.modelextractor.ModelJson
import io.github.iw.durableatlas.modelextractor.ModelLayout
import io.github.iw.durableatlas.modelextractor.ModuleManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.test.fail

/**
 * A model written by hand: Kotlin files in one module, or in several that depend on each other,
 * and Java files beside them where a test wants some, with the classpath of a degraded model:
 * the Temporal SDK, its Kotlin extensions and the Kotlin standard library. For tests of cases the sample project does not contain.
 */
class SyntheticSources(private val directory: Path) {
    private val modules = LinkedHashMap<String, List<String>>()
    private val resources = LinkedHashMap<String, MutableList<String>>()

    /** Whether the manifests list the resource files, as a model made before they were exported does not. */
    var listResources: Boolean = true

    /** Jars the modules have besides those of a degraded model, as a model made from the build has a repository's libraries. */
    var libraries: List<Path> = emptyList()

    /** Declares a module and the modules it depends on. Files go under `<name>/src/main/kotlin`. */
    fun module(path: String, vararg dependencies: String): SyntheticSources {
        modules[path] = dependencies.toList()
        return this
    }

    /** Writes a Kotlin file of [module]. The first line of [text] should be its package. */
    fun file(module: String, name: String, text: String): SyntheticSources {
        if (module !in modules) module(module)
        val file = sourceDirectory(module).resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
        return this
    }

    /** Writes a Java file of [module], under `<name>/src/main/java`. */
    fun javaFile(module: String, name: String, text: String): SyntheticSources {
        if (module !in modules) module(module)
        val file = sourceDirectory(module, "java").resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
        return this
    }

    /** Writes a file of the main resources of [module], under `<name>/src/main/resources`, such as its `application.yaml`. */
    fun resource(module: String, name: String, text: String): SyntheticSources {
        if (module !in modules) module(module)
        val file = sourceDirectory(module, "resources").resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, text.trimIndent() + "\n")
        resources.getOrPut(module) { ArrayList() } += directory.resolve(ModelLayout.SOURCE_DIRECTORY).relativize(file).joinToString("/")
        return this
    }

    fun model(): ExtractedModel {
        val names = ModelLayout.manifestNames(modules.keys)
        for ((path, dependencies) in modules) {
            val roots = listOf(sourceDirectory(path), sourceDirectory(path, "java")).filter { it == sourceDirectory(path) || it.isDirectory() }
            val manifest = ModuleManifest(
                commit = FIXED_COMMIT,
                path = path,
                sources = roots.map { directory.resolve(ModelLayout.SOURCE_DIRECTORY).relativize(it).joinToString("/") },
                projectDeps = dependencies.sorted(),
                jars = (classpath() + libraries).map { it.toString() },
                mode = ModelMode.DEGRADED,
                resources = if (listResources) resources[path].orEmpty().sorted() else emptyList(),
            )
            val file = directory.resolve(names.getValue(path))
            Files.createDirectories(file.parent)
            Files.writeString(file, ModelJson.encode(manifest))
        }
        val index = ModelIndex(
            commit = FIXED_COMMIT,
            mode = ModelMode.DEGRADED,
            modules = modules.keys.sorted().map { IndexedModule(it, names.getValue(it)) },
            dropped = emptyList(),
        )
        Files.writeString(directory.resolve(ModelLayout.INDEX_FILE), ModelJson.encode(index))
        return ExtractedModel.read(directory)
    }

    fun open(log: (String) -> Unit = {}): SourceBackend = SourceBackends.open(model(), log = log)

    private fun sourceDirectory(module: String, language: String = "kotlin"): Path =
        directory.resolve(ModelLayout.SOURCE_DIRECTORY)
            .resolve(module.trim(':').replace(':', '/').ifEmpty { "root" })
            .resolve("src/main/$language")

    companion object {
        /** The jars of a degraded model, which the build names in a system property. */
        fun classpath(): List<Path> {
            val configured = System.getProperty("durable-atlas.degradedClasspath")
                ?: fail("System property durable-atlas.degradedClasspath is not set")
            return configured.split(File.pathSeparator).filter { it.isNotBlank() }.map { Path.of(it) }.flatMap { entry ->
                if (entry.isDirectory()) entry.listDirectoryEntries().filter { it.extension == "jar" }.sorted() else listOf(entry)
            }
        }
    }
}
