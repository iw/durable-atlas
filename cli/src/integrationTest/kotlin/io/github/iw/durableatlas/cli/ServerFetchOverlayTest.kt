package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.analyser.AnalysisRequest
import io.github.iw.durableatlas.analyser.Analyser
import io.github.iw.durableatlas.analyser.testing.SampleModels
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fetches the executions of the sample repository's workflows from a real Temporal server and
 * lays them over the IR, by commit. It is no part of the build: `./gradlew :cli:serverTest` runs
 * it, against the server at `-Ddurable-atlas.server=<host:port>` (by default `127.0.0.1:7233`),
 * namespace `default`.
 *
 * The server has to hold what the sample repository's `runOnServer` task leaves there when it is
 * given two build ids, the first round with the executions that wait for timers:
 *
 * ```
 * ./gradlew :workflows:runOnServer --args="--build-id build-a --build-id build-b"
 * ```
 *
 * Whatever else the server holds of these workflow types is fetched as well; executions of other
 * worker versions have to be left out by the version map.
 */
@Tag("server")
class ServerFetchOverlayTest {
    @TempDir
    lateinit var dir: Path

    private val server: String = System.getProperty("durable-atlas.server") ?: "127.0.0.1:7233"
    private val saved: Path = Path.of(System.getProperty("durable-atlas.fixtureDir")).resolve("histories")

    private val deployment = "sample-monorepo"

    /** The commit the sample repository is analysed at, which stands for the build `build-a`. */
    private val first: String = SampleModels.degraded.index.commit

    /** Both builds ran the same sources; the second commit is the same IR under another name. */
    private val second = "b".repeat(40)

