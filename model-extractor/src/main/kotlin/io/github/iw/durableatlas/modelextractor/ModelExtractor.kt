package io.github.iw.durableatlas.modelextractor

import io.github.iw.durableatlas.ir.ModelMode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

/**
 * Extracts the model of one commit of a repository: a manifest per module worth analysing, an
 * index, and a copy of those modules' sources.
 *
 * The repository is never written to. Its Gradle build runs in a clone made in a scratch
 * directory, and the sources in the model are exported from the commit by git, so the model does
 * not depend on the scratch directory or on the state of the repository's working tree.
 */
class ModelExtractor(private val log: (String) -> Unit = {}) {
    /**
     * Returns the model for [request], extracting it unless one exists.
     *
     * @throws GradleExtractionException if Gradle mode was asked for and the build could not be queried
     * @throws ModelExtractionException if no model could be produced
     */
    fun extract(request: ModelRequest): ModelResult {
        val repository = SourceRepository.open(request.repository)
        val commit = repository.resolveCommit(request.commit)
        val out = request.out.toAbsolutePath().normalize()
        val modelDirectory = ModelLayout.modelDirectory(out, commit)

        if (!request.force) {
            existingModel(modelDirectory, commit, request.mode)?.let { index ->
                log("a ${index.mode.label} model for $commit exists at $modelDirectory; nothing to extract")
                return ModelResult(commit, index.mode, modelDirectory, index, cached = true, null, null, null)
            }
        }

        Files.createDirectories(out)
        val scratch = createScratch(request.scratchParent)
        try {
            val builder = ModelBuilder(repository, commit, scratch, log)
            var gradleFailure: String? = null
            var gradleTasks: List<String>? = null
            fun fromGradle(): ModelIndex =
                publish(out, modelDirectory) { target ->
                    builder.fromGradle(target).also { gradleTasks = it.tasks }.index
                }

            fun degraded(): ModelIndex = publish(out, modelDirectory, builder::degraded)

            val index = when (request.mode) {
                ModelMode.GRADLE -> fromGradle()
                ModelMode.DEGRADED -> degraded()
                null -> try {
                    fromGradle()
                } catch (e: GradleExtractionException) {
                    gradleFailure = e.message ?: "Gradle extraction failed"
                    log("Gradle extraction failed: $gradleFailure")
                    log("building a degraded model instead")
                    degraded()
                }
            }
            log("wrote a ${index.mode.label} model of ${index.modules.size} module(s) to $modelDirectory")
            return ModelResult(
                commit, index.mode, modelDirectory, index, cached = false, gradleFailure, gradleTasks,
                scratch.takeIf { request.keepScratch },
            )
        } finally {
            if (request.keepScratch) log("kept the scratch directory $scratch") else removeScratch(scratch)
        }
    }

    /** Failing to tidy up must not turn a finished extraction into a failed one. */
    private fun removeScratch(scratch: Path) {
        try {
            Directories.deleteRecursively(scratch)
        } catch (e: IOException) {
            log("warning: could not remove the scratch directory $scratch: ${e.message}")
        }
    }

    private fun existingModel(directory: Path, commit: String, mode: ModelMode?): ModelIndex? {
        val file = directory.resolve(ModelLayout.INDEX_FILE)
        if (!file.isRegularFile()) return null
        val index = try {
            ModelJson.decodeIndex(Files.readString(file))
        } catch (e: ModelFormatException) {
            log("ignoring the model at $directory: ${e.message}")
            return null
        }
        return index.takeIf { it.commit == commit && (mode == null || it.mode == mode) }
    }

    /**
     * Builds a model in a staging directory next to its final location and moves it there in one
     * step, so a model directory with an index is always complete.
     */
    private fun publish(out: Path, modelDirectory: Path, build: (Path) -> ModelIndex): ModelIndex {
        val staging = Files.createTempDirectory(out, ".staging-")
        try {
            val index = build(staging)
            Directories.deleteRecursively(modelDirectory)
            Files.move(staging, modelDirectory, StandardCopyOption.ATOMIC_MOVE)
            return index
        } finally {
            Directories.deleteRecursively(staging)
        }
    }

    private fun createScratch(parent: Path?): Path {
        val directory = if (parent != null) {
            Files.createDirectories(parent)
            Files.createTempDirectory(parent, SCRATCH_PREFIX)
        } else {
            Files.createTempDirectory(SCRATCH_PREFIX)
        }
        // Gradle reports canonical paths; the scratch path must compare equal to them.
        return directory.toRealPath()
    }

    private val ModelMode.label: String get() = name.lowercase()

    private companion object {
        const val SCRATCH_PREFIX = "durable-atlas-scratch-"
    }
}
