package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.Join
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.WaitsFor
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.overlay.coverage.CoverageBuilder
import io.github.iw.durableatlas.overlay.coverage.CoverageJson
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.history.StepNoted
import io.github.iw.durableatlas.overlay.mapping.Doubt
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Graphs
import io.github.iw.durableatlas.overlay.testing.Histories
import io.github.iw.durableatlas.overlay.testing.OpenHistories
import io.github.iw.durableatlas.overlay.testing.Profiles
import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.History
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Executions of workflows that run races, start steps with calls that give promises, wait for
 * a promise a time at most, wait for room in a queue and on what their own code lets go of,
 * wait for the start of a child inside what nothing waits for, and name some of what an upsert
 * writes: what the analyser gives for such code, with a history for every way through it. And
 * the events that say a step has started, which are laid on the node of that step.
 */
class OpenOverlayTest {
    private class Overlaid(val document: IrDocument, val trace: ExecutionTrace, private val names: Profiles) {
        val edges: List<String> get() = names.edges(trace.edges).lines()
        val nodes: List<String> get() = names.nodes(trace.nodes).lines()
        fun name(id: String): String = names.name(id)

        /** The nodes the events of the type [eventType] were laid on, in the order of the history. */
        fun laidOn(eventType: String): List<String> = trace.matches.filter { it.eventType == eventType }.map { it.nodes.joinToString(" or ", transform = ::name) }
    }

    private fun overlay(document: IrDocument, history: History): Overlaid {
        val mapper = ExecutionMapper(document)
        return Overlaid(document, mapper.map(HistoryJson.of(history, "${document.workflowType}.json")), Profiles(mapper))
    }

    private fun overlay(file: String, way: String): Overlaid = overlay(Fixtures.open(file), OpenHistories.all.getValue(file).getValue(way))

    private fun history(workflowType: String, events: Histories.() -> Unit): History = Histories(workflowType).apply(events).build()

    @Test
    fun `every way through is laid over its graph without an unmapped event or a doubt`() {
        assertEquals(Fixtures.openIr.toFile().list()!!.map { it.removeSuffix(".ir.json") }.toSet(), OpenHistories.all.keys)
        val doubts = LinkedHashMap<String, List<Doubt>>()
        val endless = ArrayList<String>()
        for ((file, histories) in OpenHistories.all) {
            for (way in histories.keys) {
                val trace = overlay(file, way).trace
                assertEquals(emptyList(), trace.unmapped, "$file, $way")
                if (trace.closedAt.isEmpty()) endless += "$file.$way"
                if (trace.ambiguous.isNotEmpty()) doubts["$file.$way"] = trace.ambiguous.map { it.doubt }
            }
        }
        assertEquals(emptyMap(), doubts)
        // A wait that gives up with a failure nothing catches ends the workflow where it stands: no node stands for that end.
        assertEquals(listOf("Patience.expired"), endless)
    }

    // ---- races

    @Test
    fun `a race is left when its first lane ends, and a lane that ends later is counted to its end then`() {
        val race = overlay("Fastest", "loser-ends")
        assertEquals(
            listOf(
                "activity Fetch@28 -> parallel@28 ×1",
                "activity Fetch@32 -> parallel@32 ×1",
                "activity Notify -> parallel@32 ×1",
                // The store lost; it ended while the lanes of the second region ran.
                "activity Store@29 -> parallel@28 ×1",
                "activity Store@33 -> parallel@32 ×1",
                "parallel@28 -> activity Fetch@28 ×1",
                // The region was left once, when the fetch was done.
                "parallel@28 -> activity Notify ×1",
                "parallel@28 -> activity Store@29 ×1",
                "parallel@32 -> activity Fetch@32 ×1",
                "parallel@32 -> activity Store@33 ×1",
                "parallel@32 -> end ×1",
                "start -> parallel@28 ×1",
            ),
            race.edges,
        )
        // The notice was scheduled while the store still ran: only a race lets the flow get there.
        assertEquals(listOf("activity Fetch@28", "activity Store@29", "activity Notify", "activity Fetch@32", "activity Store@33"), race.laidOn("ActivityTaskScheduled"))
    }

    @Test
    fun `a lane that lost a race and never ends is not counted to its end`() {
        val race = overlay("Fastest", "loser-runs-on")
        assertContains(race.edges, "activity Store@29 -> parallel@28 ×1")
        assertContains(race.edges, "parallel@28 -> activity Notify ×1")
        // The fetch was started, and that is all.
        assertContains(race.edges, "parallel@28 -> activity Fetch@28 ×1")
        assertTrue(race.edges.none { it.startsWith("activity Fetch@28 ->") })
        assertTrue("activity Fetch@28 ×1" in race.nodes)
    }

