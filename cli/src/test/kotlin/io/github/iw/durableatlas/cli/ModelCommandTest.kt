package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import io.github.iw.durableatlas.modelextractor.testing.TreeSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `model` command in the cases that need git but not the sample repository's Gradle build. */
class ModelCommandTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("out")
    private val repository: SampleRepository by lazy { SampleRepository.create(dir.resolve("repo")) }

    private fun model(vararg arguments: String, commit: String = repository.head): CliktCommandTestResult {
        val repo = repository.project.toString()
        return durableAtlas().test(listOf("model", "--repo", repo, "--commit", commit, "--out", out.toString()) + arguments)
    }

    private fun CliktCommandTestResult.summary(): JsonObject = Json.parseToJsonElement(stdout).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    @Test
    fun `degraded mode succeeds and prints a JSON summary on stdout, logs on stderr`() {
        val before = TreeSnapshot.metadata(repository.root)

        val result = model("--mode", "degraded", "--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val summary = result.summary()
        assertEquals(
            listOf("command", "commit", "mode", "cached", "fallback", "directory", "modules", "dropped"),
            summary.keys.toList(),
        )
        assertEquals("model", summary.text("command"))
        assertEquals(repository.head, summary.text("commit"))
        assertEquals("degraded", summary.text("mode"))
        assertFalse(summary.flag("cached"))
        assertFalse(summary.flag("fallback"))
        assertEquals(listOf(":"), summary.getValue("modules").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, summary.getValue("dropped").jsonArray.size)
        val directory = Path.of(summary.text("directory"))
        assertEquals(out.toRealPath().resolve(repository.head), directory.toRealPath())
        assertTrue(directory.resolve("index.json").isRegularFile())
        assertTrue(directory.resolve("modules/_.json").isRegularFile())
        assertTrue(directory.resolve("source/workflows/src/main/kotlin").isDirectory())

        assertTrue(result.stderr.lines().filter { it.isNotEmpty() }.all { it.startsWith("model: ") }, result.stderr)
        assertContains(result.stderr, "wrote a degraded model")
        assertEquals(before, TreeSnapshot.metadata(repository.root))
        assertEquals("", repository.status())
    }

    @Test
    fun `without --json the summary is a sentence and the model directory`() {
        val result = model("--mode", "degraded")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val lines = result.stdout.lines()
        assertEquals("degraded model of ${repository.head}: 1 module(s) kept, 0 left out", lines[0])
        assertTrue(Path.of(lines[1]).resolve("index.json").isRegularFile())
    }

    @Test
    fun `an existing model is reported as cached, and --force extracts again`() {
        model("--mode", "degraded")

        val cached = model("--mode", "degraded", "--json")
        assertEquals(ExitCode.SUCCESS, cached.statusCode)
        assertTrue(cached.summary().flag("cached"))

        val forced = model("--mode", "degraded", "--force", "--json")
        assertEquals(ExitCode.SUCCESS, forced.statusCode)
        assertFalse(forced.summary().flag("cached"))
    }

    @Test
    fun `--keep-scratch reports where the scratch directory is`() {
        val result = model("--mode", "degraded", "--keep-scratch", "--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val scratch = Path.of(result.summary().text("scratch"))
        try {
            assertTrue(scratch.isDirectory())
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    @Test
    fun `when Gradle cannot be used the exit status depends on the mode asked for`() {
        val commit = repository.commit("Remove the wrapper") { project -> Files.delete(project.resolve("gradlew")) }

        val explicit = model("--mode", "gradle", "--json", commit = commit)
        assertEquals(ExitCode.FAILURE, explicit.statusCode)
        assertEquals("", explicit.stdout)
        assertContains(explicit.stderr, "model: failed: ")
        assertFalse(out.resolve(commit).exists())

        val fallback = model("--json", commit = commit)
        assertEquals(ExitCode.DEGRADED, fallback.statusCode, fallback.stderr)
        assertEquals("degraded", fallback.summary().text("mode"))
        assertTrue(fallback.summary().flag("fallback"))
        assertFalse(fallback.summary().flag("cached"))
        assertContains(fallback.stderr, "Gradle extraction failed")
        assertContains(fallback.stderr, "completed in degraded mode")
        assertTrue(out.resolve(commit).resolve("index.json").isRegularFile())

        // The degraded model is reused, and the run is still flagged.
        val again = model("--json", commit = commit)
        assertEquals(ExitCode.DEGRADED, again.statusCode)
        assertTrue(again.summary().flag("cached"))

        val degraded = model("--mode", "degraded", "--json", commit = commit)
        assertEquals(ExitCode.SUCCESS, degraded.statusCode)
        assertTrue(degraded.summary().flag("cached"))
    }

    @Test
    fun `an unknown commit or mode is a failure`() {
        val unknownCommit = model("--mode", "degraded", commit = "no-such-commit")
        assertEquals(ExitCode.FAILURE, unknownCommit.statusCode)
        assertEquals("", unknownCommit.stdout)
        assertContains(unknownCommit.stderr, "no-such-commit")

        val unknownMode = model("--mode", "fast")
        assertEquals(ExitCode.FAILURE, unknownMode.statusCode)
        assertContains(unknownMode.stderr, "--mode")
    }
}
