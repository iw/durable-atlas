package io.github.iw.durableatlas.modelextractor

import io.github.iw.durableatlas.ir.ModelMode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

internal class GradleModel(val index: ModelIndex, val tasks: List<String>)

/** Builds the files of a model for one commit into a directory, in either mode. */
internal class ModelBuilder(
    private val repository: SourceRepository,
    private val commit: String,
    private val scratch: Path,
    private val log: (String) -> Unit,
) {
    /** A module as Gradle described it, with paths inside the checkout made relative to its root. */
    private class Described(
        val path: String,
        val sources: List<String>,
        val projectDeps: List<String>,
        val jars: List<String>,
        /** The entries of [jars] that are files of the repository. */
        val repositoryJars: List<String>,
        /** The configuration files in the module's main resource directories. */
        val resources: List<String>,
    )

    /** Queries the commit's Gradle build in a scratch clone and writes the model to [target]. */
    fun fromGradle(target: Path): GradleModel {
        log("cloning $commit into the scratch directory $scratch")
        val root = repository.cloneAt(commit, scratch.resolve("checkout")).let {
            if (Files.isDirectory(it)) it.toRealPath() else it
        }
        val run = GradleModelRunner(log).run(root, scratch)

        val tracked = repository.directories(commit)
        val configuration = repository.files(commit, ModuleManifest.CONFIGURATION_FILES)
        val described = run.manifests.map { describe(it, root, tracked, configuration) }
        val usesTemporal = described
            .filter { module -> ModuleScoping.usesTemporal(module.sources.map(root::resolve)) }
            .map { it.path }
            .toSet()
        val scope = ModuleScoping.scope(described.associate { it.path to it.projectDeps }, usesTemporal)
        for (dropped in scope.dropped) log("leaving out ${dropped.path} (${dropped.reason})")

        val keptPaths = scope.kept.toSet()
        val kept = described.filter { it.path in keptPaths }
        val manifests = kept.map { module ->
            ModuleManifest(
                commit = commit,
                path = module.path,
                sources = module.sources,
                projectDeps = module.projectDeps.filter { it in keptPaths },
                jars = module.jars,
                mode = ModelMode.GRADLE,
                resources = module.resources,
            )
        }

        val sourceRoot = target.resolve(ModelLayout.SOURCE_DIRECTORY)
        repository.export(commit, kept.flatMap { it.sources + it.resources }.distinct().sorted(), sourceRoot, scratch)
        for (jar in kept.flatMap { it.repositoryJars }.distinct()) {
            val copy = sourceRoot.resolve(jar)
            if (!Files.exists(copy)) {
                Files.createDirectories(copy.parent)
                Files.copy(root.resolve(jar), copy)
            }
        }
        return GradleModel(write(target, ModelMode.GRADLE, manifests, scope.dropped), run.tasks)
    }

    /**
     * Writes a model with a single module made of every main source directory of the commit to
     * [target]: the directories a Kotlin module of a Gradle build compiles by convention,
     * `src/main/kotlin` and `src/main/java`, wherever they lie. Only git is needed for this; the
     * repository's build is not touched. The configuration files of the resource directory next to
     * each of them, `src/main/resources`, come with them.
     */
    fun degraded(target: Path): ModelIndex {
        val sources = degradedSources(repository.directories(commit))
        if (sources.isEmpty()) {
            throw ModelExtractionException(
                "Commit $commit has no ${DEGRADED_SOURCE_DIRECTORIES.joinToString(" or ")} directory",
            )
        }
        val counted = DEGRADED_SOURCE_DIRECTORIES.joinToString(", ") { kind -> "${sources.count { it == kind || it.endsWith("/$kind") }} $kind" }
        log("taking the sources of ${sources.size} main source director${if (sources.size == 1) "y" else "ies"} of $commit: $counted")
        val resources = degradedResources(sources, repository.files(commit, ModuleManifest.CONFIGURATION_FILES))
        val manifest = ModuleManifest(
            commit = commit,
            path = DEGRADED_MODULE_PATH,
            sources = sources,
            projectDeps = emptyList(),
            jars = DegradedClasspath.locate().map { it.toString() },
            mode = ModelMode.DEGRADED,
            resources = resources,
        )
        repository.export(commit, (sources + resources).sorted(), target.resolve(ModelLayout.SOURCE_DIRECTORY), scratch)
        return write(target, ModelMode.DEGRADED, listOf(manifest), emptyList())
    }

    /**
     * @param configuration the configuration files of the commit, relative to [root]
     */
    private fun describe(raw: RawManifest, root: Path, tracked: Set<String>, configuration: List<String>): Described {
        if (raw.unresolved.isNotEmpty()) {
            log("warning: ${raw.path} has unresolved dependencies: ${raw.unresolved.sorted().joinToString(", ")}")
        }
        val sources = raw.sources.mapNotNull { source ->
            val relative = relativeTo(root, source)
            val problem = when {
                relative == null -> "outside the repository"
                relative != "." && relative !in tracked -> "not part of the commit"
                else -> null
            }
            if (problem != null) log("warning: ${raw.path} has a source directory that is $problem: $source")
            relative.takeIf { problem == null }
        }
        val jars = mutableListOf<String>()
        val repositoryJars = mutableListOf<String>()
        for (jar in raw.jars) {
            val relative = relativeTo(root, jar)
            when {
                relative != null && root.resolve(relative).isRegularFile() -> {
                    jars += relative
                    repositoryJars += relative
                }
                // Anything else below the scratch directory disappears with it.
                relative != null || Path.of(jar).startsWith(scratch) ->
                    log("warning: ${raw.path} has a classpath entry that cannot be kept: $jar")
                else -> jars += jar
            }
        }
        // A resource directory outside the repository, or one the commit does not hold, has no file of the commit.
        val resourceDirectories = raw.resources.mapNotNull { relativeTo(root, it) }.toSet()
        return Described(
            path = raw.path,
            sources = sources.distinct().sorted(),
            projectDeps = raw.projectDeps.distinct().sorted(),
            jars = jars.distinct(),
            repositoryJars = repositoryJars,
            resources = configuration.filter { file -> file.substringBeforeLast('/', ".") in resourceDirectories }.sorted(),
        )
    }

    /** [path] relative to [root] with `/` separators, or `null` if it is not below [root]. */
    private fun relativeTo(root: Path, path: String): String? {
        val absolute = Path.of(path).let { if (Files.exists(it)) it.toRealPath() else it.toAbsolutePath().normalize() }
        if (!absolute.startsWith(root)) return null
        return root.relativize(absolute).joinToString("/").ifEmpty { "." }
    }

    private fun write(
        target: Path,
        mode: ModelMode,
        manifests: List<ModuleManifest>,
        dropped: List<DroppedModule>,
    ): ModelIndex {
        val sorted = manifests.sortedBy { it.path }
        val names = ModelLayout.manifestNames(sorted.map { it.path })
        Files.createDirectories(target.resolve(ModelLayout.MODULES_DIRECTORY))
        for (manifest in sorted) {
            Files.writeString(target.resolve(names.getValue(manifest.path)), ModelJson.encode(manifest))
        }
        val index = ModelIndex(
            commit = commit,
            mode = mode,
            modules = sorted.map { IndexedModule(it.path, names.getValue(it.path)) },
            dropped = dropped.sortedBy { it.path },
        )
        // The index is written last: its presence marks the model as complete.
        Files.writeString(target.resolve(ModelLayout.INDEX_FILE), ModelJson.encode(index))
        return index
    }

    companion object {
        /** Path of the single module of a degraded model; it stands for the whole repository. */
        const val DEGRADED_MODULE_PATH: String = ":"

        /**
         * Where the main sources of a module lie by convention. The Kotlin source set of a Gradle
         * module covers both, so a model made from the build has both, and a degraded one takes
         * both: Kotlin files are read wherever they are, and what Java files declare is known.
         */
        val DEGRADED_SOURCE_DIRECTORIES: List<String> = listOf("src/main/kotlin", "src/main/java")

        /**
         * The directories among [directories], given relative to the analysed tree, that a
         * degraded model takes its sources from, sorted. A directory inside another one that is
         * taken comes with that one and is not named again, so that no file is read twice.
         */
        internal fun degradedSources(directories: Set<String>): List<String> {
            val conventional = directories
                .filter { directory -> DEGRADED_SOURCE_DIRECTORIES.any { directory == it || directory.endsWith("/$it") } }
                .toSet()
            return conventional
                .filter { directory -> conventional.none { it != directory && directory.startsWith("$it/") } }
                .sorted()
        }

        /** The resource directory of a module by convention, beside [DEGRADED_SOURCE_DIRECTORIES]. */
        private const val RESOURCE_DIRECTORY: String = "src/main/resources"

        /**
         * The configuration files among [files] that a degraded model with the source directories
         * [sources] keeps: those of the resource directory next to each, `src/main/resources`
         * beside `src/main/kotlin`, sorted.
         */
        internal fun degradedResources(sources: List<String>, files: List<String>): List<String> {
            val directories = sources.mapNotNull { source ->
                DEGRADED_SOURCE_DIRECTORIES.firstOrNull { source == it || source.endsWith("/$it") }
                    ?.let { kind -> source.removeSuffix(kind) + RESOURCE_DIRECTORY }
            }.toSet()
            return files.filter { file -> file.substringBeforeLast('/', "") in directories }.sorted()
        }
    }
}
