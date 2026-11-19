package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.UnknownReasons
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.mapping.AmbiguousMatch
import io.github.iw.durableatlas.overlay.mapping.Doubt
import io.github.iw.durableatlas.overlay.mapping.EdgeRef
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.mapping.Hits
import io.github.iw.durableatlas.overlay.mapping.UnmappedEvent
import io.github.iw.durableatlas.overlay.testing.Graphs
import io.github.iw.durableatlas.overlay.testing.Histories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules of the mapping, each on a graph and a history made for it. */
class ExecutionMapperTest {
    private fun overlay(document: IrDocument, events: Histories.() -> Unit): ExecutionTrace =
        ExecutionMapper(document).map(HistoryJson.of(Histories(document.workflowType).apply(events).build(), "test.json"))

    private fun ExecutionTrace.counts(): Map<String, Int> = nodes.mapValues { it.value.count }

    private fun ExecutionTrace.taken(): Set<String> = edges.keys.map { "${it.from}>${it.to}" }.toSet()

    /** `start → A → B → end`. */
    private val line = Graphs().activity("A").activity("B").node("end", NodeKind.END).flow("start", "A", "B", "end").build()

    // --- which node ---

    @Test
    fun `events are laid on the nodes of their name, and the edges between them are taken`() {
        val trace = overlay(line) {
            workflowTask()
            ran("A")
            ran("B")
            completed()
        }

        assertEquals(mapOf("start" to 1, "A" to 1, "B" to 1, "end" to 1), trace.counts())
        assertEquals(setOf("start>A", "A>B", "B>end"), trace.taken())
        assertEquals(4, trace.mapped)
        assertEquals(CloseStatus.COMPLETED, trace.status)
        assertEquals(listOf("end"), trace.closedAt)
        assertEquals(listOf(1L, 3L, 5L, 7L), trace.matches.map { it.eventId })
        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
    }

    @Test
    fun `a name at several nodes goes to the one the execution can come to`() {
        // The same activity before and after another one.
        val document = Graphs().activity("first", "A").activity("B").activity("second", "A").node("end", NodeKind.END)
            .flow("start", "first", "B", "second", "end").build()

        val trace = overlay(document) {
            ran("A")
            ran("B")
            ran("A")
            completed()
        }

        assertEquals(mapOf("start" to 1, "first" to 1, "B" to 1, "second" to 1, "end" to 1), trace.counts())
        assertEquals(emptyList(), trace.ambiguous)
    }

    @Test
    fun `later events settle which of two nodes an event belonged to`() {
        // A loop around an activity, and the same activity once more after the loop.
        val document = Graphs().node("loop", NodeKind.LOOP).activity("inLoop", "A", parent = "loop").activity("after", "A")
            .activity("B").node("end", NodeKind.END)
            .flow("start", "loop", "after", "B", "end").build()

        val trace = overlay(document) {
            ran("A")
            ran("A")
            ran("A")
            ran("B")
            completed()
        }

        // Only the node after the loop leads to B, so the third A was that one and the two before it were rounds of the loop.
        assertEquals(mapOf("start" to 1, "loop" to 1, "inLoop" to 2, "after" to 1, "B" to 1, "end" to 1), trace.counts())
        assertEquals(Hits(2), trace.edges[EdgeRef("loop", "inLoop")])
        assertEquals(Hits(2), trace.edges[EdgeRef("inLoop", "loop")])
        assertEquals(Hits(1), trace.edges[EdgeRef("loop", "after")])
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(6, trace.mapped)
    }

    @Test
    fun `an event that fits several nodes for good is counted for all of them and reported`() {
        val document = Graphs().node("branch", NodeKind.BRANCH).activity("yes", "A").activity("no", "A").node("end", NodeKind.END)
            .flow("start", "branch").edge("branch", "yes", label = "yes").edge("branch", "no", label = "no")
            .flow("yes", "end").flow("no", "end").build()

        val trace = overlay(document) {
            ran("A")
            completed()
        }

        assertEquals(Hits(1, uncertain = 1), trace.nodes["yes"])
        assertEquals(Hits(1, uncertain = 1), trace.nodes["no"])
        assertEquals(Hits(1), trace.nodes["branch"])
        assertEquals(Hits(1), trace.nodes["end"])
        assertEquals(Hits(1), trace.edges[EdgeRef("start", "branch")])
        assertEquals(Hits(1, uncertain = 1), trace.edges[EdgeRef("branch", "yes", label = "yes")])
        assertEquals(
            listOf(
                AmbiguousMatch(
                    Doubt.CANDIDATES, 2, "ActivityTaskScheduled", "A", listOf("no", "yes"),
                    listOf(EdgeRef("branch", "no", label = "no"), EdgeRef("branch", "yes", label = "yes")),
                ),
                AmbiguousMatch(Doubt.PATHS, 4, "WorkflowExecutionCompleted", null, emptyList(), listOf(EdgeRef("no", "end"), EdgeRef("yes", "end"))),
            ),
            trace.ambiguous,
        )
        assertEquals(1, trace.ambiguousEvents)
        assertEquals(2, trace.mapped)
    }