    @Test
    fun `the scope that is cancelled after a race cancels the lane that lost, which does not get to its end`() {
        val fetch = overlay("Winner", "fetch")
        assertEquals(
            listOf(
                "activity Fetch -> parallel ×1",
                "activity Store@39 -> end ×1",
                "cancelScope scope -> activity Store@39 ×1",
                // The timer of the other lane was cancelled with the scope.
                "cancelScope scope -> parallel cancels ×1",
                "parallel -> activity Fetch ×1",
                "parallel -> cancelScope scope ×1",
                "parallel -> timer ×1",
                "start -> parallel ×1",
            ),
            fetch.edges,
        )
        assertEquals(listOf("cancelScope scope"), fetch.laidOn("TimerCanceled"))
        // The store of the lane that lost never ran: the one that did is the one after the scope.
        assertEquals(listOf("activity Fetch", "activity Store@39"), fetch.laidOn("ActivityTaskScheduled"))

        val store = overlay("Winner", "store")
        assertContains(store.edges, "timer -> activity Store@33 ×1")
        assertContains(store.edges, "activity Store@33 -> parallel ×1")
        assertContains(store.edges, "cancelScope scope -> parallel cancels ×1")
        assertTrue(store.edges.none { it.startsWith("activity Fetch ->") })
        assertEquals(listOf("cancelScope scope"), store.laidOn("ActivityTaskCancelRequested"))
    }

    @Test
    fun `a lane that lost goes on in its lane as the history says, and does not go on past the region`() {
        // Two lanes of two steps each, and after the race a step of the same name as the last of the first lane.
        val document = Graphs("Relay").node("race", NodeKind.PARALLEL) { it.copy(join = Join.FIRST) }
            .activity("a1", "A", parent = "race").activity("a2", "Wrap", parent = "race")
            .activity("b1", "B", parent = "race").activity("b2", "Check", parent = "race")
            .activity("after", "Wrap").node("end", NodeKind.END)
            .flow("start", "race", "after", "end").flow("a1", "a2").flow("b1", "b2")
            .build()
        val relay = overlay(
            document,
            history("Relay") {
                val a = activity("A")
                val b = activity("B")
                activityCompleted(b)
                val check = activity("Check")
                activityCompleted(check)
                // The second lane is done: the flow goes on. The first is still at its first step.
                val wrap = activity("Wrap")
                activityCompleted(a)
                // And now does its second, which is called like the step after the race.
                val late = activity("Wrap")
                activityCompleted(wrap)
                activityCompleted(late)
                completed()
            },
        )
        assertEquals(emptyList(), relay.trace.unmapped)
        assertEquals(emptyList(), relay.trace.ambiguous)
        // The first `Wrap` is the step after the race; the second is the lane's own, though the flow has long gone on.
        assertEquals(listOf("a1", "b1", "b2", "after", "a2"), relay.trace.matches.filter { it.eventType == "ActivityTaskScheduled" }.map { it.nodes.single() })
        assertEquals(
            listOf("a1 -> a2 ×1", "a2 -> race ×1", "after -> end ×1", "b1 -> b2 ×1", "b2 -> race ×1", "race -> a1 ×1", "race -> after ×1", "race -> b1 ×1", "start -> race ×1"),
            relay.trace.edges.map { (edge, hits) -> "${edge.from} -> ${edge.to} ×${hits.count}" }.sorted(),
        )
    }

    @Test
    fun `a timer that loses a race is left running, and one that wins takes the flow on`() {
        val inTime = overlay("Deadline", "in-time")
        assertContains(inTime.edges, "activity Fetch -> parallel ×1")
        assertContains(inTime.edges, "branch !work.isCompleted -> end 'no' ×1")
        assertTrue(inTime.edges.none { it.startsWith("timer ->") })

        val late = overlay("Deadline", "late")
        assertContains(late.edges, "timer -> parallel ×1")
        assertContains(late.edges, "branch !work.isCompleted -> activity Notify 'yes' ×1")
        // The fetch ended while the notice ran, and is counted to the end of its lane then.
        assertContains(late.edges, "activity Fetch -> parallel ×1")
    }

    // ---- promises of calls that start a step, and the events that say a step has started

