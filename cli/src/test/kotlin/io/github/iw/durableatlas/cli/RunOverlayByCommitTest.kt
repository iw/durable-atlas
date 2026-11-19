package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import io.github.iw.durableatlas.overlay.coverage.CoverageJson
import io.github.iw.durableatlas.overlay.testing.FakeTemporalServer
import io.temporal.api.history.v1.History
import io.temporal.common.WorkflowExecutionHistory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

/**
 * From a repository to coverage by commit with nothing moved by hand: `run` on two commits of the
 * sample repository into one directory, then `overlay --version-map` over the IR that is there.
 */
class RunOverlayByCommitTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("out")
    private val overlay: Path get() = dir.resolve("coverage")
    private val saved: Path = Path.of(System.getProperty("durable-atlas.fixtureDir")).resolve("histories")

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.list(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    /** A history the sample repository keeps, as workers of the deployment `orders` with [buildId] would have left it. */
    private fun ranBy(name: String, buildId: String): History =
        FakeTemporalServer.ranBy(WorkflowExecutionHistory.fromJson(Files.readString(saved.resolve("$name.json"))).history, "orders", buildId)

    @Test
    fun `histories are laid over the IR of their commit as two runs left it, with the commits abbreviated in the version map`() {
        val repository = SampleRepository.create(dir.resolve("repo"))
        val first = repository.head
        val removed = SampleCommits.REMOVED_WORKFLOW_TYPE
        val second = SampleCommits.withoutTimerWorkflow(repository)
        val (shortFirst, shortSecond) = listOf(first, second).map { it.take(7) }
        for (commit in listOf(first, shortSecond)) {
            val run = durableAtlas().test(
                listOf("run", "--repo", repository.project.toString(), "--commit", commit, "--out", out.toString(), "--mode", "degraded", "--format", "mermaid"),
            )
            assertEquals(ExitCode.SUCCESS, run.statusCode, run.stderr)
        }
        assertEquals(listOf(first, second).sorted(), out.resolve("ir").listDirectoryEntries().map { it.name }.sorted())

        // Build a ran the first commit and build b the second, which no longer has one of the workflow types.
        val histories = Files.createDirectories(dir.resolve("histories"))
        fun write(file: String, history: History) = Files.writeString(histories.resolve(file), WorkflowExecutionHistory(history).toJson(true))
        write("order-a.json", ranBy("OrderWorkflow", "build-a"))
        write("order-b.json", ranBy("OrderWorkflow.payment-declined", "build-b"))
        write("removed-a.json", ranBy(removed, "build-a"))
        write("removed-b.json", ranBy(removed, "build-b"))
        write("loop-x.json", ranBy("LoopWorkflow", "build-x"))
        Files.copy(saved.resolve("$removed.json"), histories.resolve("removed-plain.json"))
        val versions = Files.writeString(
            dir.resolve("versions.json"),
            """
            {
              "schemaVersion": 1,
              "versions": [
                { "deployment": "orders", "buildId": "build-a", "commit": "$shortFirst" },
                { "deployment": "orders", "buildId": "build-b", "commit": "$shortSecond" }
              ],
              "unversioned": "$shortFirst"
            }
            """.trimIndent(),
        )

        val result = durableAtlas().test(
            listOf(
                "overlay", "--version-map", versions.toString(), "--ir", out.resolve("ir").toString(), "--history", histories.toString(),
                "--out", overlay.toString(), "--format", "mermaid", "--json",
            ),
        )

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val summary = Json.parseToJsonElement(result.stdout).jsonObject

        // The commits are reported, and their directories named, by their full SHA.
        val commits = summary.list("commits").associateBy { it.text("commit") }
        assertEquals(setOf(first, second), commits.keys)
        assertEquals(listOf(first, second, "index.md").sorted(), overlay.listDirectoryEntries().map { it.name }.sorted())
        for (commit in listOf(first, second)) {
            assertEquals(overlay.resolve(commit).toRealPath(), Path.of(commits.getValue(commit).text("directory")).toRealPath())
            assertTrue(overlay.resolve(commit).resolve("index.md").isRegularFile())
            assertContains(Files.readString(overlay.resolve("index.md")), "`$commit`")
        }
        assertEquals(listOf("build-a"), commits.getValue(first).list("workerVersions").map { it.text("buildId") })
        assertEquals(1, commits.getValue(first).getValue("unversioned").jsonPrimitive.content.toInt())
        assertEquals(listOf("build-b"), commits.getValue(second).list("workerVersions").map { it.text("buildId") })

        // Every history is under the commit of its build, over the IR `run` wrote for that commit.
        val placed = summary.list("workflows").associate { workflow ->
            (workflow.text("commit") to workflow.text("workflowType")) to workflow.list("histories").map { it.text("history") }
        }
        assertEquals(
            mapOf(
                (first to "OrderWorkflow") to listOf("order-a.json"),
                (first to removed) to listOf("removed-a.json", "removed-plain.json"),
                (second to "OrderWorkflow") to listOf("order-b.json"),
            ),
            placed,
        )
        for (workflow in summary.list("workflows")) {
            val commit = workflow.text("commit")
            val type = workflow.text("workflowType")
            assertEquals("$commit/$type.ir.json", workflow.text("ir"))
            assertTrue(out.resolve("ir").resolve(workflow.text("ir")).isRegularFile())
            val coverage = Path.of(workflow.text("coverage"))
            assertEquals(overlay.resolve(commit).resolve("$type.coverage.json").toRealPath(), coverage.toRealPath())
            assertEquals(commit, CoverageJson.decode(Files.readString(coverage)).commit)
            assertEquals(0, workflow.getValue("events").jsonObject.getValue("unmapped").jsonPrimitive.content.toInt())
            for (history in workflow.list("histories")) {
                val file = Path.of(history.list("files").single().text("file"))
                assertEquals(overlay.resolve(commit).resolve("executions").toRealPath(), file.toRealPath().parent)
            }
        }

        // The second commit has no such workflow type, so the history its build left of one has nothing to lie over.
        assertEquals(
            listOf(
                "loop-x.json" to "worker version orders:build-x is not in the version map",
                "removed-b.json" to "no workflow document for this workflow type",
            ),
            summary.list("unmatched").map { it.text("history") to it.text("reason") },
        )
        assertContains(result.stderr, "overlay: commit $first: 3 execution(s), run by orders:build-a, no version\n")
        assertContains(result.stderr, "overlay: commit $second: 1 execution(s), run by orders:build-b\n")

        // Without the map nothing lies over the directory of commits, and the warning says what it holds.
        val ir = out.resolve("ir").toString()
        val plain = durableAtlas().test(listOf("overlay", "--ir", ir, "--history", histories.toString(), "--out", dir.resolve("plain").toString(), "--format", "mermaid"))
        assertEquals(ExitCode.SUCCESS, plain.statusCode, plain.stderr)
        assertContains(plain.stderr, "overlay: warning: $ir holds a directory of IR files per commit, as 'run' writes them: give --ir one of them, or give --version-map\n")
        val one = durableAtlas().test(listOf("overlay", "--ir", "$ir/$first", "--history", histories.toString(), "--out", dir.resolve("one").toString(), "--format", "mermaid"))
        assertEquals(ExitCode.SUCCESS, one.statusCode, one.stderr)
        assertFalse("holds a directory of IR files per commit" in one.stderr)
    }
}
