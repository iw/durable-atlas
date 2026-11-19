package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
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
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The commands on a commit of the sample repository whose workflow calls into a library that a
 * degraded model lacks: the calls are left out of the diagram, and every command that reads or
 * writes the workflow says how many there are and where they are listed.
 */
class SkippedCallCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("out")
    private val repository: SampleRepository by lazy { SampleRepository.create(dir.resolve("repo")) }

    /** The commit with the workflow that logs: a helper in a file of its own, and the workflow that uses it. */
    private val logging: String by lazy {
        repository.commit("Add a workflow that logs") { project ->
            val sources = Files.createDirectories(project.resolve("$SOURCES/audit"))
            Files.writeString(sources.resolve("LoggerExtensions.kt"), HELPER.trimIndent() + "\n")
            Files.writeString(sources.resolve("AuditedWorkflow.kt"), WORKFLOW.trimIndent() + "\n")
        }
    }

    private fun command(vararg arguments: String): CliktCommandTestResult = durableAtlas().test(arguments.toList())

    private fun run(commit: String, vararg arguments: String): CliktCommandTestResult =
        command("run", "--repo", repository.project.toString(), "--commit", commit, "--out", out.toString(), "--mode", "degraded", *arguments)

    private fun CliktCommandTestResult.summary(): JsonObject = Json.parseToJsonElement(stdout).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.entries(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

    @Test
    fun `run says on stderr how many calls it left out, and counts them for each workflow`() {
        val result = run(logging, "--json")

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertContains(
            result.stderr,
            "run: 5 call(s) that did not resolve were taken for calls into libraries the model lacks and are not drawn: " +
                "5 place(s) in the sources, met in 1 of 31 workflow(s). Each IR file lists its own under skippedCalls\n",
        )
        val workflows = result.summary().entries("workflows")
        assertEquals(31, workflows.size)
        val audited = workflows.single { it.text("workflowType") == "AuditedWorkflow" }
        assertEquals(
            listOf("workflowType", "implementation", "nodes", "unknown", "unknownPct", "skipped", "overThreshold", "ir", "files"),
            audited.keys.toList(),
        )
        assertEquals(listOf(4, 0, 5), listOf("nodes", "unknown", "skipped").map { audited.getValue(it).jsonPrimitive.int })
        // A workflow that left nothing out says nothing of it.
        assertTrue(workflows.filter { it !== audited }.none { "skipped" in it })

        // The document lists the calls, those of the helper too, and is a valid one.
        val text = Files.readString(Path.of(audited.text("ir")))
        JsonSchemaChecker.forWorkflowIr().assertValid(text)
        val document = IrJson.decode(text)
        assertEquals(ModelMode.DEGRADED, document.modelMode)
        assertEquals(listOf(NodeKind.START, NodeKind.END, NodeKind.ACTIVITY, NodeKind.ACTIVITY), document.nodes.map { it.kind }.sorted())
        assertEquals(
            listOf(
                "AuditedWorkflow.kt:23 AuditLogging.logger(\"audited\")",
                "AuditedWorkflow.kt:29 logger.info(\"released \" + inventory.release(sku))",
                "LoggerExtensions.kt:8 log.info { message }",
                "LoggerExtensions.kt:9 log.atInfo()",
                "LoggerExtensions.kt:9 log.atInfo().log(message)",
            ),
            document.skippedCalls.map { "${it.loc.file.substringAfterLast('/')}:${it.loc.line} ${it.text}" },
        )

        // The pages say it where a reader looks: the index in a column, the page of the workflow in a list.
        val diagrams = Path.of(result.summary().text("diagrams"))
        val index = Files.readString(diagrams.resolve("index.md"))
        assertContains(index, "| Workflow | Implementation | Model | Nodes | Unknown | Skipped | Diagram |")
        assertContains(index, "| `AuditedWorkflow` | `com.example.fixture.workflows.audit.AuditedWorkflowImpl` | `degraded` | 4 | 0 | 5 |")
        assertContains(index, "Skipped: 5 calls that did not resolve were taken for calls into libraries that the degraded model lacks")
        val page = Files.readString(diagrams.resolve("AuditedWorkflow.md"))
        assertContains(page, "| Skipped calls | 5 |")
        assertContains(page, "## Skipped calls\n")
        assertContains(page, "/audit/LoggerExtensions.kt:8`: `log.info { message }`")
        assertContains(Files.readString(diagrams.resolve("AuditedWorkflow.svg")), "5 calls that did not resolve are not drawn")
    }

    @Test
    fun `without --json the table has a column for the calls that were left out`() {
        val result = run(logging)

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val lines = result.stdout.lines()
        assertEquals(listOf("Workflow type", "Nodes", "Unknown", "Skipped", "File"), lines[0].split(Regex(" {2,}")))
        val rows = lines.subList(1, 32).map { it.trim().split(Regex(" {2,}")) }
        assertEquals(listOf("AuditedWorkflow", "4", "0", "5"), rows.single { it[0] == "AuditedWorkflow" }.take(4))
        assertEquals(listOf("TypedActivityWorkflow", "6", "0", "0"), rows.single { it[0] == "TypedActivityWorkflow" }.take(4))
        assertEquals("31 workflow(s) drawn from the degraded model of $logging", lines[32])
        assertEquals("5 unresolved call(s) taken for calls into libraries and not drawn, listed on the page of each workflow", lines[33])
        assertTrue(lines[34].endsWith("index.md"), lines[34])
    }

    @Test
    fun `where nothing is left out no command says anything of it`() {
        val result = run(repository.head)

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertEquals(listOf("Workflow type", "Nodes", "Unknown", "File"), result.stdout.lines()[0].split(Regex(" {2,}")))
        assertFalse("did not resolve" in result.stderr, result.stderr)
        assertFalse("kipped" in result.stdout, result.stdout)
        val head = repository.head
        assertFalse("Skipped" in Files.readString(out.resolve("diagrams").resolve(head).resolve("index.md")))
        for (file in Files.list(out.resolve("ir").resolve(head)).use { it.toList() }) assertFalse("skippedCalls" in Files.readString(file), file.toString())
    }

    @Test
    fun `analyse, render and overlay each say what they have of the calls that were left out`() {
        assertEquals(ExitCode.SUCCESS, run(logging).statusCode)
        val model = out.resolve("model").resolve(logging)
        val ir = dir.resolve("ir")

        val analysed = command("analyse", "--model", model.toString(), "--out", ir.toString(), "--json")
        assertEquals(ExitCode.SUCCESS, analysed.statusCode, analysed.stderr)
        assertContains(analysed.stderr, "analyse: 5 call(s) that did not resolve were taken for calls into libraries the model lacks and are not drawn: ")
        val documents = analysed.summary().entries("workflows").flatMap { it.entries("ir") }
        val audited = documents.single { it.text("file").endsWith("AuditedWorkflow.ir.json") }
        assertEquals(listOf("implementation", "file", "nodes", "unknown", "skipped"), audited.keys.toList())
        assertEquals(5, audited.getValue("skipped").jsonPrimitive.int)
        assertTrue(documents.filter { it !== audited }.none { "skipped" in it })
        // The workflow has no call that the analysis could not follow: what it left out cannot call an activity.
        val unresolved = analysed.summary().getValue("standaloneActivities").jsonObject.getValue("unresolvedWorkflows").jsonArray.map { it.jsonPrimitive.content }
        assertFalse("AuditedWorkflow" in unresolved, unresolved.toString())

        val plain = command("analyse", "--model", model.toString(), "--out", ir.toString())
        assertContains(plain.stdout, "\n5 unresolved call(s) taken for calls into libraries and not drawn, listed in the IR files")

        val rendered = command("render", "--ir", ir.toString(), "--out", dir.resolve("diagrams").toString(), "--json")
        assertEquals(ExitCode.SUCCESS, rendered.statusCode, rendered.stderr)
        val drawn = rendered.summary().entries("diagrams")
        assertEquals(5, drawn.single { it.text("workflowType") == "AuditedWorkflow" }.getValue("skipped").jsonPrimitive.int)
        assertEquals(1, drawn.count { "skipped" in it })

        // An execution that ran an activity the document has no node for: one of the calls may hide it.
        val histories = Files.createDirectories(dir.resolve("histories"))
        Files.writeString(histories.resolve("audited.json"), HISTORY.trimIndent() + "\n")
        val overlaid = command("overlay", "--ir", ir.toString(), "--history", histories.toString(), "--out", dir.resolve("coverage").toString())
        assertEquals(ExitCode.SUCCESS, overlaid.statusCode, overlaid.stderr)
        assertContains(overlaid.stderr, "overlay: AuditedWorkflow: 1 of 5 events have no node")
        assertContains(
            Files.readString(dir.resolve("coverage").resolve("index.md")),
            "A step without a node may lie behind one of the 5 calls that did not resolve and are not drawn. The IR file of the workflow lists them.",
        )
    }

    private companion object {
        const val SOURCES = "workflows/src/main/kotlin/com/example/fixture/workflows"

        const val HELPER = """
            package com.example.fixture.workflows.audit

            import org.example.logging.AuditLogging

            private val log = AuditLogging.logger("audit")

            fun logStep(message: String) {
                log.info { message }
                log.atInfo().log(message)
            }
        """

        const val WORKFLOW = """
            package com.example.fixture.workflows.audit

            import com.example.fixture.activities.InventoryActivities
            import io.temporal.activity.ActivityOptions
            import io.temporal.workflow.Workflow
            import io.temporal.workflow.WorkflowInterface
            import io.temporal.workflow.WorkflowMethod
            import java.time.Duration
            import org.example.logging.AuditLogging

            @WorkflowInterface
            interface AuditedWorkflow {
                @WorkflowMethod
                fun run(sku: String)
            }

            class AuditedWorkflowImpl : AuditedWorkflow {
                private val inventory = Workflow.newActivityStub(
                    InventoryActivities::class.java,
                    ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(),
                )

                private val logger = AuditLogging.logger("audited")

                override fun run(sku: String) {
                    logStep("reserving")
                    inventory.reserveStock(sku, 1)
                    logStep("releasing")
                    logger.info("released " + inventory.release(sku))
                }
            }
        """

        /** The two activities of the workflow, and between them one that it has no node for. */
        const val HISTORY = """
            {"events": [
              {"eventId": "1", "eventType": "EVENT_TYPE_WORKFLOW_EXECUTION_STARTED",
               "workflowExecutionStartedEventAttributes": {"workflowType": {"name": "AuditedWorkflow"}, "workflowId": "audited-1", "originalExecutionRunId": "run-1"}},
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