    @Test
    fun `an activity that a worker picked up and a child that has started are laid on their nodes and move nothing`() {
        val handles = overlay("Handles", "all")
        assertEquals(listOf("childWorkflow Errand@35", "childWorkflow Errand@38"), handles.laidOn("ChildWorkflowExecutionStarted"))
        // The first child is not waited for beyond its start: the flow goes on to the next one from it.
        assertContains(handles.edges, "childWorkflow Errand@35 -> parallel@38 ×1")
        assertContains(handles.edges, "activity Notify@33 -> childWorkflow Errand@35 ×1")

        val fastest = overlay("Fastest", "loser-ends")
        assertEquals(listOf("activity Fetch@28", "activity Store@29"), fastest.laidOn("ActivityTaskStarted"))
        // 1 start, 5 activities scheduled, 2 of them started, and the end.
        assertEquals(9, fastest.trace.mapped)
        val read = HistoryJson.of(OpenHistories.all.getValue("Fastest").getValue("loser-ends"), "Fastest.json")
        assertEquals(listOf(StepNoted(4, "ActivityTaskStarted", step = 2), StepNoted(5, "ActivityTaskStarted", step = 3)), read.occurrences.filterIsInstance<StepNoted>())
    }

    @Test
    fun `what is said of a step that has no node is neither mapped nor reported`() {
        val trace = overlay(
            Fixtures.open("Soonest"),
            history("Soonest") {
                val other = activity("Elsewhere")
                activityStarted(other)
                activityCompleted(other)
                val child = child("Unknown")
                childStarted(child)
                childCompleted(child)
                completed()
            },
        ).trace
        // The steps themselves are reported; that they started says nothing more.
        assertEquals(listOf("ActivityTaskScheduled", "StartChildWorkflowExecutionInitiated"), trace.unmapped.map { it.eventType })
        assertTrue(trace.matches.none { it.eventType.endsWith("Started") && it.eventType != "WorkflowExecutionStarted" })
    }

    @Test
    fun `an update that was turned down is recognised and is no step`() {
        val trace = overlay(
            Fixtures.open("Permits"),
            history("Permits") {
                bare(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_REJECTED)
                ran("Fetch")
                ran("Store")
                ran("Notify")
                completed()
            },
        ).trace
        assertEquals(listOf("WorkflowExecutionUpdateRejected"), trace.recognised.map { it.eventType })
        assertEquals(emptyList(), trace.unmapped)
        // The handler did not run.
        assertEquals(setOf("main"), trace.entryPoints.keys)
    }

    @Test
    fun `what became of a request to cancel a child is laid on the child`() {
        val document = Graphs("Tracked").node("child", NodeKind.CHILD_WORKFLOW, "Courier").node("wait", NodeKind.TIMER) { it.copy(duration = "PT1M") }
            .node("end", NodeKind.END)
            .flow("start", "child", "wait", "end")
            .build()
        val tracked = overlay(
            document,
            history("Tracked") {
                val child = child("Courier", "courier-1")
                childStarted(child)
                val request = cancelRequested("courier-1", child = true)
                cancelDelivered(request)
                childCanceled(child)
                terminated()
            },
        )
        // No node cancels the child: the request and what became of it are about the child, and that is where they are laid.
        assertEquals(
            listOf("StartChildWorkflowExecutionInitiated", "ChildWorkflowExecutionStarted", "RequestCancelExternalWorkflowExecutionInitiated", "ExternalWorkflowExecutionCancelRequested"),
            tracked.trace.matches.filter { it.nodes == listOf("child") }.map { it.eventType },
        )
        assertEquals(emptyList(), tracked.trace.unmapped)
    }

    @Test
    fun `timers and waits that run beside the flow are laid on their lanes`() {
        val pauses = overlay("Pauses", "released")
        assertContains(pauses.edges, "timer@33 -> parallel@33 ×1")
        assertContains(pauses.edges, "await released@34 -> parallel@33 ×1")
        // What is chained to a promise follows the step the promise stands for.
        assertContains(pauses.edges, "timer@37 -> activity Store ×1")
        assertContains(pauses.edges, "await released@38 -> activity Notify ×1")
        // The timer nobody waits for holds nothing up: the workflow ends while it runs.
        assertContains(pauses.edges, "timer@41 -> end ×1")
        assertEquals(listOf("timer@33", "timer@37", "timer@40", "timer@41"), pauses.laidOn("TimerStarted"))
    }

    // ---- a promise waited for a time at most