    @Test
    fun `a handler that runs takes a step of its own name before the workflow method that waits`() {
        val document = Graphs().activity("X").activity("inMain", "N").node("end", NodeKind.END).flow("start", "X", "inMain", "end")
            .handler("signal-s", EntryPointKind.SIGNAL, "s").activity("inHandler", "N").node("s.end", NodeKind.END)
            .flow("signal-s.start", "inHandler", "s.end").build()

        val trace = overlay(document) {
            val x = activity("X")
            signal("s")
            val first = activity("N")
            activityCompleted(x)
            val second = activity("N")
            activityCompleted(first)
            activityCompleted(second)
            completed()
        }

        assertEquals(listOf("inHandler"), trace.matches.single { it.eventId == 4L }.nodes)
        assertEquals(listOf("inMain"), trace.matches.single { it.eventId == 6L }.nodes)
        assertEquals(emptyList(), trace.ambiguous)
        // The handler is taken to have returned when the workflow ended.
        assertEquals(1, trace.counts()["s.end"])
        assertEquals(mapOf("main" to 1, "signal-s" to 1), trace.entryPoints.mapValues { it.value.count })
    }

    @Test
    fun `a step with no node is unmapped`() {
        val trace = overlay(line) {
            ran("A")
            ran("Z")
            ran("B")
            otherMarker("Custom")
            signal("nobody")
            completed()
        }

        assertEquals(
            listOf(
                UnmappedEvent(4, "ActivityTaskScheduled", "Z", emptyList()),
                UnmappedEvent(8, "MarkerRecorded", "Custom", emptyList()),
                UnmappedEvent(9, "WorkflowExecutionSignaled", "nobody", emptyList()),
            ),
            trace.unmapped,
        )
        // What has a node is laid on it all the same.
        assertEquals(mapOf("start" to 1, "A" to 1, "B" to 1, "end" to 1), trace.counts())
        assertEquals(setOf("start>A", "A>B", "B>end"), trace.taken())
    }

    @Test
    fun `an unknown call ahead is the likely source of a step without a node, and of one that is out of reach`() {
        val document = Graphs().activity("A").node("hidden", NodeKind.UNKNOWN) { it.copy(reason = UnknownReasons.DEPTH_LIMIT) }
            .activity("B").node("end", NodeKind.END).flow("start", "A", "hidden", "B", "end").build()

        val trace = overlay(document) {
            ran("A")
            ran("Z")
            ran("A")
            ran("B")
            completed()
        }

        // The second A has a node of its name, but behind the unknown call it is not that node.
        assertEquals(
            listOf(
                UnmappedEvent(4, "ActivityTaskScheduled", "Z", listOf("hidden")),
                UnmappedEvent(6, "ActivityTaskScheduled", "A", listOf("hidden")),
            ),
            trace.unmapped,
        )
        assertEquals(mapOf("start" to 1, "A" to 1, "hidden" to 1, "B" to 1, "end" to 1), trace.counts())
    }

    @Test
    fun `an event out of order goes to the one node of its name, and says so`() {
        val trace = overlay(line) {
            ran("B")
            completed()
        }

        // The graph runs A before B, and the history has no A.
        assertEquals(mapOf("start" to 1, "B" to 1, "end" to 1), trace.counts())
        assertEquals(emptyList(), trace.unmapped)
        assertEquals(listOf(AmbiguousMatch(Doubt.OUT_OF_ORDER, 2, "ActivityTaskScheduled", "B", listOf("B"))), trace.ambiguous)
        assertEquals(3, trace.mapped)
        // No edge is counted for the way to B, which is not known. From B the execution goes on as the graph says.
        assertEquals(setOf("B>end"), trace.taken())
    }

    // --- which edge ---

    private val gate = Graphs().node("gate", NodeKind.VERSION_GATE) { it.copy(changeId = "change") }
        .activity("Old").activity("New").node("end", NodeKind.END)
        .flow("start", "gate").edge("gate", "Old", label = "default").edge("gate", "New", label = "v2")
        .flow("Old", "end").flow("New", "end").build()

