package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.WaitsFor
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.diagram.OverlayOptions
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.history.Outcome
import io.github.iw.durableatlas.overlay.history.StepCancelled
import io.github.iw.durableatlas.overlay.history.StepEnded
import io.github.iw.durableatlas.overlay.history.StepKind
import io.github.iw.durableatlas.overlay.history.StepTaken
import io.github.iw.durableatlas.overlay.mapping.Doubt
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Graphs
import io.github.iw.durableatlas.overlay.testing.Histories
import io.github.iw.durableatlas.overlay.testing.PatternHistories
import io.github.iw.durableatlas.overlay.testing.Profiles
import io.github.iw.durableatlas.render.DiagramFormats
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executions of workflows that wait on queues and promises that handlers feed, start work that
 * nothing waits for, cancel scopes and other workflows, chain promises and retry beside the
 * flow: what the analyser gives for such code, with a history for every way through it.
 */
class PatternOverlayTest {
    @TempDir
    lateinit var dir: Path

    private class Overlaid(val document: IrDocument, val trace: ExecutionTrace, private val names: Profiles) {
        val edges: List<String> get() = names.edges(trace.edges).lines()
        val nodes: List<String> get() = names.nodes(trace.nodes).lines()
        fun name(id: String): String = names.name(id)
    }

    private fun overlay(file: String, way: String): Overlaid {
        val document = Fixtures.pattern(file)
        val mapper = ExecutionMapper(document)
        val history = PatternHistories.all.getValue(file).getValue(way)
        return Overlaid(document, mapper.map(HistoryJson.of(history, "$file.$way.json")), Profiles(mapper))
    }

    @Test
    fun `every way through is laid over its graph without an unmapped event or a doubt`() {
        assertEquals(Fixtures.patternIr.toFile().list()!!.map { it.removeSuffix(".ir.json") }.toSet(), PatternHistories.all.keys)
        val doubts = LinkedHashMap<String, List<Doubt>>()
        val endless = ArrayList<String>()
        for ((file, histories) in PatternHistories.all) {
            for (way in histories.keys) {
                val trace = overlay(file, way).trace
                assertEquals(emptyList(), trace.unmapped, "$file, $way")
                if (trace.closedAt.isEmpty()) endless += "$file.$way"
                if (trace.ambiguous.isNotEmpty()) doubts["$file.$way"] = trace.ambiguous.map { it.doubt }
            }
        }
        assertEquals(emptyMap(), doubts)
        // A promise that a signal fails ends the workflow where it waits for it: no node stands for that end.
        assertEquals(listOf("Approval.rejected"), endless)
    }

    // ---- waits that handlers feed

    @Test
    fun `a wait on a queue is woken by the handlers that fill it, and leaves on its timeout when its timer fires`() {
        assertEquals(
            listOf(
                "activity Report -> end ×1",
                "activity Work@40 -> await items.poll() ×1",
                "activity Work@42 -> await items.cancellablePoll() ×1",
                // The last wait is given nothing: its timer fires.
                "await items.cancellablePoll() -> activity Report timeout ×1",
                "await items.poll() -> activity Work@42 ×1",
                // The signal waits for room in the queue where there is none; there was, both times.
                "await items.put() -> end of signal-add ×2",
                "await items.take() -> activity Work@40 ×1",
                // The second signal arrives while the timer of the second wait runs, and wakes it.
                "signal-add -> await items.poll() wakes ×1",
                "signal-add -> await items.take() wakes ×1",
                "start -> await items.take() ×1",
                "start of signal-add -> await items.put() ×2",
            ),
            overlay("Inbox", "all").edges,
        )
        // `?: return`: the time was up, and nothing came.
        assertTrue("await items.poll() -> end timeout ×1" in overlay("Inbox", "one").edges)
        // What is there before it is asked for starts no timer; the update that brought it woke the first wait twice.
        val pushed = overlay("Inbox", "pushed")
        assertTrue("update-push -> await items.take() wakes ×2" in pushed.edges)
        assertTrue("await items.cancellablePoll() -> end ×1" in pushed.edges)
        assertEquals(listOf("TimerStarted"), pushed.trace.matches.filter { it.nodes.any { node -> pushed.name(node).startsWith("await") } }.map { it.eventType })
    }

