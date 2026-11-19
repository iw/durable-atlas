package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.Join
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.WaitsFor
import io.github.iw.durableatlas.overlay.coverage.CoverageJson
import io.temporal.api.common.v1.ActivityType
import io.temporal.api.common.v1.Payload
import io.temporal.api.common.v1.WorkflowType
import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.History
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.common.WorkflowExecutionHistory
import kotlinx.serialization.json.Json
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

/**
 * The `render` and `overlay` commands on a document that holds a race whose losers a scope
 * cancels, a region that is left once a child in it has started, an upsert that names some of
 * what it writes and a wait that the workflow method ends itself; and on a history that holds
 * the events of activities and a child that started, and an update that was turned down.
 */
class OpenCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val ir: Path get() = Files.createDirectories(dir.resolve("ir"))

    /** Two quotes raced and the slower one cancelled; a label; a region with a contract whose start is waited for; a filing. */
    private val tender = IrDocument(
        commit = "0".repeat(40),
        modelMode = ModelMode.GRADLE,
        workflowType = "Tender",
        interfaceName = "com.example.Tender",
        implementation = "com.example.TenderImpl",
        module = ":workflows",
        entryPoints = listOf(
            EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, "Tender", "start"),
            EntryPoint("update-amend", EntryPointKind.UPDATE, "amend", "amend", reads = listOf("open")),
        ),
        nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("quotes", NodeKind.PARALLEL, join = Join.FIRST),
            IrNode("near", NodeKind.ACTIVITY, name = "Quote", parent = "quotes"),
            IrNode("far", NodeKind.ACTIVITY, name = "QuoteAbroad", parent = "quotes"),
            IrNode("stop", NodeKind.CANCEL_SCOPE, label = "quotes"),
            IrNode("stage", NodeKind.UPSERT_SEARCH_ATTRIBUTES, keys = listOf("Stage"), otherKeys = true),
            IrNode("signing", NodeKind.PARALLEL, waitsFor = WaitsFor.START),
            IrNode("draft", NodeKind.ACTIVITY, name = "Draft", parent = "signing"),
            IrNode("contract", NodeKind.CHILD_WORKFLOW, name = "Contract", waitsFor = WaitsFor.START, parent = "signing"),
            IrNode("file", NodeKind.ACTIVITY, name = "File"),
            IrNode("end", NodeKind.END),
            IrNode("amend", NodeKind.START),
            IrNode("open", NodeKind.AWAIT, reads = listOf("open"), label = "open"),
            IrNode("amended", NodeKind.END),
        ),
        edges = listOf(
            IrEdge("start", "quotes"), IrEdge("quotes", "stop"), IrEdge("stop", "quotes", EdgeKind.CANCELS), IrEdge("stop", "stage"),
            IrEdge("stage", "signing"), IrEdge("draft", "contract"), IrEdge("signing", "file"), IrEdge("file", "end"),
            IrEdge("amend", "open"), IrEdge("open", "amended"), IrEdge("main", "open", EdgeKind.WAKES, "open"),
        ),
    )

    private fun write() = Files.writeString(ir.resolve("Tender.ir.json"), IrJson.encode(tender))

    @Test
    fun `render draws the race, the region that waits for a start, the partly known upsert and what the workflow method wakes`() {
        write()
        val out = dir.resolve("diagrams")
        val result = durableAtlas().test(listOf("render", "--ir", ir.toString(), "--out", out.toString(), "--json"))

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val diagram = Json.parseToJsonElement(result.stdout).jsonObject.getValue("diagrams").jsonArray.single().jsonObject
        assertEquals(14, diagram.getValue("nodes").jsonPrimitive.int)

        val drawn = Files.readString(out.resolve("Tender.svg"))
        assertContains(drawn, ">PARALLEL · FIRST TO FINISH</text>")
        assertContains(drawn, ">PARALLEL · WAITS FOR START ONLY</text>")
        assertContains(drawn, ">Stage, …</text>")
        assertContains(drawn, "data-from=\"stop\" data-to=\"quotes\" data-kind=\"cancels\"")
        assertContains(drawn, "data-from=\"start\" data-to=\"open\" data-kind=\"wakes\"")
        val page = Files.readString(out.resolve("Tender.md"))
        assertContains(page, "subgraph n_quotes_region [\"parallel#58; first to finish\"]")
        assertContains(page, "n_quotes_join((\"first to finish\"))")
        assertContains(page, "n_stage[(\"upsert search attributes#58; Stage, …\")]")
        assertContains(page, "subgraph n_signing_region [\"parallel#58; waits for start only\"]")
        assertContains(page, "n_start -.->|\"open\"| n_open")
    }

    @Test
    fun `overlay leaves the race with its first lane, lays started events on their steps and names the update that was turned down`() {
        write()
        var next = 0L
        fun event(type: EventType, attributes: HistoryEvent.Builder.() -> Unit = {}): HistoryEvent =
            HistoryEvent.newBuilder().setEventId(++next).setEventType(type).apply(attributes).build()
        fun scheduled(name: String) = event(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED) {
            activityTaskScheduledEventAttributesBuilder.setActivityType(ActivityType.newBuilder().setName(name))
        }
        fun started(scheduled: Long) = event(EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED) { activityTaskStartedEventAttributesBuilder.setScheduledEventId(scheduled) }
        fun completed(scheduled: Long) = event(EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) { activityTaskCompletedEventAttributesBuilder.setScheduledEventId(scheduled) }
        val history = History.newBuilder().addAllEvents(
            listOf(
                event(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED) {
                    workflowExecutionStartedEventAttributesBuilder.setWorkflowType(WorkflowType.newBuilder().setName("Tender"))
                },
                // Both quotes are asked for; the one from near by is back first, and the other is cancelled.
                scheduled("Quote"),
                scheduled("QuoteAbroad"),
                started(2),
                started(3),
                completed(2),
                event(EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED) { activityTaskCancelRequestedEventAttributesBuilder.setScheduledEventId(3) },
                event(EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES) {
                    upsertWorkflowSearchAttributesEventAttributesBuilder.searchAttributesBuilder
                        .putIndexedFields("Stage", Payload.getDefaultInstance()).putIndexedFields("Bidder", Payload.getDefaultInstance())
                },
                // An amendment arrives that the validator turns down.
                event(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_REJECTED),
                scheduled("Draft"),
                completed(10),
                event(EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED) {
                    startChildWorkflowExecutionInitiatedEventAttributesBuilder.setWorkflowType(WorkflowType.newBuilder().setName("Contract"))
                },
                event(EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED) { childWorkflowExecutionStartedEventAttributesBuilder.setInitiatedEventId(12) },
                // The contract has started: the workflow files the tender and is done while the contract runs on.
                scheduled("File"),
                completed(14),
                event(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED) { workflowExecutionCompletedEventAttributesBuilder },
            ),
        ).build()
        val histories = Files.createDirectories(dir.resolve("histories"))
        Files.writeString(histories.resolve("Tender.json"), WorkflowExecutionHistory(history).toJson(true))

        val out = dir.resolve("overlay")
        val result = durableAtlas().test(listOf("overlay", "--ir", ir.toString(), "--history", histories.toString(), "--out", out.toString(), "--json"))

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val workflow = Json.parseToJsonElement(result.stdout).jsonObject.getValue("workflows").jsonArray.single().jsonObject
        val events = workflow.getValue("events").jsonObject
        // Start, 4 activities, 2 of them started, the request to cancel, the upsert, the child and its start, the end.
        assertEquals(listOf(12, 0, 0, 1), listOf("mapped", "ambiguous", "unmapped", "recognised").map { events.getValue(it).jsonPrimitive.int })

        val coverage = CoverageJson.decode(Files.readString(out.resolve("Tender.coverage.json")))
        assertEquals(emptyList(), coverage.unmapped)
        assertEquals(emptyList(), coverage.ambiguous)
        assertEquals(listOf("WorkflowExecutionUpdateRejected" to 9L), coverage.recognised.map { it.eventType to it.eventId })
        fun hits(from: String, to: String, kind: EdgeKind = EdgeKind.FLOW): Int = coverage.edges.single { it.from == from && it.to == to && it.kind == kind }.hits
        // The quote that won ended its lane, the one that was cancelled did not; the region was left once.
        assertEquals(listOf(1, 0, 1, 1), listOf(hits("near", "quotes"), hits("far", "quotes"), hits("quotes", "stop"), hits("stop", "quotes", EdgeKind.CANCELS)))
        // The flow went on past the region of the contract, whose lane got as far as the child.
        assertEquals(listOf(1, 1, 1), listOf(hits("signing", "file"), hits("draft", "contract"), hits("stage", "signing")))
        // Nothing in a history says that the workflow method ended a wait that started no timer.
        assertEquals(0, hits("main", "open", EdgeKind.WAKES))
        val diagram = Files.readString(out.resolve("Tender.coverage.svg"))
        assertContains(diagram, ">PARALLEL · FIRST TO FINISH</text>")
        assertContains(Files.readString(out.resolve("index.md")), "- `Tender.json`, event 9: `WorkflowExecutionUpdateRejected`")
    }
}