    @Test
    fun `a version gate is left on the edge of the recorded version`() {
        val trace = overlay(gate) {
            version("change", 2)
            ran("New")
            completed()
        }

        assertEquals(mapOf("start" to 1, "gate" to 1, "New" to 1, "end" to 1), trace.counts())
        assertEquals(Hits(1), trace.edges[EdgeRef("gate", "New", label = "v2")])
    }

    @Test
    fun `a version gate without a marker is left on its default edge`() {
        val trace = overlay(gate) {
            ran("Old")
            completed()
        }

        // The gate left no event, and is counted because the way to the activity leads through it.
        assertEquals(mapOf("start" to 1, "gate" to 1, "Old" to 1, "end" to 1), trace.counts())
        assertEquals(Hits(1), trace.edges[EdgeRef("gate", "Old", label = "default")])
        assertEquals(emptyList(), trace.ambiguous)
    }

    private val waiting = Graphs().node("await", NodeKind.AWAIT) { it.copy(timeout = "PT30M", reads = listOf("done")) }
        .activity("Late").activity("Done").node("end", NodeKind.END)
        .flow("start", "await").edge("await", "Late", EdgeKind.TIMEOUT).edge("await", "Done", label = "done")
        .flow("Late", "end").flow("Done", "end")
        .handler("signal-finish", EntryPointKind.SIGNAL, "finish", writes = listOf("done")).node("finish.end", NodeKind.END)
        .flow("signal-finish.start", "finish.end").edge("signal-finish", "await", EdgeKind.WAKES).build()

    @Test
    fun `a timer of the await's length is its timeout, and a timer that fires takes the timeout edge`() {
        val trace = overlay(waiting) {
            val timer = timer(1800)
            timerFired(timer)
            ran("Late")
            completed()
        }

        assertEquals(mapOf("start" to 1, "await" to 1, "Late" to 1, "end" to 1), trace.counts())
        assertEquals(Hits(1), trace.edges[EdgeRef("await", "Late", EdgeKind.TIMEOUT)])
        assertEquals("PT30M", trace.matches.single { it.eventId == 2L }.name)
    }

    @Test
    fun `a timer that is cancelled leaves the await on another edge, and the signal that came meanwhile woke it`() {
        val trace = overlay(waiting) {
            signal("finish")
            val timer = timer(1800)
            signal("finish")
            signal("finish")
            timerCanceled(timer)
            ran("Done")
            completed()
        }

        assertEquals(Hits(1), trace.edges[EdgeRef("await", "Done", label = "done")])
        assertFalse(EdgeRef("await", "Late", EdgeKind.TIMEOUT) in trace.edges)
        // Three signals, two of them while the await waited.
        assertEquals(Hits(3), trace.entryPoints["signal-finish"])
        assertEquals(Hits(2), trace.edges[EdgeRef("signal-finish", "await", EdgeKind.WAKES)])
    }

    @Test
    fun `an await whose condition holds at once starts no timer and is passed`() {
        val trace = overlay(waiting) {
            ran("Done")
            completed()
        }

        assertEquals(mapOf("start" to 1, "await" to 1, "Done" to 1, "end" to 1), trace.counts())
        assertEquals(Hits(1), trace.edges[EdgeRef("await", "Done", label = "done")])
    }

    @Test
    fun `a timer of another length is not the await's`() {
        val trace = overlay(waiting) {
            val timer = timer(60)
            timerFired(timer)
            completed()
        }

        assertEquals(listOf(UnmappedEvent(2, "TimerStarted", "PT1M", emptyList())), trace.unmapped)
    }

    @Test
    fun `a failed step is left on its exception edge`() {
        val document = Graphs().node("check", NodeKind.LOCAL_ACTIVITY, "Check").activity("A").activity("Recover")
            .node("end", NodeKind.END)
            .flow("start", "check", "A", "end").edge("check", "Recover", EdgeKind.EXCEPTION, "ActivityFailure")
            .edge("A", "Recover", EdgeKind.EXCEPTION, "ActivityFailure").flow("Recover", "end").build()

        val local = overlay(document) {
            localActivity("Check", failed = true)
            ran("Recover")
            completed()
        }
        assertEquals(setOf("start>check", "check>Recover", "Recover>end"), local.taken())

        val activity = overlay(document) {
            localActivity("Check")
            val a = activity("A")
            activityFailed(a)
            ran("Recover")
            completed()
        }
        assertEquals(setOf("start>check", "check>A", "A>Recover", "Recover>end"), activity.taken())
        assertEquals(Hits(1), activity.edges[EdgeRef("A", "Recover", EdgeKind.EXCEPTION, "ActivityFailure")])
    }

