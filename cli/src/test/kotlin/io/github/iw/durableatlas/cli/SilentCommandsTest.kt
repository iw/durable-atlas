package io.github.iw.durableatlas.cli

import com.github.ajalt.clikt.testing.test
import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.NodeKind
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
import kotlin.test.assertFalse

/**
 * The `render` and `overlay` commands on documents that hold what a history records without a
 * call that looks like a step: search attributes and memo entries that are set, a wait for a
 * lock, a loop that is a retry and a Nexus operation; and on a history that holds a request
 * from outside to cancel the workflow.
 */
class SilentCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val ir: Path get() = Files.createDirectories(dir.resolve("ir"))

    /** A workflow that labels itself, waits for a lock, publishes with a retry, greets through Nexus and labels itself again. */
    private val listing = IrDocument(
        commit = "0".repeat(40),
        modelMode = ModelMode.GRADLE,
        workflowType = "Listing",
        interfaceName = "com.example.Listing",
        implementation = "com.example.ListingImpl",
        module = ":workflows",
        entryPoints = listOf(
            EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, "Listing", "start"),
            EntryPoint("signal-withdraw", EntryPointKind.SIGNAL, "withdraw", "withdraw", writes = listOf("shelf")),
        ),
        nodes = listOf(
            IrNode("start", NodeKind.START),
            IrNode("stage", NodeKind.UPSERT_SEARCH_ATTRIBUTES, keys = listOf("Stage", "Seller")),
            IrNode("note", NodeKind.UPSERT_MEMO, keys = listOf("note")),
            IrNode("shelf", NodeKind.AWAIT, timeout = "PT1M", reads = listOf("shelf"), label = "shelf.tryLock()"),
            IrNode("again", NodeKind.LOOP, label = "retry on failure, up to 3 attempts", retry = true),
            IrNode("publish", NodeKind.ACTIVITY, name = "Publish", timeout = "PT30S", parent = "again"),
            IrNode("greet", NodeKind.NEXUS_OPERATION, name = "hello", service = "Greeter"),
            IrNode("done", NodeKind.UPSERT_SEARCH_ATTRIBUTES),
            IrNode("end", NodeKind.END),
            IrNode("withdraw", NodeKind.START),
            IrNode("withdrawn", NodeKind.END),
        ),
        edges = listOf(
            IrEdge("start", "stage"), IrEdge("stage", "note"), IrEdge("note", "shelf"), IrEdge("shelf", "again"),
            IrEdge("shelf", "end", EdgeKind.TIMEOUT), IrEdge("again", "greet"), IrEdge("greet", "done"), IrEdge("done", "end"),
            IrEdge("withdraw", "withdrawn"), IrEdge("signal-withdraw", "shelf", EdgeKind.WAKES),
        ),
    )

    private fun write() = Files.writeString(ir.resolve("Listing.ir.json"), IrJson.encode(listing))

    @Test
    fun `render draws upserts with what they write, the wait for a lock and the retry`() {
        write()
        val out = dir.resolve("diagrams")
        val result = durableAtlas().test(listOf("render", "--ir", ir.toString(), "--out", out.toString(), "--json"))

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val diagram = Json.parseToJsonElement(result.stdout).jsonObject.getValue("diagrams").jsonArray.single().jsonObject
        assertEquals(11, diagram.getValue("nodes").jsonPrimitive.int)
        assertEquals(0, diagram.getValue("unknown").jsonPrimitive.int)

        val drawn = Files.readString(out.resolve("Listing.svg"))
        assertContains(drawn, "<g id=\"stage\" data-kind=\"upsertSearchAttributes\" class=\"node\">")
        assertContains(drawn, "<g id=\"note\" data-kind=\"upsertMemo\" class=\"node\">")
        assertContains(drawn, ">UPSERT SEARCH ATTRIBUTES</text>")
        assertContains(drawn, ">Stage, Seller</text>")
        assertContains(drawn, ">AWAIT · TIMEOUT 1M</text>")
        assertContains(drawn, ">shelf.tryLock()</text>")
        assertContains(drawn, "data-from=\"signal-withdraw\" data-to=\"shelf\" data-kind=\"wakes\"")
        assertContains(drawn, ">Upsert search attributes</text>")
        assertContains(drawn, ">Upsert memo</text>")
        val page = Files.readString(out.resolve("Listing.md"))
        assertContains(page, "n_stage[(\"upsert search attributes#58; Stage, Seller\")]")
        assertContains(page, "note[(\"upsert memo#58; note\")]")
        assertContains(page, "n_done[(\"upsert search attributes\")]")
        assertContains(page, "subgraph n_again_region [\"retry on failure, up to 3 attempts\"]")
    }

    @Test
    fun `overlay lays upserts, the pauses of a retry and what a Nexus operation leaves on their nodes, and names what came from outside`() {
        write()
        var next = 0L
        fun event(type: EventType, attributes: HistoryEvent.Builder.() -> Unit = {}): HistoryEvent =
            HistoryEvent.newBuilder().setEventId(++next).setEventType(type).apply(attributes).build()
        fun publish() = event(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED) {
            activityTaskScheduledEventAttributesBuilder.setActivityType(ActivityType.newBuilder().setName("Publish"))
        }
        val history = History.newBuilder().addAllEvents(
            listOf(
                event(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED) {
                    workflowExecutionStartedEventAttributesBuilder.setWorkflowType(WorkflowType.newBuilder().setName("Listing"))
                },
                event(EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES) {
                    upsertWorkflowSearchAttributesEventAttributesBuilder.searchAttributesBuilder
                        .putIndexedFields("Seller", Payload.getDefaultInstance()).putIndexedFields("Stage", Payload.getDefaultInstance())
                },
                event(EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED) {
                    workflowPropertiesModifiedEventAttributesBuilder.upsertedMemoBuilder.putFields("note", Payload.getDefaultInstance())
                },
                // The lock is free. The first attempt to publish fails, the retry pauses, the second succeeds.
                publish(),
                event(EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED) { activityTaskFailedEventAttributesBuilder.setScheduledEventId(4) },
                event(EventType.EVENT_TYPE_TIMER_STARTED) {
                    timerStartedEventAttributesBuilder.setTimerId("t").setStartToFireTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(1))
                },
                event(EventType.EVENT_TYPE_TIMER_FIRED) { timerFiredEventAttributesBuilder.setStartedEventId(6) },
                publish(),
                event(EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) { activityTaskCompletedEventAttributesBuilder.setScheduledEventId(8) },
                event(EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED) {
                    nexusOperationScheduledEventAttributesBuilder.setService("Greeter").setOperation("hello")
                },
                event(EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED) { nexusOperationStartedEventAttributesBuilder.setScheduledEventId(10) },
                // Someone asks the workflow to cancel while the operation runs; the workflow lets it finish.
                event(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED) { workflowExecutionCancelRequestedEventAttributesBuilder.setIdentity("operator") },
                event(EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED) { nexusOperationCompletedEventAttributesBuilder.setScheduledEventId(10) },
                event(EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES) {
                    upsertWorkflowSearchAttributesEventAttributesBuilder.searchAttributesBuilder.putIndexedFields("Stage", Payload.getDefaultInstance())
                },
                event(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED) { workflowExecutionCompletedEventAttributesBuilder },
            ),
        ).build()
        val histories = Files.createDirectories(dir.resolve("histories"))
        Files.writeString(histories.resolve("Listing.json"), WorkflowExecutionHistory(history).toJson(true))

        val out = dir.resolve("overlay")
        val result = durableAtlas().test(listOf("overlay", "--ir", ir.toString(), "--history", histories.toString(), "--out", out.toString(), "--json"))

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val workflow = Json.parseToJsonElement(result.stdout).jsonObject.getValue("workflows").jsonArray.single().jsonObject
        assertEquals(0.0, workflow.getValue("unmappedPct").jsonPrimitive.content.toDouble())
        val events = workflow.getValue("events").jsonObject
        assertEquals(listOf(10, 0, 0, 1), listOf("mapped", "ambiguous", "unmapped", "recognised").map { events.getValue(it).jsonPrimitive.int })
        assertContains(result.stderr, "Listing: 1 event(s) say what was done to an execution from outside; they are no step of the workflow")

        val coverage = CoverageJson.decode(Files.readString(out.resolve("Listing.coverage.json")))
        assertEquals(emptyList(), coverage.unmapped)
        assertEquals(emptyList(), coverage.ambiguous)
        assertEquals(listOf("WorkflowExecutionCancelRequested" to 12L), coverage.recognised.map { it.eventType to it.eventId })
        assertEquals(emptyList(), coverage.neverHit - "withdraw" - "withdrawn")
        // The retry is entered once and its step is run twice.
        assertEquals(listOf(1, 2), listOf("again", "publish").map { id -> coverage.nodes.single { it.id == id }.hits })
        assertContains(Files.readString(out.resolve("index.md")), "Events from outside the workflow, which are no step of it:\n\n- `Listing.json`, event 12: `WorkflowExecutionCancelRequested`")
        assertContains(Files.readString(out.resolve("Listing.coverage.md")), "1 event of the histories says what was done to an execution from outside")
        assertContains(Files.readString(out.resolve("Listing.coverage.svg")), "data-kind=\"upsertMemo\"")
    }

    @Test
    fun `an overlay without events from outside says nothing of them`() {
        write()
        val history = History.newBuilder().addEvents(
            HistoryEvent.newBuilder().setEventId(1).setEventType(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED).apply {
                workflowExecutionStartedEventAttributesBuilder.setWorkflowType(WorkflowType.newBuilder().setName("Listing"))
            },
        ).build()
        val histories = Files.createDirectories(dir.resolve("histories"))
        Files.writeString(histories.resolve("Listing.json"), WorkflowExecutionHistory(history).toJson(true))

        val out = dir.resolve("overlay")
        val result = durableAtlas().test(listOf("overlay", "--ir", ir.toString(), "--history", histories.toString(), "--out", out.toString(), "--json"))

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        assertFalse("recognised" in result.stdout)
        assertFalse("from outside" in result.stderr)
        assertFalse("recognised" in Files.readString(out.resolve("Listing.coverage.json")))
        assertFalse("from outside" in Files.readString(out.resolve("index.md")))
        assertFalse("from outside" in Files.readString(out.resolve("Listing.coverage.md")))
    }
}