    @Test
    fun `a wait on a promise is woken by the handler that completes it`() {
        assertEquals(
            listOf(
                "activity Prepare -> await decision.get() ×1",
                "activity Work -> await second.cancellableGet() ×1",
                "await decision.get() -> activity Work ×1",
                "await second.cancellableGet() -> end ×1",
                "signal-approve -> await decision.get() wakes ×1",
                // The same signal completes the second promise the second time.
                "signal-approve -> await second.cancellableGet() wakes ×1",
                "start -> activity Prepare ×1",
                "start of signal-approve -> end of signal-approve ×2",
            ),
            overlay("Approval", "approved").edges,
        )
        // A promise that is failed ends the workflow: the flow stops at the await, which is passed without an event.
        val rejected = overlay("Approval", "rejected")
        assertEquals(listOf("start -> activity Prepare ×1", "start of signal-reject -> end of signal-reject ×1"), rejected.edges)
        assertEquals(emptyList(), rejected.trace.closedAt)
    }

    // ---- work that nothing waits for

    @Test
    fun `the flow goes on from a step that is not waited for, and how that step ends steers nothing`() {
        val fine = overlay("Unjoined", "fine")
        assertEquals(
            listOf(
                // Announce is not waited for: the flow goes on while it runs.
                "activity Announce -> parallel@32 ×1",
                "activity Check -> parallel@37 ×1",
                "activity CleanUp -> end ×1",
                "activity Load -> activity Weigh ×1",
                "activity Prepare -> activity Work ×1",
                // And on from the last one that is not waited for.
                "activity Undo -> activity CleanUp ×1",
                "activity Weigh -> branch tasks.weigh(id) ×1",
                "activity Work -> parallel@37 ×1",
                "branch tasks.weigh(id) -> activity Audit 'no' ×1",
                "parallel@32 -> activity Load ×1",
                // Past the region that nothing waits for, at once.
                "parallel@32 -> parallel@37 ×1",
                "parallel@37 -> activity Check ×1",
                // The flow's own lane of the region it waits for: a step it does not wait for, and one it does.
                "parallel@37 -> activity Prepare ×1",
                "parallel@37 -> activity Undo ×1",
                "start -> activity Announce ×1",
            ),
            fine.edges,
        )
        // Three steps fail that nobody waits for: the flow is where it was without them.
        val unseen = overlay("Unjoined", "unseen")
        assertEquals(fine.edges.filterNot { "Weigh ->" in it || "-> activity Audit" in it }, unseen.edges)
        assertEquals(emptyList(), unseen.trace.ambiguous)

        val started = overlay("Detach", "started")
        assertEquals(
            listOf(
                "childWorkflow Courier@45 -> childWorkflow Courier@48 ×1",
                "childWorkflow Courier@48 -> childWorkflow Courier@50 ×1",
                "childWorkflow Courier@50 -> end ×1",
                "start -> childWorkflow Courier@45 ×1",
            ),
            started.edges,
        )
        assertEquals(listOf(WaitsFor.START, WaitsFor.NOTHING, null), started.document.nodes.filter { it.kind == NodeKind.CHILD_WORKFLOW }.sortedBy { it.loc?.line }.map { it.waitsFor })
    }