    @Test
    fun `a loop that takes no round is entered and left`() {
        val document = Graphs().node("loop", NodeKind.LOOP).activity("A", parent = "loop").activity("B").node("end", NodeKind.END)
            .flow("start", "loop", "B", "end").build()

        val trace = overlay(document) {
            ran("B")
            completed()
        }

        assertEquals(mapOf("start" to 1, "loop" to 1, "B" to 1, "end" to 1), trace.counts())
        assertEquals(setOf("start>loop", "loop>B", "B>end"), trace.taken())
        assertEquals(emptyList(), trace.ambiguous)
    }

    // --- parallel regions ---

    /** Two lanes of two activities each, then one more activity. */
    private val lanes = Graphs().node("parallel", NodeKind.PARALLEL)
        .activity("A1", parent = "parallel").activity("A2", parent = "parallel")
        .activity("B1", parent = "parallel").activity("B2", parent = "parallel")
        .activity("C").activity("Rescue").node("end", NodeKind.END)
        .flow("start", "parallel", "C", "end").flow("A1", "A2").flow("B1", "B2")
        .edge("A1", "Rescue", EdgeKind.EXCEPTION, "ActivityFailure").flow("Rescue", "end").build()

    @Test
    fun `the events of parallel lanes interleave, and each lane keeps its own place`() {
        val trace = overlay(lanes) {
            val a1 = activity("A1")
            val b1 = activity("B1")
            activityCompleted(b1)
            val b2 = activity("B2")
            activityCompleted(a1)
            val a2 = activity("A2")
            activityCompleted(b2)
            activityCompleted(a2)
            ran("C")
            completed()
        }

        assertEquals(mapOf("start" to 1, "parallel" to 1, "A1" to 1, "B1" to 1, "B2" to 1, "A2" to 1, "C" to 1, "end" to 1), trace.counts())
        assertEquals(
            setOf("start>parallel", "parallel>A1", "parallel>B1", "A1>A2", "B1>B2", "A2>parallel", "B2>parallel", "parallel>C", "C>end"),
            trace.taken(),
        )
        assertTrue(trace.edges.values.all { it == Hits(1) })
        assertEquals(emptyList(), trace.ambiguous)
    }

    @Test
    fun `a failure that leaves a parallel region ends its other lanes`() {
        val trace = overlay(lanes) {
            val a1 = activity("A1")
            val b1 = activity("B1")
            activityFailed(a1)
            ran("Rescue")
            activityCompleted(b1)
            completed()
        }

        assertEquals(mapOf("start" to 1, "parallel" to 1, "A1" to 1, "B1" to 1, "Rescue" to 1, "end" to 1), trace.counts())
        assertEquals(setOf("start>parallel", "parallel>A1", "parallel>B1", "A1>Rescue", "Rescue>end"), trace.taken())
        assertEquals(emptyList(), trace.unmapped)
    }

    // --- handlers ---

    @Test
    fun `an accepted update ran its validator, and its handler runs until the update completes`() {
        val document = Graphs().activity("A").node("end", NodeKind.END).flow("start", "A", "end")
            .handler("update-u", EntryPointKind.UPDATE, "u").activity("Apply").node("u.end", NodeKind.END)
            .flow("update-u.start", "Apply", "u.end")
            .handler("validator-u", EntryPointKind.UPDATE_VALIDATOR, "u").node("v.end", NodeKind.END).flow("validator-u.start", "v.end")
            .handler("query-q", EntryPointKind.QUERY, "q").node("q.end", NodeKind.END).flow("query-q.start", "q.end").build()

        val trace = overlay(document) {
            val a = activity("A")
            val update = updateAccepted("u")
            val apply = activity("Apply")
            activityCompleted(apply)
            updateCompleted(update)
            activityCompleted(a)
            completed()
        }

        assertEquals(mapOf("main" to 1, "validator-u" to 1, "update-u" to 1), trace.entryPoints.mapValues { it.value.count })
        assertEquals(
            mapOf("start" to 1, "A" to 1, "validator-u.start" to 1, "v.end" to 1, "update-u.start" to 1, "Apply" to 1, "u.end" to 1, "end" to 1),
            trace.counts(),
        )
        assertEquals(listOf("update-u"), trace.matches.single { it.eventId == 3L }.nodes)
        assertEquals(emptyList(), trace.unmapped)
    }

