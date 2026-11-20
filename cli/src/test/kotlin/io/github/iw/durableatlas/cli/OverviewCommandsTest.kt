package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.ir.Detail
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.ir.testing.OverviewContract
import io.github.iw.durableatlas.modelextractor.testing.SampleRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The commands on a commit of the sample repository whose workflow hands a block to a library
 * that a degraded model lacks and calls a helper around an interface no class implements.
 * `--detail overview`, the default, leaves both calls out of the diagram, and every command
 * that reads or writes the workflow says how many there are and where they are listed;
 * `--detail full` draws both, and says nothing that the commands did not say before.
 */
class OverviewCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val repository: SampleRepository by lazy { SampleRepository.create(dir.resolve("repo")) }

    /** The commit with the workflow whose steps stand among calls that cannot be read. */
    private val wrapped: String by lazy {
        repository.commit("Add a workflow that wraps its steps") { project ->
            val sources = Files.createDirectories(project.resolve("$SOURCES/wrapped"))
            Files.writeString(sources.resolve("WrappedWorkflow.kt"), WORKFLOW.trimIndent() + "\n")
        }
    }

    private fun command(vararg arguments: String): CliktCommandTestResult = durableAtlas().test(arguments.toList())

    private fun run(commit: String, out: Path, vararg arguments: String): CliktCommandTestResult =
        command("run", "--repo", repository.project.toString(), "--commit", commit, "--out", out.toString(), "--mode", "degraded", *arguments)

    private fun CliktCommandTestResult.summary(): JsonObject = Json.parseToJsonElement(stdout).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.entries(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    private fun JsonObject.workflow(): JsonObject = entries("workflows").single { it.text("workflowType") == "WrappedWorkflow" }

    @Test
    fun `run draws an overview unless told otherwise, says on stderr how many calls it left out, and counts them for each workflow`() {
        val result = run(wrapped, dir.resolve("out"), "--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertContains(
            result.stderr,
            "run: 2 call(s) that could not be read and behind which a step is only possible have no node in this overview: " +
                "2 place(s) in the sources, met in 1 of 31 workflow(s). Each IR file lists its own under leftOutCalls; " +
                "--detail full draws each as an unknown node\n",
        )
        val summary = result.summary()
        assertEquals("overview", summary.text("detail"))
        val workflow = summary.workflow()
        assertEquals(
            listOf("workflowType", "implementation", "nodes", "unknown", "unknownPct", "skipped", "leftOut", "overThreshold", "ir", "files"),
            workflow.keys.toList(),
        )
        // Two activities between the start and the end: no node for the wrapper, none for the helper, no branch around it.
        assertEquals(listOf(4, 0, 1, 2), listOf("nodes", "unknown", "skipped", "leftOut").map { workflow.getValue(it).jsonPrimitive.int })
        assertTrue(summary.entries("workflows").filter { it !== workflow }.none { "leftOut" in it })

        val text = Files.readString(Path.of(workflow.text("ir")))
        JsonSchemaChecker.forWorkflowIr().assertValid(text)
        val document = IrJson.decode(text)
        assertEquals(Detail.OVERVIEW, document.detail)
        assertEquals(listOf(NodeKind.START, NodeKind.END, NodeKind.ACTIVITY, NodeKind.ACTIVITY), document.nodes.map { it.kind }.sorted())
        assertEquals(
            listOf("WrappedWorkflow.kt:35 unsupported traced", "WrappedWorkflow.kt:39 unseen-code Records.keep"),
            document.leftOutCalls.map { "${it.loc.file.substringAfterLast('/')}:${it.loc.line} ${it.reason} ${it.label}" },
        )

        // The pages say it where a reader looks: the index in a column, the page of the workflow in a list, the drawing below it.
        val diagrams = Path.of(summary.text("diagrams"))
        val index = Files.readString(diagrams.resolve("index.md"))
        assertContains(index, "| Workflow | Implementation | Model | Nodes | Unknown | Skipped | Left out | Diagram |")
        assertContains(index, "| `WrappedWorkflow` | `com.example.fixture.workflows.wrapped.WrappedWorkflowImpl` | `degraded` | 4 | 0 | 1 | 2 |")
        assertContains(index, "Left out: the diagrams are overviews, and 2 calls that could not be read have no node in them")
        val page = Files.readString(diagrams.resolve("WrappedWorkflow.md"))
        assertContains(page, "| Calls left out | 2 |")
        assertContains(page, "## Calls left out\n")
        assertContains(page, "- `unsupported`: `traced` at `$SOURCES/wrapped/WrappedWorkflow.kt:35`")
        assertContains(Files.readString(diagrams.resolve("WrappedWorkflow.svg")), "Overview: 2 calls that could not be read are not drawn: 1 is not modelled")
    }

    @Test
    fun `without --json the table has a column for the calls that were left out`() {
        val result = run(wrapped, dir.resolve("out"))

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val lines = result.stdout.lines()
        assertEquals(listOf("Workflow type", "Nodes", "Unknown", "Skipped", "Left out", "File"), lines[0].split(Regex(" {2,}")))
        val rows = lines.subList(1, 32).map { it.trim().split(Regex(" {2,}")) }
        assertEquals(listOf("WrappedWorkflow", "4", "0", "1", "2"), rows.single { it[0] == "WrappedWorkflow" }.take(5))
        assertEquals(listOf("TypedActivityWorkflow", "6", "0", "0", "0"), rows.single { it[0] == "TypedActivityWorkflow" }.take(5))
        assertEquals("31 workflow(s) drawn from the degraded model of $wrapped", lines[32])
        assertEquals("1 unresolved call(s) taken for calls into libraries and not drawn, listed on the page of each workflow", lines[33])
        assertEquals(
            "2 call(s) that could not be read left out of the overview (--detail full draws each as an unknown node), listed on the page of each workflow",
            lines[34],
        )
        assertTrue(lines[35].endsWith("index.md"), lines[35])
    }

    @Test
    fun `--detail full draws every call that could not be read, and says nothing of overviews`() {
        val result = run(wrapped, dir.resolve("out"), "--detail", "full", "--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertFalse("overview" in result.stderr, result.stderr)
        val summary = result.summary()
        assertEquals(
            listOf("command", "commit", "mode", "cached", "fallback", "model", "ir", "diagrams", "index", "formats", "workflows", "exitCode"),
            summary.keys.toList(),
        )
        val workflow = summary.workflow()
        assertEquals(
            listOf("workflowType", "implementation", "nodes", "unknown", "unknownPct", "skipped", "overThreshold", "ir", "files"),
            workflow.keys.toList(),
        )
        // The wrapper, the question that the helper stands behind, and the helper.
        assertEquals(listOf(7, 2, 1), listOf("nodes", "unknown", "skipped").map { workflow.getValue(it).jsonPrimitive.int })
        val text = Files.readString(Path.of(workflow.text("ir")))
        assertFalse("\"detail\"" in text)
        assertFalse("leftOutCalls" in text)
        val diagrams = Path.of(summary.text("diagrams"))
        assertFalse("Left out" in Files.readString(diagrams.resolve("index.md")))
        assertFalse("left out" in Files.readString(diagrams.resolve("WrappedWorkflow.md")))
        assertFalse("Overview:" in Files.readString(diagrams.resolve("WrappedWorkflow.svg")))

        // The table for people is the one a run always printed.
        val plain = run(wrapped, dir.resolve("plain"), "--detail", "full")
        assertEquals(listOf("Workflow type", "Nodes", "Unknown", "Skipped", "File"), plain.stdout.lines()[0].split(Regex(" {2,}")))
        assertFalse("overview" in plain.stdout, plain.stdout)
    }

    @Test
    fun `an overview keeps every step of the full detail, and where nothing is left out its diagrams are the same files`() {
        assertEquals(ExitCode.SUCCESS, run(wrapped, dir.resolve("overview")).statusCode)
        assertEquals(ExitCode.SUCCESS, run(wrapped, dir.resolve("full"), "--detail", "full").statusCode)
        fun files(out: String, kind: String): Map<String, String> =
            dir.resolve(out).resolve(kind).resolve(wrapped).listDirectoryEntries().sorted().associate { it.name to Files.readString(it) }

        val full = files("full", "ir")
        val overviews = files("overview", "ir")
        assertEquals(full.keys, overviews.keys)
        val differing = ArrayList<String>()
        for (name in full.keys.filter { it.endsWith(".ir.json") }) {
            val before = IrJson.decode(full.getValue(name))
            val after = IrJson.decode(overviews.getValue(name))
            assertEquals(emptyList(), OverviewContract.violations(before, after), name)
            if (OverviewContract.differs(before, after)) differing += name.removeSuffix(".ir.json")
            // An overview that left nothing out differs from the full document in the one line that says what it is.
            else assertEquals(full.getValue(name), overviews.getValue(name).replace("  \"detail\": \"overview\",\n", ""), name)
        }
        // One workflow of the sample repository has a node that only says why its call could not be read, and is told what was called.
        assertEquals(listOf("DynamicDispatchWorkflow", "WrappedWorkflow"), differing)

        val drawn = files("full", "diagrams")
        val brief = files("overview", "diagrams")
        assertEquals(drawn.keys, brief.keys)
        val changed = drawn.keys.filter { drawn.getValue(it) != brief.getValue(it) }
        assertEquals(
            listOf("DynamicDispatchWorkflow.md", "DynamicDispatchWorkflow.svg", "WrappedWorkflow.md", "WrappedWorkflow.svg", "index.md"),
            changed,
        )
    }

    @Test
    fun `where nothing is left out no command says anything of it`() {
        val result = run(repository.head, dir.resolve("out"))

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertEquals(listOf("Workflow type", "Nodes", "Unknown", "File"), result.stdout.lines()[0].split(Regex(" {2,}")))
        assertFalse("overview" in result.stderr, result.stderr)
        assertFalse("eft out" in result.stdout, result.stdout)
        val head = repository.head
        assertFalse("Left out" in Files.readString(dir.resolve("out").resolve("diagrams").resolve(head).resolve("index.md")))
        for (file in dir.resolve("out").resolve("ir").resolve(head).listDirectoryEntries("*.ir.json")) {
            val text = Files.readString(file)
            assertContains(text, "\"detail\": \"overview\"")
            assertFalse("leftOutCalls" in text, file.toString())
        }
    }

    @Test
    fun `analyse, render and overlay each say what they have of the calls that were left out`() {
        assertEquals(ExitCode.SUCCESS, run(wrapped, dir.resolve("out")).statusCode)
        val model = dir.resolve("out").resolve("model").resolve(wrapped)
        val ir = dir.resolve("ir")

        val analysed = command("analyse", "--model", model.toString(), "--out", ir.toString(), "--json")
        assertEquals(ExitCode.SUCCESS, analysed.statusCode, analysed.stderr)
        assertContains(analysed.stderr, "analyse: 2 call(s) that could not be read and behind which a step is only possible have no node in this overview: ")
        assertEquals("overview", analysed.summary().text("detail"))
        val documents = analysed.summary().entries("workflows").flatMap { it.entries("ir") }
        val document = documents.single { it.text("file").endsWith("WrappedWorkflow.ir.json") }
        assertEquals(listOf("implementation", "file", "nodes", "unknown", "skipped", "leftOut"), document.keys.toList())
        assertEquals(2, document.getValue("leftOut").jsonPrimitive.int)
        assertTrue(documents.filter { it !== document }.none { "leftOut" in it })

        val plain = command("analyse", "--model", model.toString(), "--out", ir.toString())
        assertContains(plain.stdout, "\n2 call(s) that could not be read left out of the overview (--detail full draws each as an unknown node), listed in the IR files")

        val rendered = command("render", "--ir", ir.toString(), "--out", dir.resolve("diagrams").toString(), "--json")
        assertEquals(ExitCode.SUCCESS, rendered.statusCode, rendered.stderr)
        val drawn = rendered.summary().entries("diagrams")
        assertEquals(2, drawn.single { it.text("workflowType") == "WrappedWorkflow" }.getValue("leftOut").jsonPrimitive.int)
        assertEquals(1, drawn.count { "leftOut" in it })

        // An execution that ran an activity the overview has no node for: one of the calls may hide it.
        val histories = Files.createDirectories(dir.resolve("histories"))
        Files.writeString(histories.resolve("wrapped.json"), HISTORY.trimIndent() + "\n")
        val overlaid = command("overlay", "--ir", ir.toString(), "--history", histories.toString(), "--out", dir.resolve("coverage").toString())
        assertEquals(ExitCode.SUCCESS, overlaid.statusCode, overlaid.stderr)
        assertContains(overlaid.stderr, "overlay: WrappedWorkflow: 1 of 5 events have no node")
        assertContains(
            Files.readString(dir.resolve("coverage").resolve("index.md")),
            "A step without a node may lie behind one of the 2 calls that this overview leaves out. The IR file of the workflow lists them.",
        )

        // In full, analyse writes what it always wrote, and says nothing of a detail.
        val whole = command("analyse", "--model", model.toString(), "--out", dir.resolve("ir-full").toString(), "--detail", "full", "--json")
        assertEquals(ExitCode.SUCCESS, whole.statusCode, whole.stderr)
        assertEquals(
            listOf("command", "commit", "mode", "inventory", "workflows", "activityInterfaces", "standaloneActivities", "warnings"),
            whole.summary().keys.toList(),
        )
        assertFalse("overview" in whole.stderr, whole.stderr)
        assertFalse("\"detail\"" in Files.readString(dir.resolve("ir-full").resolve("WrappedWorkflow.ir.json")))
    }

    @Test
    fun `a detail that is neither of the two is refused, and the help of both commands names the option`() {
        val refused = run(wrapped, dir.resolve("out"), "--detail", "brief")
        assertEquals(ExitCode.FAILURE, refused.statusCode)
        assertContains(refused.stderr, "--detail")
        assertFalse(Files.exists(dir.resolve("out")))

        for (name in listOf("run", "analyse")) {
            val help = command(name, "--help").stdout.replace(Regex("\\s+"), " ")
            assertContains(help, "--detail=(full|overview)")
            assertContains(help, "'overview' (the default)")
        }
    }

    private companion object {
        const val SOURCES = "workflows/src/main/kotlin/com/example/fixture/workflows"

        const val WORKFLOW = """
            package com.example.fixture.workflows.wrapped

            import com.example.fixture.activities.InventoryActivities
            import io.temporal.activity.ActivityOptions
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod
            import java.time.Duration
            import org.example.tracing.traced

            /** No class of the sources implements this one. */
            interface StockLedger {
                fun note(sku: String)
            }

            class Records(private val ledger: StockLedger) {
                fun keep(sku: String) {
                    ledger.note(sku)
                }
            }

            @WorkflowInterface
            interface WrappedWorkflow {
                @WorkflowMethod
                fun run(sku: String)
            }

            class WrappedWorkflowImpl(private val records: Records) : WrappedWorkflow {
                private val inventory = Workflow.newActivityStub(
                    InventoryActivities::class.java,
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(),
                )

                override fun run(sku: String) {
                    traced("reserve") {
                        inventory.reserveStock(sku, 1)
                    }
                    if (sku.isEmpty()) {
                        records.keep(sku)
                    }
                    inventory.release(sku)
                }
            }
        """

        /** The two activities of the workflow, and between them one that it has no node for. */
        const val HISTORY = """
            {"events": [
              {"eventId": "1", "eventType": "EVENT_TYPE_WORKFLOW_EXECUTION_STARTED",
               "workflowExecutionStartedEventAttributes": {"workflowType": {"name": "WrappedWorkflow"}, "workflowId": "wrapped-1", "originalExecutionRunId": "run-1"}},
              {"eventId": "2", "eventType": "EVENT_TYPE_ACTIVITY_TASK_SCHEDULED",
               "activityTaskScheduledEventAttributes": {"activityType": {"name": "ReserveStock"}}},
              {"eventId": "3", "eventType": "EVENT_TYPE_ACTIVITY_TASK_COMPLETED", "activityTaskCompletedEventAttributes": {"scheduledEventId": "2"}},
              {"eventId": "4", "eventType": "EVENT_TYPE_ACTIVITY_TASK_SCHEDULED",
               "activityTaskScheduledEventAttributes": {"activityType": {"name": "WriteAudit"}}},
              {"eventId": "5", "eventType": "EVENT_TYPE_ACTIVITY_TASK_COMPLETED", "activityTaskCompletedEventAttributes": {"scheduledEventId": "4"}},
              {"eventId": "6", "eventType": "EVENT_TYPE_ACTIVITY_TASK_SCHEDULED",
               "activityTaskScheduledEventAttributes": {"activityType": {"name": "ReleaseStock"}}},
              {"eventId": "7", "eventType": "EVENT_TYPE_ACTIVITY_TASK_COMPLETED", "activityTaskCompletedEventAttributes": {"scheduledEventId": "6"}},
              {"eventId": "8", "eventType": "EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED", "workflowExecutionCompletedEventAttributes": {}}
            ]}
        """
    }
}