    @Test
    fun `a region that is not waited for is entered by its lanes while the flow goes on past it`() {
        val fine = overlay("Unjoined", "fine")
        val region = fine.document.nodes.single { it.kind == NodeKind.PARALLEL && it.waitsFor == WaitsFor.NOTHING }
        val name = fine.name(region.id)
        // The flow leaves the region where it enters it, before the lane has done anything.
        assertTrue("$name -> ${fine.name(fine.document.edges.single { it.from == region.id && it.kind == EdgeKind.FLOW }.to)} ×1" in fine.edges)
        assertTrue("$name -> activity Load ×1" in fine.edges)
        // The lane ends on its own: nothing leads on from where it ends, and the workflow completes all the same.
        assertTrue(fine.edges.none { it.startsWith("activity Audit ->") })
        assertEquals(1, fine.trace.closedAt.size)
        // A lane that is still running when the workflow is done stays where it was.
        val unseen = overlay("Unjoined", "unseen")
        assertTrue("activity Load -> activity Weigh ×1" in unseen.edges)
        assertEquals(1, unseen.trace.closedAt.size)
    }

    // ---- cancellation

    @Test
    fun `an activity that is asked to cancel brings the flow to the node that cancels its scope`() {
        val cancelled = overlay("CancelLater", "cancelled")
        assertEquals(
            listOf(
                "activity Prepare -> timer ×1",
                "activity Report -> end ×1",
                // The request to cancel the activity is the scope being cancelled.
                "cancelScope scope -> activity Prepare cancels ×1",
                "cancelScope scope -> activity Report ×1",
                "start -> activity Prepare ×1",
                "timer -> cancelScope scope ×1",
            ),
            cancelled.edges,
        )
        val request = cancelled.trace.matches.single { it.eventType == "ActivityTaskCancelRequested" }
        assertEquals(listOf("cancelScope scope"), request.nodes.map(cancelled::name))

        // Nothing ran any more when the scope was cancelled: the node is passed, and its edge to the activity is not taken.
        val done = overlay("CancelLater", "done-first")
        assertEquals(cancelled.edges.filterNot { "cancels" in it }, done.edges)
        assertTrue("cancelScope scope ×1" in done.nodes)
    }

    @Test
    fun `every request to cancel is one more hit of the edge to what the scope holds`() {
        val race = overlay("Race", "three")
        assertEquals(
            listOf(
                "activity Prepare -> parallel ×3",
                "activity Report -> end ×1",
                "cancelScope scope -> activity Report ×1",
                // Two of the three still ran.
                "cancelScope scope -> loop for each id cancels ×2",
                "loop for each id -> cancelScope scope ×1",
                "loop for each id -> parallel ×3",
                "parallel -> activity Prepare ×3",
                "parallel -> loop for each id ×3",
                "start -> loop for each id ×1",
            ),
            race.edges,
        )
        // Two requests, one place: the second finds the flow at the node already.
        assertEquals(listOf("cancelScope scope", "cancelScope scope"), race.trace.matches.filter { it.eventType == "ActivityTaskCancelRequested" }.flatMap { it.nodes }.map(race::name))
        assertTrue("cancelScope scope ×1" in race.nodes)
    }

    @Test
    fun `a timer that is cancelled, or an activity, brings the handler that cancels the scope to its node`() {
        val stopped = overlay("Stoppable", "stopped")
        assertEquals(
            listOf(
                "activity CleanUp -> end ×1",
                "activity Prepare -> timer ×1",
                "cancelScope scope -> end of signal-stop ×1",
                // The handler cancels the scope, which the cancelled timer shows.
                "cancelScope scope -> timer cancels ×1",
                "start -> activity Prepare ×1",
                "start of signal-stop -> cancelScope scope ×1",
                // And the workflow method goes on where the cancellation is caught.
                "timer -> activity CleanUp exception 'CanceledFailure' ×1",
            ),
            stopped.edges,
        )
        assertEquals(listOf("cancelScope scope"), stopped.trace.matches.single { it.eventType == "TimerCanceled" }.nodes.map(stopped::name))

        val atWork = overlay("Stoppable", "stopped-at-work")
        assertTrue("cancelScope scope -> activity Work cancels ×1" in atWork.edges)
        assertTrue("activity Work -> activity CleanUp exception 'CanceledFailure' ×1" in atWork.edges)

        // Without the signal the handler is not there, and neither is any of this.
        val through = overlay("Stoppable", "ran-through")
        assertTrue(through.edges.none { "cancelScope" in it })
    }