    @Test
    fun `a handler that is called very often does not keep every call open`() {
        val document = Graphs().activity("A").node("end", NodeKind.END).flow("start", "A", "end")
            .handler("signal-s", EntryPointKind.SIGNAL, "s").activity("Seldom").node("s.end", NodeKind.END)
            .flow("signal-s.start", "Seldom", "s.end").build()

        val trace = overlay(document) {
            repeat(500) { signal("s") }
            ran("Seldom")
            ran("A")
            completed()
        }

        // The activity belongs to one of the calls; which one does not matter.
        assertEquals(Hits(500), trace.entryPoints["signal-s"])
        assertEquals(mapOf("start" to 1, "signal-s.start" to 500, "Seldom" to 1, "s.end" to 1, "A" to 1, "end" to 1), trace.counts())
        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
    }

    // --- how it ends ---

    /** `start → A → branch`, which throws on `yes` and returns on `no`. */
    private val throwing = Graphs().activity("A").node("branch", NodeKind.BRANCH).node("fail", NodeKind.FAIL, "Rejected")
        .node("end", NodeKind.END).flow("start", "A", "branch").edge("branch", "fail", EdgeKind.EXCEPTION, "yes")
        .edge("branch", "end", label = "no").build()

    @Test
    fun `a failure the workflow throws ends at its fail node`() {
        val trace = overlay(throwing) {
            ran("A")
            failed("Rejected")
        }

        assertEquals(CloseStatus.FAILED, trace.status)
        assertEquals(listOf("fail"), trace.closedAt)
        assertEquals(setOf("start>A", "A>branch", "branch>fail"), trace.taken())
        assertEquals(3, trace.mapped)
    }

    @Test
    fun `a failure that escapes from a step ends at no node, and is not unmapped`() {
        val trace = overlay(throwing) {
            val a = activity("A")
            activityFailed(a)
            failed()
        }

        // The activity has no exception edge, so the flow did not go on to the branch that throws.
        assertEquals(CloseStatus.FAILED, trace.status)
        assertEquals(emptyList(), trace.closedAt)
        assertEquals(emptyList(), trace.unmapped)
        assertEquals(mapOf("start" to 1, "A" to 1), trace.counts())
        assertEquals(2, trace.mapped)
    }

    @Test
    fun `an execution that is ended from outside ends at no node`() {
        for ((status, end) in listOf<Pair<CloseStatus, Histories.() -> Long>>(
            CloseStatus.TERMINATED to { terminated() },
            CloseStatus.TIMED_OUT to { timedOut() },
            CloseStatus.CANCELED to { canceled() },
        )) {
            val trace = overlay(line) {
                activity("A")
                end()
            }

            assertEquals(status, trace.status)
            assertEquals(emptyList(), trace.closedAt)
            assertEquals(emptyList(), trace.unmapped)
            assertEquals(mapOf("start" to 1, "A" to 1), trace.counts())
        }
    }

    @Test
    fun `a cancellation the workflow rethrows ends at that fail node`() {
        val document = Graphs().node("sleep", NodeKind.TIMER) { it.copy(duration = "PT10S") }.node("end", NodeKind.END)
            .node("fail", NodeKind.FAIL, "CanceledFailure").flow("start", "sleep", "end")
            .edge("sleep", "fail", EdgeKind.EXCEPTION, "CanceledFailure").build()

        val trace = overlay(document) {
            val timer = timer(10)
            timerCanceled(timer)
            canceled()
        }

        assertEquals(CloseStatus.CANCELED, trace.status)
        assertEquals(listOf("fail"), trace.closedAt)
        assertEquals(setOf("start>sleep", "sleep>fail"), trace.taken())
    }

    @Test
    fun `a history that ends before the execution does is laid over as far as it goes`() {
        val trace = overlay(line) {
            ran("A")
            activity("B")
        }

        assertEquals(CloseStatus.RUNNING, trace.status)
        assertEquals(emptyList(), trace.closedAt)
        assertEquals(mapOf("start" to 1, "A" to 1, "B" to 1), trace.counts())
    }

    @Test
    fun `a completed workflow whose end is out of reach still ends at its one end node`() {
        val trace = overlay(line) {
            ran("A")
            completed()
        }

        assertEquals(listOf("end"), trace.closedAt)
        assertEquals(listOf(Doubt.OUT_OF_ORDER), trace.ambiguous.map { it.doubt })
        assertFalse("B" in trace.nodes)
    }

    @Test
    fun `a document that contradicts itself is refused`() {
        val broken = Graphs().flow("start", "nowhere").build()

        val error = assertFailsWith<IllegalArgumentException> { ExecutionMapper(broken) }
        assertTrue("inconsistent" in error.message.orEmpty(), error.message)
    }
}
