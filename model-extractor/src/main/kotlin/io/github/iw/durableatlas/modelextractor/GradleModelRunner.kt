package io.github.iw.durableatlas.modelextractor

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** What the init script reports about one module, before the paths are made relative. */
@Serializable
internal data class RawManifest(
    val path: String,
    val sources: List<String>,
    val projectDeps: List<String>,
    val jars: List<String>,
    val unresolved: List<String> = emptyList(),
)

internal class GradleRun(
    /** One entry per Kotlin or Java module of the build, sorted by path. */
    val manifests: List<RawManifest>,
    /** Path of every task Gradle scheduled, in execution order. */
    val tasks: List<String>,
)

/** Runs a checkout's own Gradle wrapper with the model-dumping init script. */
internal class GradleModelRunner(private val log: (String) -> Unit) {
    /**
     * Queries the build in [projectDirectory]. Everything Gradle is asked to write goes to
     * [workDirectory]: the init script, the project cache and the raw manifests.
     */
    fun run(projectDirectory: Path, workDirectory: Path): GradleRun {
        val initScript = workDirectory.resolve("dump-model.init.gradle")
        Files.write(initScript, Resources.bytes(INIT_SCRIPT_RESOURCE))
        val rawDirectory = workDirectory.resolve("raw")

        val command = wrapperCommand(projectDirectory) + listOf(
            "--init-script", initScript.toString(),
            "--project-cache-dir", workDirectory.resolve("gradle-cache").toString(),
            "--no-configuration-cache",
            "--console=plain",
            "-PmodelOut=$rawDirectory",
            TASK_NAME,
        )
        log("running ${command.joinToString(" ")}")
        val result = try {
            Processes.run(command, projectDirectory) { line -> log("gradle: $line") }
        } catch (e: IOException) {
            throw GradleExtractionException("Could not start the Gradle wrapper: ${e.message}", e)
        }
        if (result.exitCode != 0) {
            throw GradleExtractionException("The Gradle wrapper exited with status ${result.exitCode}")
        }

        val tasks = readTasks(rawDirectory.resolve("tasks.txt"))
        val unexpected = tasks.filterNot { it.substringAfterLast(':') == TASK_NAME }
        if (unexpected.isNotEmpty()) {
            log("warning: the build also ran ${unexpected.joinToString(", ")}")
        }
        return GradleRun(readManifests(rawDirectory.resolve("modules")), tasks)
    }

    private fun wrapperCommand(projectDirectory: Path): List<String> {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val wrapper = projectDirectory.resolve(if (windows) "gradlew.bat" else "gradlew")
        if (!wrapper.isRegularFile()) {
            throw GradleExtractionException("The commit has no Gradle wrapper (${wrapper.fileName}) to run")
        }
        return when {
            windows -> listOf("cmd", "/c", wrapper.toString())
            Files.isExecutable(wrapper) -> listOf(wrapper.toString())
            else -> listOf("sh", wrapper.toString())
        }
    }

    private fun readTasks(file: Path): List<String> =
        if (file.isRegularFile()) Files.readAllLines(file).filter { it.isNotBlank() } else emptyList()

    private fun readManifests(directory: Path): List<RawManifest> {
        if (!Files.isDirectory(directory)) {
            throw GradleExtractionException("The build finished without describing any module")
        }
        return directory.listDirectoryEntries()
            .filter { it.extension == "json" }
            .map { file ->
                try {
                    json.decodeFromString(RawManifest.serializer(), Files.readString(file))
                } catch (e: SerializationException) {
                    throw GradleExtractionException("Unreadable module description ${file.fileName}: ${e.message}", e)
                }
            }
            .sortedBy { it.path }
    }

    companion object {
        const val TASK_NAME: String = "dumpAnalysisModel"
        const val INIT_SCRIPT_RESOURCE: String = "io/github/iw/durableatlas/modelextractor/dump-model.init.gradle"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