    @Test
    fun `while what a scope holds still runs, the flow cannot have passed the node that cancels it without an event`() {
        val cancelled = overlay("SelfStop", "cancelled")
        assertEquals(
            listOf(
                "activity Check -> branch !tasks.check(id) ×1",
                "activity Prepare -> timer ×1",
                "activity Report -> cancelScope scopes.first() ×1",
                "activity Work -> activity Check ×1",
                // The request to cancel the first activity says which way the branch went.
                "branch !tasks.check(id) -> cancelScope current scope@33 'yes' ×1",
                "cancelScope current scope@33 -> activity Prepare ×1",
                "cancelScope current scope@33 -> activity Work cancels ×1",
                "cancelScope current scope@43 -> end ×1",
                "cancelScope own -> activity Prepare cancels ×1",
                "cancelScope own -> activity Report ×1",
                "cancelScope scopes.first() -> cancelScope current scope@43 ×1",
                "start -> activity Work ×1",
                "timer -> cancelScope own ×1",
            ),
            cancelled.edges,
        )
        // The third request is for an activity whose scope the document cannot name: no node cancels it, so it is laid on the activity itself.
        val requests = cancelled.trace.matches.filter { it.eventType == "ActivityTaskCancelRequested" }.map { it.nodes.single() }.map(cancelled::name)
        assertEquals(listOf("cancelScope current scope@33", "cancelScope own", "activity Report"), requests)
        assertEquals(emptyList(), cancelled.trace.unmapped)

        // The check held, so the scope was not cancelled: its activity ran on, and no request was written.
        val checked = overlay("SelfStop", "checked")
        assertTrue("branch !tasks.check(id) -> activity Prepare 'no' ×1" in checked.edges)
        assertTrue(checked.edges.none { "cancelScope current scope@33" in it })
        assertEquals(emptyList(), checked.trace.ambiguous)
    }

    @Test
    fun `asking another workflow to cancel is a step with an end, and a child is cancelled through its scope`() {
        val asked = overlay("CallOff", "asked")
        assertEquals(
            listOf(
                "cancelExternal -> cancelExternal Carrier ×1",
                "cancelExternal Carrier -> end ×1",
                "start -> cancelExternal ×1",
            ),
            asked.edges,
        )
        val history = HistoryJson.of(PatternHistories.all.getValue("CallOff").getValue("asked"), "CallOff.asked.json")
        assertEquals(
            listOf(
                StepTaken(2, "RequestCancelExternalWorkflowExecutionInitiated", StepKind.CANCEL_EXTERNAL, waits = true),
                StepEnded(3, "ExternalWorkflowExecutionCancelRequested", step = 2, outcome = Outcome.COMPLETED),
                StepTaken(4, "RequestCancelExternalWorkflowExecutionInitiated", StepKind.CANCEL_EXTERNAL, waits = true),
                StepEnded(5, "RequestCancelExternalWorkflowExecutionFailed", step = 4, outcome = Outcome.FAILED),
            ),
            history.occurrences.subList(1, 5),
        )

        // A workflow without such a node took a step that has none.
        val plain = Histories("Courier").apply { ran("Work"); cancelDelivered(cancelRequested("other")); completed() }.build()
        val stray = ExecutionMapper(Fixtures.pattern("Courier")).map(HistoryJson.of(plain, "Courier.stray.json"))
        assertEquals(listOf("RequestCancelExternalWorkflowExecutionInitiated"), stray.unmapped.map { it.eventType })

        // A child that is started in a scope and cancelled with it: the request names the child, and belongs to the scope's node.
        val document = Graphs("Tracked")
            .node("child", NodeKind.CHILD_WORKFLOW, "Courier") { it.copy(waitsFor = WaitsFor.NOTHING) }
            .node("wait", NodeKind.TIMER) { it.copy(duration = "PT1M") }
            .node("stop", NodeKind.CANCEL_SCOPE) { it.copy(label = "scope") }
            .node("end", NodeKind.END)
            .flow("start", "child", "wait", "stop", "end")
            .edge("stop", "child", EdgeKind.CANCELS)
            .build()
        val events = Histories("Tracked").apply {
            val child = child("Courier", "courier-1")
            childStarted(child)
            timerFired(timer(60))
            val request = cancelRequested("courier-1", child = true)
            cancelDelivered(request)
            childCanceled(child)
            completed()
        }.build()
        val read = HistoryJson.of(events, "Tracked.json")
        assertEquals(listOf(StepCancelled(6, "RequestCancelExternalWorkflowExecutionInitiated", step = 2, noted = true)), read.occurrences.filterIsInstance<StepCancelled>())
        // That the request arrived is no end of a step: the child ends with an event of its own.
        assertEquals(listOf(5L, 8L), read.occurrences.filterIsInstance<StepEnded>().map { it.eventId })
        val mapper = ExecutionMapper(document)
        val trace = mapper.map(read)
        assertEquals(emptyList(), trace.unmapped)
        assertEquals(
            listOf("childWorkflow Courier -> timer ×1", "cancelScope scope -> childWorkflow Courier cancels ×1", "cancelScope scope -> end ×1", "start -> childWorkflow Courier ×1", "timer -> cancelScope scope ×1").sorted(),
            Profiles(mapper).edges(trace.edges).lines(),
        )
    }

