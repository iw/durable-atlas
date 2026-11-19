package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.overlay.coverage.CoverageJson
import io.github.iw.durableatlas.overlay.testing.FakeTemporalServer
import io.github.iw.durableatlas.render.DiagramFormats
import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.History
import io.temporal.common.WorkflowExecutionHistory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `overlay` command on the IR of the sample repository and the histories that repository
 * keeps of its workflows. Which formats exist is taken from the registry.
 */
class OverlayCommandTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("overlay")
    private val histories: Path = Path.of(System.getProperty("durable-atlas.fixtureDir")).resolve("histories")
    private val formats = DiagramFormats.all

    /** The three workflows whose history holds a step behind a call the analysis could not follow. */
    private val withUnmapped = listOf("ComputedActivityNameWorkflow", "DeepHelperChainWorkflow", "DynamicDispatchWorkflow")

    private fun overlay(
        vararg arguments: String,
        ir: Path = SampleIr.degraded,
        history: Path = histories,
        out: Path = this.out,
    ): CliktCommandTestResult =
        durableAtlas().test(listOf("overlay", "--ir", ir.toString(), "--history", history.toString(), "--out", out.toString()) + arguments)

    private fun CliktCommandTestResult.summary(): JsonObject = Json.parseToJsonElement(stdout).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.list(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    private fun String.onOneLine(): String = replace(Regex("\\s+"), " ")

    /** Every file under [directory], by its path inside it. */
    private fun contents(directory: Path = out): Map<String, String> =
        Files.walk(directory).use { files ->
            files.filter { it.isRegularFile() }.toList().associate { it.relativeTo(directory).toString() to Files.readString(it) }
        }.toSortedMap()

    /** A directory with some of the sample histories. */
    private fun some(vararg names: String): Path {
        val directory = Files.createDirectories(dir.resolve("histories"))
        names.forEach { Files.copy(histories.resolve(it), directory.resolve(it)) }
        return directory
    }

    @Test
    fun `histories are laid over the IR and the result is summarised as JSON on stdout, logs go to stderr`() {
        val result = overlay("--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val summary = result.summary()
        assertEquals(listOf("command", "directory", "index", "formats", "workflows", "unmatched", "skipped", "exitCode"), summary.keys.toList())
        assertEquals("overlay", summary.text("command"))
        assertEquals(out.toRealPath(), Path.of(summary.text("directory")).toRealPath())
        assertEquals(out.toRealPath().resolve("index.md"), Path.of(summary.text("index")).toRealPath())
        assertEquals(formats.map { it.name }, summary.getValue("formats").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, summary.getValue("unmatched").jsonArray.size)
        assertEquals(0, summary.getValue("skipped").jsonArray.size)
        assertEquals(0, summary.getValue("exitCode").jsonPrimitive.int)

        val workflows = summary.list("workflows")
        assertEquals(30, workflows.size)
        assertEquals(workflows.map { it.text("workflowType") }.sorted(), workflows.map { it.text("workflowType") })
        val written = mutableListOf(out.toRealPath().resolve("index.md"))
        for (workflow in workflows) {
            assertEquals(
                listOf(
                    "workflowType", "implementation", "ir", "executions", "nodes", "nodesHit", "events", "uncertain", "unmappedPct",
                    "overThreshold", "coverage", "diagram", "files", "histories",
                ),
                workflow.keys.toList(),
            )
            val type = workflow.text("workflowType")
            assertEquals("$type.ir.json", workflow.text("ir"))
            assertEquals(out.toRealPath().resolve("$type.coverage.json"), Path.of(workflow.text("coverage")).toRealPath())
            assertEquals(out.toRealPath().resolve("$type.svg"), Path.of(workflow.text("diagram")).toRealPath())
            assertEquals(formats.map { it.name }, workflow.list("files").map { it.text("format") })
            assertEquals(
                formats.map { out.toRealPath().resolve("$type.coverage.${it.extension}") },
                workflow.list("files").map { Path.of(it.text("file")).toRealPath() },
            )
            assertEquals(workflow.getValue("executions").jsonPrimitive.int, workflow.list("histories").size)
            written.add(Path.of(workflow.text("coverage")).toRealPath())
            written.add(Path.of(workflow.text("diagram")).toRealPath())
            written += workflow.list("files").map { Path.of(it.text("file")).toRealPath() }
            for (history in workflow.list("histories")) {
                assertEquals(type, history.text("history").substringBefore('.'))
                assertEquals(formats.map { it.name }, history.list("files").map { it.text("format") })
                written += history.list("files").map { Path.of(it.text("file")).toRealPath() }
            }
        }
        assertEquals(written.map { it.relativeTo(out.toRealPath()).toString() }.sorted(), contents().keys.toList())

        // What the summary says is what the coverage files hold.
        val order = workflows.single { it.text("workflowType") == "OrderWorkflow" }
        assertEquals(3, order.getValue("executions").jsonPrimitive.int)
        assertEquals(
            listOf("OrderWorkflow.json" to "completed", "OrderWorkflow.payment-declined.json" to "failed", "OrderWorkflow.payment-timeout.json" to "completed"),
            order.list("histories").map { it.text("history") to it.text("status") },
        )
        val coverage = CoverageJson.decode(Files.readString(Path.of(order.text("coverage"))))
        assertEquals(coverage.recordedNodes, order.getValue("nodes").jsonPrimitive.int)
        assertEquals(coverage.hitNodes, order.getValue("nodesHit").jsonPrimitive.int)
        assertEquals(28, coverage.hitNodes)
        assertEquals(56, order.getValue("events").jsonObject.getValue("mapped").jsonPrimitive.int)
        assertEquals(0, order.getValue("events").jsonObject.getValue("unmapped").jsonPrimitive.int)
        assertEquals(0.0, order.getValue("unmappedPct").jsonPrimitive.double)
        assertFalse(order.getValue("overThreshold").jsonPrimitive.boolean)
        assertEquals(out.toRealPath().resolve("executions/OrderWorkflow.payment-declined.svg"), Path.of(order.list("histories")[1].list("files")[0].text("file")).toRealPath())

        assertEquals(withUnmapped, workflows.filter { it.getValue("events").jsonObject.getValue("unmapped").jsonPrimitive.int > 0 }.map { it.text("workflowType") })

        assertTrue(result.stderr.lines().filter { it.isNotEmpty() }.all { it.startsWith("overlay: ") }, result.stderr)
        assertContains(result.stderr, "overlay: DynamicDispatchWorkflow: 1 of 3 events have no node\n")
        assertContains(result.stderr, "overlay: wrote the coverage of 30 workflow type(s) from 32 execution(s) to ")
    }

    @Test
    fun `without --json the summary is a table, a sentence and the index page`() {
        val result = overlay(history = some("OrderWorkflow.json", "OrderWorkflow.payment-timeout.json", "TimerWorkflow.json"))

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val lines = result.stdout.lines()
        assertEquals(listOf("Workflow", "type", "Executions", "Nodes", "hit", "Mapped", "Unmapped", "Uncertain", "Coverage"), lines[0].split(Regex("\\s+")))
        assertEquals(
            listOf("OrderWorkflow", "2", "25/31", "43", "0", "0", out.toRealPath().resolve("OrderWorkflow.coverage.json").toString()),
            lines[1].split(Regex("\\s+")).let { it.dropLast(1) + Path.of(it.last()).toRealPath().toString() },
        )
        assertEquals(listOf("TimerWorkflow", "1", "5/5", "5", "0", "0"), lines[2].split(Regex("\\s+")).dropLast(1))
        assertEquals("3 execution(s) of 2 workflow type(s) drawn as ${formats.joinToString(", ") { it.name }}", lines[3])
        assertEquals(out.toRealPath().resolve("index.md"), Path.of(lines[4]).toRealPath())
        assertEquals(listOf(""), lines.drop(5))
    }

    @Test
    fun `events without a node do not change the exit status, unless a limit is set`() {
        // Each of the three histories has one event without a node among its three.
        val unlimited = overlay("--json")
        assertEquals(ExitCode.SUCCESS, unlimited.statusCode, unlimited.stderr)
        assertEquals(0, unlimited.summary().getValue("exitCode").jsonPrimitive.int)
        assertFalse("maxUnmappedPct" in unlimited.summary())

        val strict = overlay("--max-unmapped-pct", "0", "--json")
        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, strict.statusCode, strict.stderr)
        val summary = strict.summary()
        assertEquals(2, summary.getValue("exitCode").jsonPrimitive.int)
        assertEquals(0.0, summary.getValue("maxUnmappedPct").jsonPrimitive.double)
        assertEquals(withUnmapped, summary.list("workflows").filter { it.getValue("overThreshold").jsonPrimitive.boolean }.map { it.text("workflowType") })
        assertEquals(33.3, summary.list("workflows").single { it.text("workflowType") == "DynamicDispatchWorkflow" }.getValue("unmappedPct").jsonPrimitive.double)
        assertContains(strict.stderr, "overlay: DynamicDispatchWorkflow: 1 of 3 events have no node (33.3 %), more than the 0.0 % allowed\n")
        // Everything is written all the same.
        assertEquals(contents(), contents().also { assertTrue(it.size > 100) })
        assertTrue(out.resolve("DynamicDispatchWorkflow.coverage.json").isRegularFile())

        // Only more than the limit is too much.
        val lenient = overlay("--max-unmapped-pct", "33.4", "--json")
        assertEquals(ExitCode.SUCCESS, lenient.statusCode, lenient.stderr)
        assertEquals(0, lenient.summary().list("workflows").count { it.getValue("overThreshold").jsonPrimitive.boolean })

        val invalid = overlay("--max-unmapped-pct", "101")
        assertEquals(ExitCode.FAILURE, invalid.statusCode)
        assertContains(invalid.stderr, "--max-unmapped-pct")
    }

    @Test
    fun `a history whose workflow type has no IR is reported, and is no failure`() {
        val ir = Files.createDirectories(dir.resolve("ir"))
        Files.copy(SampleIr.degraded.resolve("TimerWorkflow.ir.json"), ir.resolve("TimerWorkflow.ir.json"))
        val some = some("TimerWorkflow.json", "AwaitWorkflow.json")
        Files.writeString(some.resolve("about.json"), "{\"made\": \"by the tests\"}")

        val result = overlay("--json", ir = ir, history = some)

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val summary = result.summary()
        assertEquals(listOf("TimerWorkflow"), summary.list("workflows").map { it.text("workflowType") })
        val unmatched = summary.list("unmatched").single()
        assertEquals(listOf("history", "workflowType", "reason"), unmatched.keys.toList())
        assertEquals("AwaitWorkflow.json", unmatched.text("history"))
        assertEquals("AwaitWorkflow", unmatched.text("workflowType"))
        assertEquals("no workflow document for this workflow type", unmatched.text("reason"))
        assertEquals(listOf("about.json"), summary.getValue("skipped").jsonArray.map { it.jsonPrimitive.content })
        assertContains(result.stderr, "overlay: warning: AwaitWorkflow.json is left out: workflow type AwaitWorkflow: no workflow document for this workflow type\n")
        assertContains(result.stderr, "overlay: warning: about.json is not an event history; skipped\n")
        assertTrue(contents().keys.none { "Await" in it })

        val plain = overlay(ir = ir, history = some, out = dir.resolve("plain"))
        assertEquals(ExitCode.SUCCESS, plain.statusCode, plain.stderr)
        assertContains(plain.stdout, "1 history file(s) without a workflow document: AwaitWorkflow.json\n")

        // Histories that fit nothing at all leave an empty index and a warning.
        val none = overlay("--json", ir = ir, history = histories.resolve("AwaitWorkflow.json"), out = dir.resolve("none"))
        assertEquals(ExitCode.SUCCESS, none.statusCode, none.stderr)
        assertEquals(0, none.summary().getValue("workflows").jsonArray.size)
        assertContains(none.stderr, "overlay: warning: no history in ")
        assertEquals(listOf("index.md"), dir.resolve("none").listDirectoryEntries().map { it.name })
    }

    @Test
    fun `a single history file can be given`() {
        val result = overlay("--json", history = histories.resolve("OrderWorkflow.payment-declined.json"))

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val workflow = result.summary().list("workflows").single()
        assertEquals("OrderWorkflow", workflow.text("workflowType"))
        assertEquals(listOf("failed"), workflow.list("histories").map { it.text("status") })
        assertEquals(2 + formats.size * 2 + 1, contents().size)
    }

    private val first = "4f2a9c1"
    private val second = "9b7d3e0"

    /** The IR of some workflow types as that of two commits, each in the directory of its name. */
    private fun irByCommit(): Path {
        val root = dir.resolve("ir-by-commit")
        for (commit in listOf(first, second)) {
            val directory = Files.createDirectories(root.resolve(commit))
            for (type in listOf("OrderWorkflow", "TimerWorkflow", "DynamicDispatchWorkflow")) {
                val document = IrJson.decode(Files.readString(SampleIr.degraded.resolve("$type.ir.json")))
                Files.writeString(directory.resolve("$type.ir.json"), IrJson.encode(document.copy(commit = commit)))
            }
        }
        return root
    }

    /** A sample history as workers of the deployment `orders` with [buildId] would have left it; from the workflow task [from] on. */
    private fun ranBy(name: String, buildId: String, from: Int = 0, history: History = sample(name)): History =
        FakeTemporalServer.ranBy(history, "orders", buildId, from)

    private fun sample(name: String): History = WorkflowExecutionHistory.fromJson(Files.readString(histories.resolve("$name.json"))).history

    /** Histories of two versions, one that changed hands, one of an unknown version and one without a version. */
    private fun versioned(): Path {
        val directory = Files.createDirectories(dir.resolve("versioned"))
        fun write(file: String, history: History) = Files.writeString(directory.resolve(file), WorkflowExecutionHistory(history).toJson(true))
        write("order-a.json", ranBy("OrderWorkflow", "build-a"))
        write("order-b.json", ranBy("OrderWorkflow.payment-declined", "build-b"))
        write("order-moved.json", ranBy("OrderWorkflow.payment-timeout", "build-b", from = 3, history = ranBy("OrderWorkflow.payment-timeout", "build-a")))
        write("dispatch-b.json", ranBy("DynamicDispatchWorkflow", "build-b"))
        write("timer-x.json", ranBy("TimerWorkflow", "build-x"))
        write("timer-plain.json", sample("TimerWorkflow"))
        return directory
    }

    private fun versionMap(unversioned: String? = null): Path =
        Files.writeString(
            dir.resolve("versions.json"),
            """
            {
              "schemaVersion": 1,
              "versions": [
                { "deployment": "orders", "buildId": "build-a", "commit": "$first" },
                { "buildId": "build-b", "commit": "$second" }
              ]${unversioned?.let { ",\n  \"unversioned\": \"$it\"" }.orEmpty()}
            }
            """.trimIndent(),
        )

    @Test
    fun `with a version map every history is laid over the IR of the commit that ran it`() {
        val result = overlay("--version-map", versionMap().toString(), "--format", "mermaid", "--json", ir = irByCommit(), history = versioned())

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val summary = result.summary()
        assertEquals(
            listOf("command", "directory", "index", "formats", "versionMap", "commits", "workflows", "unmatched", "skipped", "exitCode"),
            summary.keys.toList(),
        )
        assertEquals(dir.resolve("versions.json").toRealPath(), Path.of(summary.text("versionMap")).toRealPath())
        assertEquals(out.toRealPath().resolve("index.md"), Path.of(summary.text("index")).toRealPath())

        val commits = summary.list("commits")
        assertEquals(listOf(first, second), commits.map { it.text("commit") })
        assertEquals(listOf("commit", "directory", "index", "workerVersions", "unversioned", "workflows", "executions"), commits[0].keys.toList())
        assertEquals(out.toRealPath().resolve(first), Path.of(commits[0].text("directory")).toRealPath())
        assertEquals(out.toRealPath().resolve("$second/index.md"), Path.of(commits[1].text("index")).toRealPath())
        assertEquals(listOf("orders" to "build-a"), commits[0].list("workerVersions").map { it.text("deployment") to it.text("buildId") })
        assertEquals(listOf("build-b"), commits[1].list("workerVersions").map { it.text("buildId") })
        assertEquals(listOf(1, 3), commits.map { it.getValue("executions").jsonPrimitive.int })
        assertEquals(listOf(1, 2), commits.map { it.getValue("workflows").jsonPrimitive.int })
        assertEquals(listOf(0, 0), commits.map { it.getValue("unversioned").jsonPrimitive.int })

        val workflows = summary.list("workflows")
        assertEquals(
            listOf(first to "OrderWorkflow", second to "DynamicDispatchWorkflow", second to "OrderWorkflow"),
            workflows.map { it.text("commit") to it.text("workflowType") },
        )
        val order = workflows[2]
        assertEquals("workflowType", order.keys.first())
        assertEquals("commit", order.keys.toList()[1])
        assertEquals("$second/OrderWorkflow.ir.json", order.text("ir"))
        assertEquals(out.toRealPath().resolve("$second/OrderWorkflow.coverage.json"), Path.of(order.text("coverage")).toRealPath())
        assertEquals(second, CoverageJson.decode(Files.readString(Path.of(order.text("coverage")))).commit)
        assertEquals(listOf("order-b.json", "order-moved.json"), order.list("histories").map { it.text("history") })
        assertEquals(out.toRealPath().resolve("$second/executions/order-moved.md"), Path.of(order.list("histories")[1].list("files")[0].text("file")).toRealPath())

        // The version of each history, and all of them where an execution changed hands: the last one decides.
        val moved = order.list("histories")[1]
        assertEquals("build-b", moved.getValue("workerVersion").jsonObject.text("buildId"))
        assertEquals(listOf("build-a", "build-b"), moved.list("workerVersions").map { it.text("buildId") })
        assertFalse("workerVersions" in order.list("histories")[0])
        assertEquals(0, order.getValue("events").jsonObject.getValue("unmapped").jsonPrimitive.int)

        // What the map does not place is reported and left out, and is no failure.
        assertEquals(
            listOf(
                "timer-plain.json" to "the history names no worker version, and the version map has no commit for such histories",
                "timer-x.json" to "worker version orders:build-x is not in the version map",
            ),
            summary.list("unmatched").map { it.text("history") to it.text("reason") },
        )
        assertEquals(
            listOf(
                "$first/OrderWorkflow.coverage.json", "$first/OrderWorkflow.coverage.md", "$first/executions/order-a.md", "$first/index.md",
                "$second/DynamicDispatchWorkflow.coverage.json", "$second/DynamicDispatchWorkflow.coverage.md",
                "$second/OrderWorkflow.coverage.json", "$second/OrderWorkflow.coverage.md", "$second/executions/dispatch-b.md",
                "$second/executions/order-b.md", "$second/executions/order-moved.md", "$second/index.md", "index.md",
            ),
            contents().keys.toList(),
        )

        assertTrue(result.stderr.lines().filter { it.isNotEmpty() }.all { it.startsWith("overlay: ") }, result.stderr)
        assertContains(result.stderr, "overlay: warning: timer-x.json is left out: workflow type TimerWorkflow: worker version orders:build-x is not in the version map\n")
        assertContains(result.stderr, "overlay: DynamicDispatchWorkflow (commit $second): 1 of 3 events have no node\n")
        assertContains(result.stderr, "overlay: commit $first: 1 execution(s), run by orders:build-a\n")
        assertContains(result.stderr, "overlay: commit $second: 3 execution(s), run by orders:build-b\n")
        assertContains(result.stderr, "overlay: wrote the coverage of 3 workflow type(s) from 4 execution(s) to ")
    }

    @Test
    @Suppress("DEPRECATION")
    fun `a worker that named its build alone is matched by build id, and its version has no deployment`() {
        // As a 1.32 server records workers that set nothing but WorkerOptions.setBuildId.
        val stamped = History.newBuilder().addAllEvents(
            sample("OrderWorkflow").eventsList.map { event ->
                if (event.eventType != EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) return@map event
                event.toBuilder().apply { workflowTaskCompletedEventAttributesBuilder.workerVersionBuilder.setBuildId("build-b") }.build()
            },
        ).build()
        val directory = Files.createDirectories(dir.resolve("stamped"))
        Files.writeString(directory.resolve("order-stamped.json"), WorkflowExecutionHistory(stamped).toJson(true))

        val result = overlay("--version-map", versionMap().toString(), "--format", "mermaid", "--json", ir = irByCommit(), history = directory)

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val commit = result.summary().list("commits").single()
        assertEquals(second, commit.text("commit"))
        assertEquals(listOf(listOf("buildId")), commit.list("workerVersions").map { it.keys.toList() })
        val history = result.summary().list("workflows").single().list("histories").single()
        assertEquals(mapOf("buildId" to "build-b"), history.getValue("workerVersion").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertContains(result.stderr, "overlay: commit $second: 1 execution(s), run by build-b\n")
    }

    @Test
    fun `with a version map the table names the commit, and histories without a version go where the map says`() {
        val result = overlay(
            "--version-map", versionMap(unversioned = first).toString(), "--format", "mermaid", "--max-unmapped-pct", "10",
            ir = irByCommit(), history = versioned(),
        )

        assertEquals(ExitCode.UNRESOLVED_THRESHOLD_EXCEEDED, result.statusCode, result.stderr)
        val lines = result.stdout.lines()
        assertEquals(listOf("Commit", "Workflow", "type", "Executions", "Nodes", "hit", "Mapped", "Unmapped", "Uncertain", "Coverage"), lines[0].split(Regex("\\s+")))
        assertEquals(
            listOf(
                listOf(first, "OrderWorkflow", "1"),
                listOf(first, "TimerWorkflow", "1"),
                listOf(second, "DynamicDispatchWorkflow", "1"),
                listOf(second, "OrderWorkflow", "2"),
            ),
            lines.subList(1, 5).map { it.split(Regex("\\s+")).take(3) },
        )
        assertEquals("5 execution(s) laid over 2 commit(s), drawn as mermaid", lines[5])
        assertEquals("1 history file(s) left out: timer-x.json", lines[6])
        assertEquals(out.toRealPath().resolve("index.md"), Path.of(lines[7]).toRealPath())
        assertContains(result.stderr, "overlay: commit $first: 2 execution(s), run by orders:build-a, no version\n")
        assertContains(
            result.stderr,
            "overlay: DynamicDispatchWorkflow (commit $second): 1 of 3 events have no node (33.3 %), more than the 10.0 % allowed\n",
        )
    }

    @Test
    fun `a version map may abbreviate a commit, and one that several directories begin with is a failure`() {
        val ir = irByCommit()
        val abbreviated = Files.writeString(
            dir.resolve("abbreviated.json"),
            """
            {
              "schemaVersion": 1,
              "versions": [
                { "deployment": "orders", "buildId": "build-a", "commit": "4f2a" },
                { "buildId": "build-b", "commit": "9b7d" }
              ],
              "unversioned": "$first"
            }
            """.trimIndent(),
        )

        val result = overlay("--version-map", abbreviated.toString(), "--format", "mermaid", "--json", ir = ir, history = versioned())

        // The commits are reported, and their directories named, as the directories of their IR are.
        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val commits = result.summary().list("commits")
        assertEquals(listOf(first, second), commits.map { it.text("commit") })
        assertEquals(listOf(first, second).map { out.toRealPath().resolve(it) }, commits.map { Path.of(it.text("directory")).toRealPath() })
        assertEquals(listOf(2, 3), commits.map { it.getValue("executions").jsonPrimitive.int })
        assertEquals(
            listOf(first to "OrderWorkflow", first to "TimerWorkflow", second to "DynamicDispatchWorkflow", second to "OrderWorkflow"),
            result.summary().list("workflows").map { it.text("commit") to it.text("workflowType") },
        )
        assertEquals("$second/OrderWorkflow.ir.json", result.summary().list("workflows")[3].text("ir"))
        assertEquals(setOf(first, second, "index.md"), contents().keys.map { it.substringBefore('/') }.toSet())
        assertContains(result.stderr, "overlay: commit $first: 2 execution(s), run by orders:build-a, no version\n")

        // With a second directory that begins the same way the map no longer says which commit it means.
        Files.createDirectories(ir.resolve("4f2a000"))
        val elsewhere = dir.resolve("ambiguous")
        val ambiguous = overlay("--version-map", abbreviated.toString(), "--json", ir = ir, history = versioned(), out = elsewhere)
        assertEquals(ExitCode.FAILURE, ambiguous.statusCode)
        assertEquals("", ambiguous.stdout)
        assertEquals(
            "overlay: failed: the version map names commit 4f2a, which several directories in $ir begin with: " +
                "4f2a000, 4f2a9c1; the map has to write more of the commit\n",
            ambiguous.stderr,
        )
        assertFalse(elsewhere.exists())

        assertContains(durableAtlas().test("overlay --help").stdout.onOneLine(), "may abbreviate a commit's SHA to its first four or more characters")
    }

    @Test
    fun `a version map that cannot be read is a failure, and nothing is written`() {
        val twice = Files.writeString(
            dir.resolve("twice.json"),
            """{"schemaVersion": 1, "versions": [{"buildId": "b", "commit": "c1"}, {"buildId": "b", "commit": "c2"}]}""",
        )
        val result = overlay("--version-map", twice.toString(), "--json", ir = irByCommit(), history = versioned())
        assertEquals(ExitCode.FAILURE, result.statusCode)
        assertEquals("", result.stdout)
        assertEquals("overlay: failed: twice.json is not a version map: b is listed twice\n", result.stderr)
        assertFalse(out.exists())

        val missing = overlay("--version-map", dir.resolve("nowhere.json").toString())
        assertEquals(ExitCode.FAILURE, missing.statusCode)
        assertContains(missing.stderr, "--version-map")

        // Without the directories of the commits nothing can be laid over anything: every history is left out.
        val flat = overlay("--version-map", versionMap().toString(), "--json", history = versioned())
        assertEquals(ExitCode.SUCCESS, flat.statusCode, flat.stderr)
        assertEquals(0, flat.summary().getValue("workflows").jsonArray.size)
        assertEquals(6, flat.summary().getValue("unmatched").jsonArray.size)
        assertContains(flat.stderr, "which has no directory of workflow documents")

        assertContains(durableAtlas().test("overlay --help").stdout.onOneLine(), "which commit each worker version was built from")
    }

    @Test
    fun `a history or an IR file that cannot be read is a failure, and nothing is written`() {
        val some = some("TimerWorkflow.json")
        Files.writeString(some.resolve("broken.json"), "{\"events\": [{\"eventId\": \"one\"}]}")

        val history = overlay("--json", history = some)
        assertEquals(ExitCode.FAILURE, history.statusCode)
        assertEquals("", history.stdout)
        assertContains(history.stderr, "overlay: failed: broken.json is not a workflow event history")
        assertFalse(out.exists())

        val ir = Files.createDirectories(dir.resolve("ir"))
        Files.writeString(ir.resolve("Broken.ir.json"), "{ \"schemaVersion\": 1 }")
        val document = overlay("--json", ir = ir)
        assertEquals(ExitCode.FAILURE, document.statusCode)
        assertContains(document.stderr, "overlay: failed: Broken.ir.json: ")
        assertFalse(out.exists())

        val missing = overlay(history = dir.resolve("nowhere"))
        assertEquals(ExitCode.FAILURE, missing.statusCode)
        assertContains(missing.stderr, "--history")
    }

    @Test
    fun `the diagram settings are passed on`() {
        val some = some("OrderWorkflow.json", "LoopWorkflow.json")

        val mermaid = overlay("--format", "mermaid", "--max-nodes", "10", "--json", history = some)
        assertEquals(ExitCode.SUCCESS, mermaid.statusCode, mermaid.stderr)
        assertEquals(listOf("mermaid"), mermaid.summary().getValue("formats").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("diagram" in mermaid.summary().list("workflows").first())
        assertTrue(contents().keys.none { it.endsWith(".svg") })
        assertContains(Files.readString(out.resolve("LoopWorkflow.coverage.md")), "This workflow has 12 nodes, more than the limit of 10")

        val pages = dir.resolve("pages")
        val result = overlay(
            "--format", "svg", "--html", "--legend", "none", "--background", "transparent", "--wrap-width", "0",
            "--source-base-url", "https://example.org/repo/blob/{commit}", "--json",
            history = some, out = pages,
        )
        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertEquals(listOf("svg", "html"), result.summary().getValue("formats").jsonArray.map { it.jsonPrimitive.content })
        val svg = Files.readString(pages.resolve("executions/OrderWorkflow.svg"))
        assertFalse("class=\"paper\"" in svg)
        assertFalse("class=\"legend\"" in svg)
        assertContains(svg, "xlink:href=\"https://example.org/repo/blob/")
        assertTrue(pages.resolve("executions/OrderWorkflow.html").isRegularFile())
        assertTrue(pages.resolve("OrderWorkflow.coverage.html").isRegularFile())
        assertTrue(contents(pages).keys.none { it.endsWith(".md") && it != "index.md" })

        val invalid = overlay("--format", "no-such-format")
        assertEquals(ExitCode.FAILURE, invalid.statusCode)
        assertContains(invalid.stderr.onOneLine(), "is not a format this tool can write")
    }

    @Test
    fun `two runs write the same bytes`() {
        val some = some("OrderWorkflow.json", "OrderWorkflow.payment-declined.json", "ApprovalWorkflow.json", "Shipment.json")

        assertEquals(ExitCode.SUCCESS, overlay(history = some).statusCode)
        val first = contents()
        assertEquals(ExitCode.SUCCESS, overlay(history = some).statusCode)
        assertEquals(first, contents())
        val other = dir.resolve("other")
        assertEquals(ExitCode.SUCCESS, overlay(history = some, out = other).statusCode)
        assertEquals(first, contents(other))
        assertEquals(3 * (2 + formats.size) + 4 * formats.size + 1, first.size)
    }
}