    @Test
    fun `a wait for a promise takes the timer of its time, and leaves on its timeout when the timer fires`() {
        val approved = overlay("Patience", "approved")
        assertContains(approved.edges, "await approval.get()@34 -> activity Store ×1")
        assertContains(approved.edges, "signal-approve -> await approval.get()@34 wakes ×1")
        assertTrue(approved.edges.none { "timeout" in it })

        val late = overlay("Patience", "late")
        assertContains(late.edges, "await approval.get()@34 -> activity Notify timeout ×1")
        assertContains(late.edges, "signal-approve -> await approval.cancellableGet() wakes ×1")
        assertEquals(listOf("await approval.get()@34", "await approval.cancellableGet()"), late.laidOn("TimerStarted"))
        // The last wait finds the promise done: no timer, and no timeout.
        assertContains(late.edges, "await approval.get()@41 -> activity Fetch ×1")
    }

    // ---- more waits

    @Test
    fun `a wait for room in a queue leaves nothing unless it has a time, and is woken by whoever takes something out`() {
        val room = overlay("Buffer", "room")
        assertEquals(
            listOf(
                "activity Store -> end ×1",
                "await items.cancellableOffer() -> activity Store ×1",
                "await items.cancellablePut() -> await items.cancellableOffer() ×1",
                "await items.offer() -> await items.cancellablePut() ×1",
                "await items.put()@37 -> await items.offer() ×1",
                // The signal that puts something in passes its wait as well.
                "await items.put()@49 -> end of signal-add ×1",
                "start -> await items.put()@37 ×1",
                "start of signal-add -> await items.put()@49 ×1",
            ),
            room.edges,
        )
        assertContains(overlay("Buffer", "full").edges, "await items.offer() -> activity Fetch timeout ×1")

        val drained = overlay("Buffer", "drained")
        assertContains(drained.edges, "signal-drain -> await items.offer() wakes ×1")
        assertContains(drained.edges, "signal-drain -> await items.cancellableOffer() wakes ×1")
        assertEquals(listOf("await items.offer()", "await items.cancellableOffer()"), drained.laidOn("TimerStarted"))
    }

    @Test
    fun `a handler the workflow registered wakes the waits on what the workflow method keeps in variables`() {
        val fed = overlay("Listener", "fed")
        assertEquals(
            listOf(
                "activity Store -> await done.get() ×1",
                "await done.get() -> end ×1",
                "await items.take() -> activity Store ×1",
                "dynamic-signal -> await done.get() wakes ×1",
                "dynamic-signal -> await items.take() wakes ×1",
                "start -> await items.take() ×1",
                "start of dynamic-signal -> end of dynamic-signal ×2",
            ),
            fed.edges,
        )
    }

    @Test
    fun `what the workflow method wakes is counted where the history shows that a wait was ended and by no handler`() {
        // Two lanes: one waits for a permit for a minute at most, the other holds it and lets go. A signal can let go of it as well.
        val document = Graphs("Turns").node("both", NodeKind.PARALLEL)
            .node("wait", NodeKind.AWAIT, parent = "both") { it.copy(timeout = "PT1M", reads = listOf("permits"), label = "permits.tryAcquire()") }
            .activity("a", "A", parent = "both").activity("b", "B", parent = "both")
            .activity("late", "Late").node("end", NodeKind.END)
            .flow("start", "both", "end").flow("wait", "a").edge("wait", "late", EdgeKind.TIMEOUT).flow("late", "end")
            .handler("signal-grant", EntryPointKind.SIGNAL, "grant", writes = listOf("permits"))
            .edge("signal-grant", "wait", EdgeKind.WAKES)
            .edge("main", "wait", EdgeKind.WAKES, "permits")
            .build()
        fun wakes(events: Histories.() -> Unit): List<String> =
            overlay(document, history("Turns", events)).trace.edges.filterKeys { it.kind == EdgeKind.WAKES }.map { (edge, hits) -> "${edge.from} ×${hits.count}" }.sorted()

        // The other lane is done and the timer is cancelled, with no signal in between: the workflow's own code ended the wait.
        assertEquals(
            listOf("main ×1"),
            wakes {
                val b = activity("B")
                val wait = timer(60)
                activityCompleted(b)
                timerCanceled(wait)
                ran("A")
                completed()
            },
        )
        // A signal came meanwhile: it is what the history shows, and the count goes to it.
        assertEquals(
            listOf("signal-grant ×1"),
            wakes {
                val b = activity("B")
                val wait = timer(60)
                signal("grant")
                timerCanceled(wait)
                ran("A")
                activityCompleted(b)
                completed()
            },
        )
        // The time was up: nothing woke the wait.
        assertEquals(
            emptyList(),
            wakes {
                val b = activity("B")
                val wait = timer(60)
                timerFired(wait)
                ran("Late")
                activityCompleted(b)
                completed()
            },
        )
        // A wait that started no timer leaves nothing to tell by: the edge from the workflow method stays uncounted.
        val permits = overlay("Permits", "update")
        assertTrue(permits.trace.edges.keys.none { it.from == "main" && it.kind == EdgeKind.WAKES })
        assertContains(permits.edges, "await lock.lock()@56 -> activity Audit ×1")
    }