    @Test
    fun `a request to cancel an activity that no node of the graph cancels is laid on the activity and moves nothing`() {
        // The workflow itself is cancelled while the scope's activity runs: no place can come to the node that cancels the scope.
        val events = Histories("CancelLater").apply {
            val prepare = activity("Prepare")
            timer(60)
            activityCancelRequested(prepare)
            activityCanceled(prepare)
            canceled()
        }.build()
        val mapper = ExecutionMapper(Fixtures.pattern("CancelLater"))
        val trace = mapper.map(HistoryJson.of(events, "CancelLater.outside.json"))
        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(listOf("activity Prepare -> timer ×1", "start -> activity Prepare ×1"), Profiles(mapper).edges(trace.edges).lines())
        // The request is about the activity, and that is all the history says of it.
        val activity = mapper.document.nodes.single { it.name == "Prepare" }.id
        assertEquals(listOf(listOf(activity)), trace.matches.filter { it.eventType == "ActivityTaskCancelRequested" }.map { it.nodes })
        assertTrue("cancelScope scope" !in Profiles(mapper).nodes(trace.nodes))
    }

    // ---- what is chained to a promise, and what is tried again beside the flow

    @Test
    fun `what is chained to a promise is taken after the step it follows, on the exception edge where that failed`() {
        assertEquals(
            listOf(
                "activity Check -> activity Prepare@36 ×1",
                "activity Prepare@32 -> activity Work ×1",
                "activity Prepare@36 -> branch failure != null ×1",
                "activity Report@31 -> activity CleanUp ×1",
                "activity Work -> parallel@32 ×1",
                "branch failure != null -> parallel@32 'no' ×1",
                "parallel@31 -> activity Report@31 ×1",
                "parallel@31 -> parallel@32 ×1",
                "parallel@32 -> activity Check ×1",
                "parallel@32 -> activity Prepare@32 ×1",
                "parallel@32 -> end ×1",
                "start -> parallel@31 ×1",
            ),
            overlay("Chains", "fine").edges,
        )
        assertEquals(
            listOf(
                // `handle`: reached on the exception edge, past the step that was never started.
                "activity Check -> branch failure != null exception 'RuntimeException' ×1",
                // `exceptionally`: taken because the step before it failed.
                "activity Prepare@32 -> activity Undo exception 'Throwable' ×1",
                "activity Report@37 -> parallel@32 ×1",
                "activity Undo -> parallel@32 ×1",
                "branch failure != null -> activity Report@37 'yes' ×1",
                "parallel@31 -> activity Report@31 ×1",
                "parallel@31 -> parallel@32 ×1",
                "parallel@32 -> activity Check ×1",
                "parallel@32 -> activity Prepare@32 ×1",
                "parallel@32 -> end ×1",
                "start -> parallel@31 ×1",
            ),
            overlay("Chains", "failed").edges,
        )
    }

