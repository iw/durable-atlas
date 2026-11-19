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
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `model` command driving the sample repository's own Gradle build. */
class ModelCommandGradleTest {
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
    fun `the default mode extracts the model through Gradle and leaves the repository alone`() {
        val before = TreeSnapshot.metadata(repository.root)

        val result = model("--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val summary = result.summary()
        assertEquals("gradle", summary.text("mode"))
        assertFalse(summary.flag("cached"))
        assertFalse(summary.flag("fallback"))
        assertEquals(
            listOf(":activities", ":helpers", ":nexus-api", ":workflows"),
            summary.getValue("modules").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf(":unrelated" to "no-temporal-usage"),
            summary.getValue("dropped").jsonArray.map { it.jsonObject.text("path") to it.jsonObject.text("reason") },
        )
        val directory = Path.of(summary.text("directory"))
        for (name in listOf("_activities", "_helpers", "_nexus-api", "_workflows")) {
            assertTrue(directory.resolve("modules/$name.json").isRegularFile(), name)
        }
        assertContains(result.stderr, "model: gradle: BUILD SUCCESSFUL")
        assertFalse("scratch" in summary)

        assertEquals(before, TreeSnapshot.metadata(repository.root))
        assertEquals("", repository.status())

        val again = model("--json")
        assertEquals(ExitCode.SUCCESS, again.statusCode)
        assertTrue(again.summary().flag("cached"))
        assertFalse("gradle: " in again.stderr)
    }

    @Test
    fun `a broken build exits with 1 in Gradle mode, 3 by default and 0 in degraded mode`() {
        val broken = repository.commit("Break the build") { project ->
            val settings = project.resolve("settings.gradle.kts")
            Files.writeString(settings, Files.readString(settings) + "\nthis is not a settings script\n")
        }

        val explicit = model("--mode", "gradle", "--json", commit = broken)
        assertEquals(ExitCode.FAILURE, explicit.statusCode)
        assertEquals("", explicit.stdout)
        assertContains(explicit.stderr, "model: failed: The Gradle wrapper exited with status")
        assertFalse(out.resolve(broken).exists())

        val fallback = model("--json", commit = broken)
        assertEquals(ExitCode.DEGRADED, fallback.statusCode, fallback.stderr)
        assertEquals("degraded", fallback.summary().text("mode"))
        assertTrue(fallback.summary().flag("fallback"))
        assertEquals(listOf(":"), fallback.summary().getValue("modules").jsonArray.map { it.jsonPrimitive.content })
        assertContains(fallback.stderr, "model: gradle: BUILD FAILED")
        assertContains(fallback.stderr, "model: completed in degraded mode")
        assertTrue(out.resolve(broken).resolve("modules/_.json").isRegularFile())

        val degraded = model("--mode", "degraded", "--force", "--json", commit = broken)
        assertEquals(ExitCode.SUCCESS, degraded.statusCode, degraded.stderr)
        assertEquals("degraded", degraded.summary().text("mode"))
        assertFalse(degraded.summary().flag("fallback"))
        assertFalse(degraded.summary().flag("cached"))
    }
}
