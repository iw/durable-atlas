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
import io.github.iw.durableatlas.ir.ParentClosePolicy
import io.github.iw.durableatlas.ir.WaitsFor
import io.github.iw.durableatlas.overlay.coverage.CoverageJson
import io.temporal.api.common.v1.ActivityType
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
 * The `render` and `overlay` commands on documents that hold what later versions of the format
 * added: steps that are not waited for, the place where a scope is cancelled with the edges to
 * what it cancels, a request to another workflow to cancel, and a child that names the interface
 * of its stub.
 */
class PatternCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val ir: Path get() = Files.createDirectories(dir.resolve("ir"))

    private fun document(type: String, implementation: String, nodes: List<IrNode>, edges: List<IrEdge>): IrDocument =
        IrDocument(
            commit = "0".repeat(40),
            modelMode = ModelMode.GRADLE,
            workflowType = type,
            interfaceName = "com.example.${implementation.removeSuffix("Impl")}",
            implementation = "com.example.$implementation",
            module = ":workflows",
            entryPoints = listOf(EntryPoint("main", EntryPointKind.WORKFLOW_METHOD, type, "start")),
            nodes = listOf(IrNode("start", NodeKind.START)) + nodes + IrNode("end", NodeKind.END),
            edges = edges,
        )

    private fun write(name: String, document: IrDocument) = Files.writeString(ir.resolve("$name.ir.json"), IrJson.encode(document))

    /** A workflow that starts a van and does not wait for it, sleeps, cancels the scope of the van and asks a route to cancel. */
    private val dispatch = document(
        "Dispatch",
        "DispatchImpl",
        listOf(
            IrNode("van", NodeKind.ACTIVITY, name = "ReserveVan", waitsFor = WaitsFor.NOTHING),
            IrNode("wait", NodeKind.TIMER, duration = "PT30M"),
            IrNode("stop", NodeKind.CANCEL_SCOPE, label = "loading"),
            IrNode("route", NodeKind.CANCEL_EXTERNAL, name = "Route"),
        ),
        listOf(
            IrEdge("start", "van"), IrEdge("van", "wait"), IrEdge("wait", "stop"), IrEdge("stop", "route"), IrEdge("route", "end"),
            IrEdge("stop", "van", EdgeKind.CANCELS),
        ),
    )

    @Test
    fun `render draws what is not waited for and what is cancelled, and links a child by the interface of its stub`() {
        write("Dispatch", dispatch)
        // Two workflows of one type, and a parent that starts a child of each.
        val work = listOf(IrNode("work", NodeKind.ACTIVITY, name = "Work"))
        val flow = listOf(IrEdge("start", "work"), IrEdge("work", "end"))
        write("Notice--com.example.NotifyImpl", document("Notice", "NotifyImpl", work, flow))
        write("Notice--com.example.RemindImpl", document("Notice", "RemindImpl", work, flow))
        val children = listOf(
            IrNode("first", NodeKind.CHILD_WORKFLOW, name = "Notice", interfaceName = "com.example.Notify"),
            IrNode("second", NodeKind.CHILD_WORKFLOW, name = "Notice", interfaceName = "com.example.Remind", waitsFor = WaitsFor.START, parentClosePolicy = ParentClosePolicy.ABANDON),
        )
        write("Parent", document("Parent", "ParentImpl", children, listOf(IrEdge("start", "first"), IrEdge("first", "second"), IrEdge("second", "end"))))

        val out = dir.resolve("diagrams")
        val result = durableAtlas().test(listOf("render", "--ir", ir.toString(), "--out", out.toString(), "--json"))

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val diagrams = Json.parseToJsonElement(result.stdout).jsonObject.getValue("diagrams").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Dispatch", "Notice", "Notice", "Parent"), diagrams.map { it.getValue("workflowType").jsonPrimitive.content })
        assertEquals(listOf(6, 3, 3, 4), diagrams.map { it.getValue("nodes").jsonPrimitive.int })

        val drawn = Files.readString(out.resolve("Dispatch.svg"))
        assertContains(drawn, "data-kind=\"cancelScope\"")
        assertContains(drawn, "data-kind=\"cancelExternal\"")
        assertContains(drawn, "data-from=\"stop\" data-to=\"van\" data-kind=\"cancels\"")
        assertContains(drawn, ">not awaited</text>")
        val page = Files.readString(out.resolve("Dispatch.md"))
        assertContains(page, "n_van[\"ReserveVan<br/>not awaited\"]")
        assertContains(page, "n_stop -.->|\"cancels\"| n_van")
        assertContains(page, "n_route[\"cancel external#58; Route\"]")

        // Each child card links the diagram of the interface it names, of the two of its type.
        val parent = Files.readString(out.resolve("Parent.svg"))
        assertContains(parent, "<a xlink:href=\"Notice--com.example.NotifyImpl.svg\">\n<g id=\"first\"")
        assertContains(parent, "<a xlink:href=\"Notice--com.example.RemindImpl.svg\">\n<g id=\"second\"")
        assertContains(parent, ">CHILD WORKFLOW · ABANDON</text>")
        assertContains(parent, ">waits for start only</text>")
        assertContains(Files.readString(out.resolve("Parent.md")), "- `Notice` (`com.example.Remind`): [Notice--com.example.RemindImpl.md](Notice--com.example.RemindImpl.md)\n")
    }

    @Test
    fun `overlay lays the events of a cancellation over the node that cancels and counts the edge to what it cancels`() {
        write("Dispatch", dispatch)
        var next = 0L
        fun event(type: EventType, attributes: HistoryEvent.Builder.() -> Unit): HistoryEvent =
            HistoryEvent.newBuilder().setEventId(++next).setEventType(type).apply(attributes).build()
        val history = History.newBuilder().addAllEvents(
            listOf(
                event(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED) {
                    workflowExecutionStartedEventAttributesBuilder.setWorkflowType(WorkflowType.newBuilder().setName("Dispatch"))
                },
                event(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED) {
                    activityTaskScheduledEventAttributesBuilder.setActivityType(ActivityType.newBuilder().setName("ReserveVan"))
                },
                event(EventType.EVENT_TYPE_TIMER_STARTED) {
                    timerStartedEventAttributesBuilder.setTimerId("t").setStartToFireTimeout(com.google.protobuf.Duration.newBuilder().setSeconds(1800))
                },
                event(EventType.EVENT_TYPE_TIMER_FIRED) { timerFiredEventAttributesBuilder.setStartedEventId(3) },
                event(EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED) { activityTaskCancelRequestedEventAttributesBuilder.setScheduledEventId(2) },
                event(EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED) {
                    requestCancelExternalWorkflowExecutionInitiatedEventAttributesBuilder.workflowExecutionBuilder.setWorkflowId("route-1")
                },
                event(EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED) {
                    externalWorkflowExecutionCancelRequestedEventAttributesBuilder.setInitiatedEventId(6)
                },
                event(EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED) { activityTaskCanceledEventAttributesBuilder.setScheduledEventId(2) },
                event(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED) { workflowExecutionCompletedEventAttributesBuilder },
            ),
        ).build()
        val histories = Files.createDirectories(dir.resolve("histories"))
        Files.writeString(histories.resolve("Dispatch.json"), WorkflowExecutionHistory(history).toJson(true))

        val out = dir.resolve("overlay")
        val result = durableAtlas().test(listOf("overlay", "--ir", ir.toString(), "--history", histories.toString(), "--out", out.toString(), "--json"))

        assertEquals(ExitCode.SUCCESS, result.statusCode, result.stderr)
        val workflow = Json.parseToJsonElement(result.stdout).jsonObject.getValue("workflows").jsonArray.single().jsonObject
        assertEquals(6, workflow.getValue("nodesHit").jsonPrimitive.int)
        assertEquals(0.0, workflow.getValue("unmappedPct").jsonPrimitive.content.toDouble())
        val coverage = CoverageJson.decode(Files.readString(out.resolve("Dispatch.coverage.json")))
        assertEquals(emptyList(), coverage.unmapped)
        assertEquals(emptyList(), coverage.neverHit)
        val cancels = coverage.edges.single { it.kind == EdgeKind.CANCELS }
        assertEquals(listOf("stop", "van", 1), listOf(cancels.from, cancels.to, cancels.hits))
        assertContains(Files.readString(out.resolve("Dispatch.coverage.md")), "n_stop -.->|\"cancels · ×1\"| n_van")
        assertContains(Files.readString(out.resolve("Dispatch.coverage.svg")), "data-kind=\"cancels\"")
    }
}