    @Test
    fun `a retry beside the flow goes round once for each attempt`() {
        assertEquals(
            listOf(
                "activity Check -> parallel@32 ×1",
                // The first loop took two rounds, and so did the second.
                "activity Prepare -> loop retry on failure, up to 3 attempts, for up to 10m ×2",
                "activity Report -> loop retry on failure ×2",
                "activity Work -> activity Report ×2",
                "loop retry on failure -> activity Work ×2",
                "loop retry on failure -> parallel@35 ×1",
                "loop retry on failure, up to 3 attempts, for up to 10m -> activity Prepare ×2",
                "loop retry on failure, up to 3 attempts, for up to 10m -> parallel@32 ×1",
                "parallel@32 -> activity Check ×1",
                "parallel@32 -> loop retry on failure, up to 3 attempts, for up to 10m ×1",
                "parallel@32 -> parallel@35 ×1",
                "parallel@35 -> end ×1",
                "parallel@35 -> loop retry on failure ×1",
                "start -> parallel@32 ×1",
            ),
            overlay("AsyncRetries", "retried").edges,
        )
        assertTrue("activity Prepare -> loop retry on failure, up to 3 attempts, for up to 10m ×1" in overlay("AsyncRetries", "at-once").edges)
    }

    // ---- the documents as a directory

    @Test
    fun `the documents are laid over as a directory, two of them of one workflow type`() {
        val histories = dir.resolve("histories")
        Files.createDirectories(histories)
        for ((file, ways) in PatternHistories.all) {
            for ((way, history) in ways) Files.writeString(histories.resolve("$file.$way.json"), HistoryJson.encode(history))
        }
        val out = dir.resolve("out")
        val summary = OverlayDirectory.write(Fixtures.patternIr, histories, out, OverlayOptions(formats = listOf(DiagramFormats.SVG, DiagramFormats.MERMAID)))

        // The histories of a type that two documents share fit neither for certain, and are left out with a reason.
        val shared = PatternHistories.all.filterKeys { it.startsWith("Notice--") }.values.sumOf { it.size }
        assertEquals(shared, summary.unmatched.count { it.file.startsWith("Notice--") && "several workflow documents" in it.reason })
        assertEquals(shared, summary.unmatched.size)
        val laid = PatternHistories.all.values.sumOf { it.size } - shared
        assertEquals(laid, summary.workflows.sumOf { it.coverage.executions })
        assertEquals(0, summary.workflows.sumOf { it.coverage.unmapped.size })

        // The edges to what a cancelled scope reaches are counted like any other, and drawn in both formats.
        val later = summary.workflows.single { it.workflowType == "CancelLater" }
        val cancels = later.coverage.edges.single { it.kind == EdgeKind.CANCELS }
        assertEquals(1, cancels.hits)
        assertEquals(1, cancels.executions)
        val page = Files.readString(out.resolve(later.files.single { it.format == DiagramFormats.MERMAID.name }.file))
        assertContains(page, "${cancels.from} -.->|\"cancels · ×1\"| ${cancels.to}\n")
        val drawing = Files.readString(out.resolve(later.files.single { it.format == DiagramFormats.SVG.name }.file))
        assertContains(drawing, "data-kind=\"cancels\"")
        assertContains(IrJson.encode(Fixtures.pattern("CancelLater")), "\"kind\": \"cancels\"")
    }
}
