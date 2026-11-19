package io.github.iw.durableatlas.analyser.testing

import io.github.iw.durableatlas.analyser.SourceBackends
import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.modelextractor.ExtractedModel
import io.github.iw.durableatlas.modelextractor.ModelExtractor
import io.github.iw.durableatlas.modelextractor.ModelRequest
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * Models of the sample project under `fixtures/`, for tests that analyse it. Each model is
 * extracted the first time it is asked for and then shared by every test in the JVM, so the
 * sample project's Gradle build runs at most once per test task. Both models are of the same commit.
 */
object SampleModels {
    private val directory: Path by lazy {
        val created = Files.createTempDirectory("durable-atlas-sample-models-")
        Runtime.getRuntime().addShutdownHook(Thread { deleteRecursively(created) })
        created
    }

    private val repository: SampleRepository by lazy { SampleRepository.create(directory.resolve("repository")) }

    /** Extracted without the sample project's build; needs git only. */
    val degraded: ExtractedModel by lazy { extract(ModelMode.DEGRADED) }

    /** Extracted through the sample project's own Gradle build. */
    val gradle: ExtractedModel by lazy { extract(ModelMode.GRADLE) }

    fun of(mode: ModelMode): ExtractedModel =
        when (mode) {
            ModelMode.GRADLE -> gradle
            ModelMode.DEGRADED -> degraded
        }

    private fun extract(mode: ModelMode): ExtractedModel {
        val name = mode.name.lowercase()
        val request = ModelRequest(
            repository = repository.project,
            commit = repository.head,
            // A directory per mode: models of one commit would otherwise replace each other.
            out = directory.resolve("models").resolve(name),
            mode = mode,
            scratchParent = directory.resolve("scratch").resolve(name),
        )
        return ExtractedModel.read(ModelExtractor().extract(request).directory)
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}

/**
 * The sample project opened for analysis, once per model and JVM. Tests share these backends and
 * must not close them.
 */
object SampleSources {
    val degraded: SourceBackend by lazy { SourceBackends.open(SampleModels.degraded) }

    val gradle: SourceBackend by lazy { SourceBackends.open(SampleModels.gradle) }

    fun of(mode: ModelMode): SourceBackend =
        when (mode) {
            ModelMode.GRADLE -> gradle
            ModelMode.DEGRADED -> degraded
        }
}

/**
 * Models of the demo project under `demo/`, extracted like those of the sample project: once per
 * test JVM, and both of the same commit. The demo is found next to the sample project's parent
 * directory, which is where the tool's repository keeps both.
 */
object DemoModels {
    /** The demo project as it is checked in. */
    val project: Path by lazy {
        val fixture = Path.of(
            System.getProperty("durable-atlas.fixtureDir") ?: error("System property durable-atlas.fixtureDir is not set"),
        )
        fixture.toAbsolutePath().normalize().parent.resolveSibling("demo").also {
            check(Files.isDirectory(it)) { "the demo project was not found at $it" }
        }
    }

    private val directory: Path by lazy {
        val created = Files.createTempDirectory("durable-atlas-demo-models-")
        Runtime.getRuntime().addShutdownHook(Thread { deleteRecursively(created) })
        created
    }

    private val repository: SampleRepository by lazy { SampleRepository.copyOf(project, directory.resolve("repository")) }

    /** Extracted without the demo's build; needs git only. */
    val degraded: ExtractedModel by lazy { extract(ModelMode.DEGRADED) }

    /** Extracted through the demo's own Gradle build. */
    val gradle: ExtractedModel by lazy { extract(ModelMode.GRADLE) }

    fun of(mode: ModelMode): ExtractedModel =
        when (mode) {
            ModelMode.GRADLE -> gradle
            ModelMode.DEGRADED -> degraded
        }

    private fun extract(mode: ModelMode): ExtractedModel {
        val name = mode.name.lowercase()
        val request = ModelRequest(
            repository = repository.project,
            commit = repository.head,
            out = directory.resolve("models").resolve(name),
            mode = mode,
            scratchParent = directory.resolve("scratch").resolve(name),
        )
        return ExtractedModel.read(ModelExtractor().extract(request).directory)
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }
}

/** The demo project opened for analysis, once per model and JVM. Tests share these backends and must not close them. */
object DemoSources {
    val degraded: SourceBackend by lazy { SourceBackends.open(DemoModels.degraded) }

    val gradle: SourceBackend by lazy { SourceBackends.open(DemoModels.gradle) }

    fun of(mode: ModelMode): SourceBackend =
        when (mode) {
            ModelMode.GRADLE -> gradle
            ModelMode.DEGRADED -> degraded
        }
}