    /** The workflows that call an activity where the analysis cannot follow: one event without a node each. */
    private val hardCases = setOf("ComputedActivityNameWorkflow", "DynamicDispatchWorkflow", "DeepHelperChainWorkflow")

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.list(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    /** What an execution did to the graph of its workflow type, without what tells one run from another. */
    private data class Path_(
        val status: String,
        val closedAt: List<String>,
        val nodes: Map<String, Int>,
        val edges: Map<String, Int>,
        val handlers: Map<String, Int>,
        val unmapped: List<String>,
        val uncertain: Int,
    )

    private fun path(document: IrDocument, history: Path): Path_ {
        val trace = ExecutionMapper(document).map(HistoryJson.read(history))
        return Path_(
            status = trace.status.text,
            closedAt = trace.closedAt,
            nodes = trace.nodes.mapValues { it.value.count }.toSortedMap(),
            edges = trace.edges.entries.associate { (edge, hits) -> "${edge.from} -> ${edge.to} [${edge.kind} ${edge.label}]" to hits.count }.toSortedMap(),
            handlers = trace.entryPoints.mapValues { it.value.count }.toSortedMap(),
            unmapped = trace.unmapped.map { "${it.eventType} ${it.name}" },
            uncertain = trace.ambiguous.size,
        )
    }

    /** The saved history that takes the path an execution on the server was started to take, if there is one. */
    private fun savedFor(workflowType: String, workflowId: String, history: Path): String? =
        when {
            workflowId == "order-1001" -> "OrderWorkflow"
            workflowId == "order-1002" -> "OrderWorkflow.payment-timeout"
            workflowId == "order-1003" -> "OrderWorkflow.payment-declined"
            workflowType == "OrderWorkflow" -> null
            // Only the first run of a chain is saved; the runs that follow take other branches.
            workflowType == "ContinueAsNewWorkflow" ->
                "ContinueAsNewWorkflow".takeIf {
                    val started = Json.parseToJsonElement(Files.readString(history)).jsonObject.list("events").first()
                    "continuedExecutionRunId" !in started.getValue("workflowExecutionStartedEventAttributes").jsonObject
                }
            else -> workflowType
        }

    @Test
    fun `executions fetched from the server are laid over the IR of the commit that ran them, as the saved histories are`() {
        // The IR of the sample repository, as the IR of two commits.
        val analysed = Analyser().analyse(AnalysisRequest(SampleModels.degraded.directory, dir.resolve("analysed")))
        assertEquals(emptyList(), analysed.failures)
        val documents = analysed.workflows.associate { it.document.workflowType to it.document }
        val ir = dir.resolve("ir")
        for (commit in listOf(first, second)) {
            val directory = Files.createDirectories(ir.resolve(commit))
            for (workflow in analysed.workflows) {
                Files.writeString(directory.resolve(workflow.file.name), IrJson.encode(workflow.document.copy(commit = commit)))
            }
        }
        val versions = Files.writeString(
            dir.resolve("versions.json"),
            """
            {
              "schemaVersion": 1,
              "versions": [
                { "deployment": "$deployment", "buildId": "build-a", "commit": "$first" },
                { "deployment": "$deployment", "buildId": "build-b", "commit": "$second" }
              ]
            }
            """.trimIndent(),
        )

        // Fetch every execution of every workflow type.
        val histories = dir.resolve("histories")
        val environment = mapOf("TEMPORAL_CONFIG_FILE" to dir.resolve("temporal.toml").toString())
        val fetched = ArrayList<JsonObject>()
        for (workflowType in documents.keys) {
            val result = durableAtlas(environment).test(
                listOf("fetch", "--workflow-type", workflowType, "--limit", "500", "--out", histories.toString(), "--address", server, "--json"),
            )
            assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
            val summary = Json.parseToJsonElement(result.stdout).jsonObject
            assertEquals("WorkflowType = '$workflowType'", summary.text("query"))
            assertEquals(false, summary.getValue("more").jsonPrimitive.content.toBoolean(), "more than 500 executions of $workflowType")
            fetched += summary.list("histories").map { JsonObject(it + ("workflowType" to summary.getValue("workflowType"))) }
        }
        assertEquals(fetched.map { Path.of(it.text("file")).name }.sorted(), histories.listDirectoryEntries().map { it.name }.sorted())

        fun buildOf(history: JsonObject): String? =
            history["workerVersion"]?.jsonObject?.takeIf { it.text("deployment") == deployment }?.text("buildId")
        val ours = fetched.filter { buildOf(it) in setOf("build-a", "build-b") }
        val byBuild = ours.groupBy { buildOf(it)!! }

        // Every path the saved histories take was run by workers of the first build; most of them by the second.
        val names = saved.listDirectoryEntries("*.json").map { it.name.removeSuffix(".json") }.sorted()
        val ranByFirst = byBuild.getValue("build-a").mapNotNull { savedFor(it.text("workflowType"), it.text("workflowId"), Path.of(it.text("file"))) }
        assertEquals(names, ranByFirst.distinct().sorted())
        val ranBySecond = byBuild.getValue("build-b").mapNotNull { savedFor(it.text("workflowType"), it.text("workflowId"), Path.of(it.text("file"))) }
        assertTrue(ranBySecond.distinct().size >= 25, ranBySecond.distinct().toString())

        // An execution takes the path of the saved history of its kind: the same hits on every node, edge and handler.
        val differences = ArrayList<String>()
        var compared = 0
        for (history in ours) {
            val workflowType = history.text("workflowType")
            val file = Path.of(history.text("file"))
            val name = savedFor(workflowType, history.text("workflowId"), file) ?: continue
            val expected = path(documents.getValue(workflowType), saved.resolve("$name.json"))
            val actual = path(documents.getValue(workflowType), file)
            assertTrue(expected.nodes.isNotEmpty() && expected.edges.isNotEmpty(), name)
            if (expected != actual) differences += "${file.name} differs from $name.json:\n  saved:   $expected\n  fetched: $actual"
            compared++
        }
        assertEquals(emptyList(), differences, differences.joinToString("\n"))
        assertTrue(compared >= names.size + 25, "$compared executions compared")

        // Lay everything over the IR, by commit.
        val out = dir.resolve("overlay")
        val overlaid = durableAtlas(environment).test(
            listOf(
                "overlay", "--ir", ir.toString(), "--history", histories.toString(), "--version-map", versions.toString(),
                "--out", out.toString(), "--json",
            ),
        )
        assertEquals(ExitCode.SUCCESS, overlaid.statusCode, overlaid.stderr)
        val summary = Json.parseToJsonElement(overlaid.stdout).jsonObject
        assertEquals(listOf(first, second), summary.list("commits").map { it.text("commit") })
        assertEquals(
            listOf(listOf("build-a"), listOf("build-b")),
            summary.list("commits").map { commit -> commit.list("workerVersions").map { it.text("buildId") } },
        )

        // Each history is under the commit of the build that ran it last.
        val commitOf = HashMap<String, String>()
        for (workflow in summary.list("workflows")) {
            val commit = workflow.text("commit")
            assertEquals(commit, IrJson.decode(Files.readString(ir.resolve(workflow.text("ir")))).commit)
            for (history in workflow.list("histories")) {
                commitOf[history.text("history")] = commit
                assertTrue(history.list("files").all { Files.isRegularFile(Path.of(it.text("file"))) && Path.of(it.text("file")).startsWith(out.resolve(commit)) })
            }
            // No event without a node, but for the one step each of the three workflows hides from the analysis.
            val unmapped = workflow.getValue("events").jsonObject.getValue("unmapped").jsonPrimitive.int
            val expected = if (workflow.text("workflowType") in hardCases) workflow.getValue("executions").jsonPrimitive.int else 0
            assertEquals(expected, unmapped, "${workflow.text("workflowType")} at $commit")
        }
        assertEquals(ours.map { Path.of(it.text("file")).name }.sorted(), commitOf.keys.sorted())
        for (history in ours) {
            assertEquals(if (buildOf(history) == "build-a") first else second, commitOf.getValue(Path.of(history.text("file")).name), history.text("file"))
        }

        // The approval that was asked for under the first build and given under the second belongs to the second.
        val handedOver = ours.filter { it.text("workflowId").startsWith("ApprovalWorkflow-handover-") }
        assertTrue(handedOver.isNotEmpty())
        for (history in handedOver) {
            assertEquals(listOf("build-a", "build-b"), history.list("workerVersions").map { it.text("buildId") })
            assertEquals(second, commitOf.getValue(Path.of(history.text("file")).name))
        }

        // Everything else on the server is of a version the map does not know, and is left out with that reason.
        val others = fetched - ours.toSet()
        assertEquals(others.map { Path.of(it.text("file")).name }.sorted(), summary.list("unmatched").map { it.text("history") }.sorted())
        for (unmatched in summary.list("unmatched")) {
            assertTrue(
                unmatched.text("reason").endsWith("is not in the version map") || unmatched.text("reason").startsWith("the history names no worker version"),
                unmatched.text("reason"),
            )
        }
        println("server test: ${ours.size} execution(s) of ${byBuild.mapValues { it.value.size }} laid over two commits, ${others.size} of other versions left out")
    }
}
