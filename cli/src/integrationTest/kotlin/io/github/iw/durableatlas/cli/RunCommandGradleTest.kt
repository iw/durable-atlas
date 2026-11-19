package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.analyser.inventory.InventoryJson
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import io.github.iw.durableatlas.modelextractor.testing.TreeSnapshot
import io.github.iw.durableatlas.render.DiagramFormats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `run` command from end to end, driving the sample repository's own Gradle build. */
class RunCommandGradleTest {
    @TempDir
    lateinit var dir: Path

    private val repository: SampleRepository by lazy { SampleRepository.create(dir.resolve("repo")) }

    private fun run(out: Path, vararg arguments: String): CliktCommandTestResult {
        val repo = repository.project.toString()
        return durableAtlas().test(listOf("run", "--repo", repo, "--commit", repository.head, "--out", out.toString()) + arguments)
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun `a run draws every workflow type of the sample repository, the same way every time`() {
        val before = TreeSnapshot.metadata(repository.root)
        val out = dir.resolve("first")

        val result = run(out, "--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val summary = Json.parseToJsonElement(result.stdout).jsonObject
        assertEquals(repository.head, summary.text("commit"))
        assertEquals("gradle", summary.text("mode"))
        assertFalse(summary.getValue("fallback").jsonPrimitive.boolean)
        assertEquals(0, summary.getValue("exitCode").jsonPrimitive.int)
        assertContains(result.stderr, "run: gradle: BUILD SUCCESSFUL")

        // A diagram in every format for every workflow type the inventory lists.
        val inventory = InventoryJson.decode(Files.readString(out.resolve("ir").resolve(repository.head).resolve("inventory.json")))
        val workflowTypes = inventory.workflows.map { it.workflowType }
        assertEquals(30, workflowTypes.size)
        val workflows = summary.getValue("workflows").jsonArray.map { it.jsonObject }
        assertEquals(workflowTypes, workflows.map { it.text("workflowType") })
        val diagrams = out.resolve("diagrams").resolve(repository.head)
        val index = Files.readString(diagrams.resolve("index.md"))
        for (workflowType in workflowTypes) {
            for (format in DiagramFormats.all) {
                val file = diagrams.resolve("$workflowType.${format.extension}")
                assertTrue(file.isRegularFile(), file.toString())
                assertContains(index, "(${file.name})")
            }
        }
        assertEquals(30 * DiagramFormats.all.size + 1, diagrams.listDirectoryEntries().size)

        // The workflows without control flow are drawn in full, so their pages are pinned.
        val mermaid = DiagramFormats.MERMAID
        for (workflowType in STRAIGHT_LINE) {
            val page = Files.readString(diagrams.resolve("$workflowType.${mermaid.extension}"))
            assertContains(page, repository.head)
            // A sample repository gets a new commit id every time it is created.
            GoldenFiles.assertMatches("run/$workflowType.${mermaid.extension}", page.replace(repository.head, FIXED_COMMIT))
        }

        // A second run, into another directory and with the model extracted again, writes the same tree.
        val again = dir.resolve("second")
        assertEquals(ExitCode.SUCCESS, run(again).statusCode)
        assertEquals(TreeSnapshot.contents(out), TreeSnapshot.contents(again))
        // So does a run into the same directory, which finds the model already there.
        val contents = TreeSnapshot.contents(out)
        assertEquals(ExitCode.SUCCESS, run(out).statusCode)
        assertEquals(contents, TreeSnapshot.contents(out))

        assertEquals(before, TreeSnapshot.metadata(repository.root))
        assertEquals("", repository.status())
    }

    private companion object {
        const val FIXED_COMMIT = "0000000000000000000000000000000000000000"

        /** The workflows of the sample repository that have no control flow. */
        val STRAIGHT_LINE = listOf(
            "GreetingChildWorkflow",
            "LocalActivityWorkflow",
            "NexusOperationWorkflow",
            "ParentWorkflow",
            "SideEffectWorkflow",
            "SignalExternalWorkflow",
            "TimerWorkflow",
            "TypedActivityWorkflow",
            "UntypedActivityWorkflow",
        )
    }
}