    // ---- start-only waits inside a region

    @Test
    fun `a child whose start is waited for from outside its region is waited for by its lane`() {
        val begun = overlay("Begun", "all")
        assertEquals(
            listOf(
                "activity Fetch@30 -> childWorkflow Errand@31 ×1",
                "activity Store -> parallel@37 ×1",
                // The notice of the lane follows the end of the child.
                "childWorkflow Errand@31 -> activity Notify ×1",
                "parallel@29 -> activity Fetch@30 ×1",
                // The flow went on past the region while the child ran.
                "parallel@29 -> activity Store ×1",
                "parallel@37 -> activity Fetch@38 ×1",
                "parallel@37 -> end ×1",
                "start -> parallel@29 ×1",
            ),
            begun.edges,
        )
        assertEquals(listOf("childWorkflow Errand@31"), begun.laidOn("ChildWorkflowExecutionStarted"))

        // If the child fails, the lane takes the way of the failure, as it does for any step it waits for.
        val document = Graphs("Guarded").node("lane", NodeKind.PARALLEL) { it.copy(waitsFor = WaitsFor.START) }
            .node("child", NodeKind.CHILD_WORKFLOW, "Errand", parent = "lane") { it.copy(waitsFor = WaitsFor.START) }
            .activity("done", "Done", parent = "lane").activity("undo", "Undo", parent = "lane")
            .activity("next", "Next").node("end", NodeKind.END)
            .flow("start", "lane", "next", "end").flow("child", "done").edge("child", "undo", EdgeKind.EXCEPTION, "ChildWorkflowFailure")
            .build()
        val failed = overlay(
            document,
            history("Guarded") {
                val child = child("Errand")
                childStarted(child)
                ran("Next")
                childFailed(child)
                ran("Undo")
                completed()
            },
        )
        assertEquals(emptyList(), failed.trace.ambiguous)
        assertTrue(failed.trace.edges.keys.any { it.from == "child" && it.to == "undo" && it.kind == EdgeKind.EXCEPTION })
        assertFalse(failed.trace.edges.keys.any { it.from == "child" && it.to == "done" })
    }

    // ---- upserts

    @Test
    fun `an upsert that names some of what it writes takes an event that writes those and more`() {
        val tags = overlay("Tags", "all")
        assertEquals(
            listOf("upsertSearchAttributes@32", "upsertSearchAttributes@33", "upsertSearchAttributes@37"),
            tags.laidOn("UpsertWorkflowSearchAttributes"),
        )
        assertEquals(listOf("upsertMemo@34", "upsertMemo@36"), tags.laidOn("WorkflowPropertiesModified"))

        // An event that lacks one of the names the node is known to write is not its event.
        val other = overlay(Fixtures.open("Tags"), history("Tags") { searchAttributes("Rank", "Region"); completed() })
        assertEquals(listOf("UpsertWorkflowSearchAttributes"), other.trace.unmapped.map { it.eventType })
    }

    // ---- coverage

    @Test
    fun `the coverage of such workflows is written with the new edges and matches its schema`() {
        val document = Fixtures.open("Permits")
        val mapper = ExecutionMapper(document)
        val traces = OpenHistories.all.getValue("Permits").map { (way, history) -> mapper.map(HistoryJson.of(history, "Permits.$way.json")) }
        val coverage = CoverageBuilder.build(mapper, traces)
        JsonSchemaChecker.forSchema(CoverageJson.schema()).assertValid(CoverageJson.encode(coverage))
        // The edges from the workflow method are listed like any other wakes edge, with what they are about.
        val own = coverage.edges.filter { it.from == "main" && it.kind == EdgeKind.WAKES }
        assertEquals(listOf("lock", "permits", "permits", "ready"), own.mapNotNull { it.label }.sorted())
        assertTrue(own.all { it.hits == 0 })
    }
}
