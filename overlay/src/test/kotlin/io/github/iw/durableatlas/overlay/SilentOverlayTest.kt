package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.WaitsFor
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.overlay.coverage.CoverageBuilder
import io.github.iw.durableatlas.overlay.coverage.CoverageJson
import io.github.iw.durableatlas.overlay.coverage.OutsideEvent
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.mapping.Doubt
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.mapping.RecognisedEvent
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Graphs
import io.github.iw.durableatlas.overlay.testing.Histories
import io.github.iw.durableatlas.overlay.testing.Profiles
import io.github.iw.durableatlas.overlay.testing.SilentHistories
import io.temporal.api.history.v1.History
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Executions of workflows that wait for a lock or a semaphore, set search attributes and memo
 * entries, retry, run Nexus operations and are asked to cancel from outside: what a history
 * records of such things, laid over the graphs the analyser gives for them.
 */
class SilentOverlayTest {
    private class Overlaid(val document: IrDocument, val trace: ExecutionTrace, private val names: Profiles) {
        val edges: List<String> get() = names.edges(trace.edges).lines()

        /** Each event that was laid on the graph, with the node it was laid on. */
        val matches: List<String> get() = trace.matches.map { "${it.eventId} ${it.eventType} -> ${it.nodes.joinToString(", ") { id -> names.name(id) }}" }
        fun name(id: String): String = names.name(id)
    }

    private fun overlay(document: IrDocument, history: History): Overlaid {
        val mapper = ExecutionMapper(document)
        return Overlaid(document, mapper.map(HistoryJson.of(history, "${document.workflowType}.json")), Profiles(mapper))
    }

    private fun overlay(file: String, way: String): Overlaid = overlay(Fixtures.silent(file), SilentHistories.all.getValue(file).getValue(way))

    private fun retry(way: String): Overlaid =
        overlay(if (way == "beside") Fixtures.pattern("AsyncRetries") else Fixtures.construct("Retries"), SilentHistories.retries.getValue(way))

    private fun history(workflowType: String, events: Histories.() -> Unit): History = Histories(workflowType).apply(events).build()

    @Test
    fun `every way through is laid over its graph without an unmapped event or a doubt`() {
        assertEquals(Fixtures.silentIr.toFile().list()!!.map { it.removeSuffix(".ir.json") }.toSet(), SilentHistories.all.keys)
        val doubts = LinkedHashMap<String, List<Doubt>>()
        for ((file, histories) in SilentHistories.all) {
            for (way in histories.keys) {
                val trace = overlay(file, way).trace
                assertEquals(emptyList(), trace.unmapped, "$file, $way")
                assertEquals(emptyList(), trace.recognised, "$file, $way")
                assertTrue(trace.closedAt.isNotEmpty(), "$file, $way")
                if (trace.ambiguous.isNotEmpty()) doubts["$file.$way"] = trace.ambiguous.map { it.doubt }
            }
        }
        for (way in SilentHistories.retries.keys) {
            val trace = retry(way).trace
            assertEquals(emptyList(), trace.unmapped, way)
            if (trace.ambiguous.isNotEmpty()) doubts["retries.$way"] = trace.ambiguous.map { it.doubt }
        }
        assertEquals(emptyMap(), doubts)
    }

    // ---- locks and semaphores

    @Test
    fun `a wait for a lock leaves no event, and one with a time to wait for at most takes its timer`() {
        // The lock is free: the update passes the wait and runs its step.
        assertEquals(
            listOf(
                "activity Fetch -> end of update-change ×1",
                "await isEveryHandlerFinished() -> end ×1",
                "await lock.lock() -> activity Fetch ×1",
                "start -> await isEveryHandlerFinished() ×1",
                "start of update-change -> await lock.lock() ×1",
            ),
            overlay("Guarded", "alone").edges,
        )
        // The timer of the wait is laid on the wait. A signal that arrives while it runs lets go of the lock:
        // the edge from the signal is taken, the timer is let go of, and the update goes on to its step.
        val freed = overlay("Guarded", "freed")
        assertContains(freed.matches, "5 TimerStarted -> await lock.tryLock()")
        assertTrue("signal-release -> await lock.tryLock() wakes ×1" in freed.edges)
        assertTrue("await lock.tryLock() -> activity Store ×1" in freed.edges)
        assertFalse(freed.edges.any { "timeout" in it })
        // Nobody lets go: the timer fires, and the update returns on the timeout edge.
        val timedOut = overlay("Guarded", "timed-out")
        assertTrue("await lock.tryLock() -> end of update-tryChange timeout ×1" in timedOut.edges)
        assertFalse(timedOut.edges.any { "Store" in it })
    }

    @Test
    fun `a run that waits for another run of the same handler passes the wait once the other is done`() {
        val queued = overlay("Guarded", "queued")
        assertTrue("start of update-change -> await lock.lock() ×2" in queued.edges)
        assertTrue("await lock.lock() -> activity Fetch ×2" in queued.edges)
        // What ends the wait is the first run letting go of the lock at its end. A history shows when a handler is
        // called, not when it lets go, so the edge from the handler to its own wait is not counted as taken here.
        assertFalse(queued.edges.any { "wakes" in it })
    }

    @Test
    fun `a wait for a semaphore is woken by the signal that gives a permit back, and times out like any await`() {
        val granted = overlay("Pool", "granted")
        assertTrue("signal-grant -> await permits.acquire()@44 wakes ×2" in granted.edges)
        assertTrue("await permits.tryAcquire() -> activity Store ×1" in granted.edges)
        assertTrue("loop for each id -> await permits.acquire()@33 ×2" in granted.edges)
        val short = overlay("Pool", "short")
        assertContains(short.matches, "4 TimerStarted -> await permits.tryAcquire()")
        assertTrue("await permits.tryAcquire() -> activity Notify@41 timeout ×1" in short.edges)
    }

    // ---- search attributes and the memo

    @Test
    fun `each upsert is laid on the step that writes the same names, or on one that does not say what it writes`() {
        val all = overlay("Labels", "all")
        assertEquals(
            listOf(
                "1 WorkflowExecutionStarted -> start",
                "2 UpsertWorkflowSearchAttributes -> upsertSearchAttributes@31",
                "3 WorkflowPropertiesModified -> upsertMemo@32",
                "4 ActivityTaskScheduled -> activity Fetch",
                // The same names as the first, in another order: where the execution stands tells the two steps apart.
                "6 UpsertWorkflowSearchAttributes -> upsertSearchAttributes@34",
                "7 UpsertWorkflowSearchAttributes -> upsertSearchAttributes@35",
                "8 WorkflowPropertiesModified -> upsertMemo@36",
                // Steps that do not say what they write take whatever is written where they stand.
                "9 UpsertWorkflowSearchAttributes -> upsertSearchAttributes@37",
                "10 WorkflowPropertiesModified -> upsertMemo@38",
                "11 UpsertWorkflowSearchAttributes -> upsertSearchAttributes@39",
                "12 WorkflowExecutionCompleted -> end",
            ),
            all.matches,
        )
        assertEquals(listOf("Attempts, Stage", "note"), all.trace.matches.subList(1, 3).map { it.name })
        assertEquals(all.document.nodes.size, all.trace.nodes.size)
    }

    @Test
    fun `the search attribute in which the SDK keeps versions is no step, and an upsert no node writes is unmapped`() {
        val versions = overlay("Labels", "versions")
        assertEquals(overlay("Labels", "all").trace.mapped, versions.trace.mapped)
        assertFalse(versions.trace.matches.any { it.eventId == 3L })
        assertEquals(emptyList(), versions.trace.unmapped)

        // A workflow without such a step that sets a search attribute all the same: the event is a step without a node.
        val stray = overlay(Fixtures.silent("Twice"), history("Twice") { ran("Fetch"); searchAttributes("Stage"); ran("Store"); completed() })
        assertEquals(listOf("UpsertWorkflowSearchAttributes Stage"), stray.trace.unmapped.map { "${it.eventType} ${it.name}" })
        // And one that writes other names than the step that stands there is not laid on that step.
        val other = overlay(Fixtures.silent("Labels"), history("Labels") { searchAttributes("Colour"); completed() })
        assertFalse(other.trace.matches.any { it.eventType == "UpsertWorkflowSearchAttributes" && it.nodes.size == 1 && other.name(it.nodes.single()) == "upsertSearchAttributes@31" })
    }

    // ---- retries

    @Test
    fun `the marker a retry begins with and the timers of its pauses are laid on its loop`() {
        val retried = retry("last-fails")
        val first = "loop retry on failure, up to 3 attempts, for up to 10m"
        val second = "loop retry on failure"
        assertEquals(
            listOf(
                "2 MarkerRecorded -> $first",
                "7 TimerStarted -> $first",
                "13 MarkerRecorded -> $second",
                "16 TimerStarted -> $second",
                "20 TimerStarted -> $second",
            ),
            retried.matches.filter { " -> loop" in it },
        )
        // The loops are entered once each, however many attempts they take; the steps count each attempt.
        assertEquals(listOf(1, 1), retried.document.nodes.filter { it.kind == NodeKind.LOOP }.map { retried.trace.nodes.getValue(it.id).count })
        assertTrue("$first -> activity Prepare ×2" in retried.edges)
        assertTrue("activity Work -> $first ×2" in retried.edges)
        assertTrue("$second -> activity Check ×3" in retried.edges)
        assertTrue("branch fine -> activity Report 'yes' ×1" in retried.edges)
    }

    @Test
    fun `a step that fails in the middle of a retry leads back to the head of its loop`() {
        val retried = retry("first-fails")
        val first = "loop retry on failure, up to 3 attempts, for up to 10m"
        // `Prepare` fails, the retry pauses, and the next attempt begins with `Prepare` again: `Work` runs once.
        assertContains(retried.matches, "5 TimerStarted -> $first")
        assertTrue("$first -> activity Prepare ×2" in retried.edges)
        assertTrue("activity Prepare -> activity Work ×1" in retried.edges)
        assertTrue("activity Work -> $first ×1" in retried.edges)
        assertEquals(emptyList(), retried.trace.ambiguous)
    }

    @Test
    fun `the pause of a retry beside the flow is laid on that retry, not on one further on`() {
        val beside = retry("beside")
        val first = "loop retry on failure, up to 3 attempts, for up to 10m"
        // The other lane is still running its check when the timer starts.
        assertContains(beside.matches, "2 MarkerRecorded -> $first")
        assertContains(beside.matches, "6 TimerStarted -> $first")
        assertContains(beside.matches, "11 MarkerRecorded -> loop retry on failure")
        assertContains(beside.matches, "14 TimerStarted -> loop retry on failure")
        assertTrue("$first -> activity Prepare ×2" in beside.edges)
    }

    @Test
    fun `a timer or a marker that no retry accounts for is still a step without a node`() {
        val retries = Fixtures.construct("Retries")
        // A timer when no step of the retry has failed and the flow has left both loops: nothing can have paused.
        val late = overlay(retries, history("Retries") { ran("Prepare"); ran("Work"); ran("Check"); ran("Report"); backOff(5); completed() })
        assertEquals(listOf("TimerStarted PT5S"), late.trace.unmapped.map { "${it.eventType} ${it.name}" })
        // A mutable side effect under an id that the code chose is not the marker of a retry.
        val named = overlay(retries, history("Retries") { mutableSideEffect("rate"); ran("Prepare"); ran("Work"); ran("Check"); completed() })
        assertEquals(listOf("MarkerRecorded rate"), named.trace.unmapped.map { "${it.eventType} ${it.name}" })
        // A document from before loops said that they are retries reads as it did: the events have no node.
        val unmarked = retries.copy(nodes = retries.nodes.map { it.copy(retry = false) })
        val before = overlay(unmarked, SilentHistories.retries.getValue("last-fails"))
        assertEquals(listOf("MarkerRecorded", "TimerStarted", "MarkerRecorded", "TimerStarted", "TimerStarted"), before.trace.unmapped.map { it.eventType })
    }

    // ---- Nexus operations

    /** A workflow that runs one Nexus operation. */
    private val greeting = Graphs("Greeting")
        .node("hello", NodeKind.NEXUS_OPERATION, name = "hello") { it.copy(service = "Greeter") }
        .node("end", NodeKind.END)
        .flow("start", "hello", "end")
        .build()

    /** A workflow that starts a Nexus operation in a scope, sleeps, and cancels the scope. */
    private val impatient = Graphs("Impatient")
        .node("hello", NodeKind.NEXUS_OPERATION, name = "hello") { it.copy(service = "Greeter", waitsFor = WaitsFor.NOTHING) }
        .node("wait", NodeKind.TIMER) { it.copy(duration = "PT1M") }
        .node("stop", NodeKind.CANCEL_SCOPE) { it.copy(label = "scope") }
        .node("end", NodeKind.END)
        .flow("start", "hello", "wait", "stop", "end")
        .edge("stop", "hello", EdgeKind.CANCELS)
        .build()

    @Test
    fun `a Nexus operation that has started is laid on the node of the operation`() {
        val overlaid = overlay(
            greeting,
            history("Greeting") {
                val hello = nexus("Greeter", "hello")
                nexusStarted(hello)
                nexusCompleted(hello)
                completed()
            },
        )
        assertEquals(
            listOf("1 WorkflowExecutionStarted -> start", "2 NexusOperationScheduled -> nexusOperation hello", "3 NexusOperationStarted -> nexusOperation hello", "5 WorkflowExecutionCompleted -> end"),
            overlaid.matches,
        )
        assertEquals("Greeter.hello", overlaid.trace.matches[2].name)
        // The operation is one step however many events it leaves, and it ends when its last event says so.
        assertEquals(1, overlaid.trace.nodes.getValue("hello").count)
        assertTrue("nexusOperation hello -> end ×1" in overlaid.edges)
        assertEquals(emptyList(), overlaid.trace.unmapped)
    }

    @Test
    fun `a request to cancel a Nexus operation is laid on the node that cancels its scope, and what becomes of it on the operation`() {
        for (delivered in listOf(true, false)) {
            val overlaid = overlay(
                impatient,
                history("Impatient") {
                    val hello = nexus("Greeter", "hello")
                    nexusStarted(hello)
                    timerFired(timer(60))
                    val request = nexusCancelRequested(hello)
                    if (delivered) nexusCancelDelivered(request, hello) else nexusCancelFailed(request, hello)
                    nexusCanceled(hello)
                    completed()
                },
            )
            assertEquals(
                listOf(
                    "1 WorkflowExecutionStarted -> start",
                    "2 NexusOperationScheduled -> nexusOperation hello",
                    "3 NexusOperationStarted -> nexusOperation hello",
                    "4 TimerStarted -> timer",
                    "6 NexusOperationCancelRequested -> cancelScope scope",
                    "7 NexusOperationCancelRequest${if (delivered) "Completed" else "Failed"} -> nexusOperation hello",
                    "9 WorkflowExecutionCompleted -> end",
                ),
                overlaid.matches,
            )
            assertTrue("cancelScope scope -> nexusOperation hello cancels ×1" in overlaid.edges)
            assertEquals(emptyList(), overlaid.trace.unmapped)
        }
    }

    @Test
    fun `where no node cancels it, the request to cancel a Nexus operation is laid on the operation itself`() {
        val overlaid = overlay(
            greeting,
            history("Greeting") {
                val hello = nexus("Greeter", "hello")
                nexusStarted(hello)
                cancelRequestedFromOutside()
                val request = nexusCancelRequested(hello)
                nexusCancelDelivered(request, hello)
                nexusCanceled(hello)
                canceled()
            },
        )
        assertEquals(
            listOf(
                "1 WorkflowExecutionStarted -> start",
                "2 NexusOperationScheduled -> nexusOperation hello",
                "3 NexusOperationStarted -> nexusOperation hello",
                "5 NexusOperationCancelRequested -> nexusOperation hello",
                "6 NexusOperationCancelRequestCompleted -> nexusOperation hello",
            ),
            overlaid.matches,
        )
        assertEquals(emptyList(), overlaid.trace.unmapped)
        // The operation of an event that has no node has none either: what follows it is left out, and is no step of its own.
        val nowhere = overlay(Fixtures.silent("Twice"), history("Twice") { nexusStarted(nexus("Greeter", "hello")); completed() })
        assertEquals(listOf("NexusOperationScheduled"), nowhere.trace.unmapped.map { it.eventType })
    }

    // ---- from outside

    @Test
    fun `a request from outside to cancel the workflow is recognised, and is no step without a node`() {
        val document = Fixtures.silent("Precedence")
        val history = history("Precedence") {
            ran("Fetch")
            ran("Fetch")
            ran("Store")
            ran("Notify")
            cancelRequestedFromOutside()
            canceled()
        }
        val mapper = ExecutionMapper(document)
        val trace = mapper.map(HistoryJson.of(history, "Precedence.cancelled.json"))
        assertEquals(listOf(RecognisedEvent(10, "WorkflowExecutionCancelRequested")), trace.recognised)
        assertEquals(emptyList(), trace.unmapped)
        assertFalse(trace.matches.any { it.eventId == 10L })

        // The coverage says so apart from the events without a node, and counts it apart from the steps.
        val coverage = CoverageBuilder.build(mapper, listOf(trace))
        assertEquals(listOf(OutsideEvent("Precedence.cancelled.json", 10, "WorkflowExecutionCancelRequested")), coverage.recognised)
        assertEquals(1, coverage.events.recognised)
        assertEquals(0, coverage.events.unmapped)
        assertEquals(coverage.events.mapped, coverage.events.total)
        assertEquals(1, coverage.histories.single().events.recognised)
        val json = CoverageJson.encode(coverage)
        JsonSchemaChecker.forSchema(CoverageJson.schema()).assertValid(json)
        assertContains(json, "\"recognised\": [\n    {\n      \"history\": \"Precedence.cancelled.json\",\n      \"eventId\": 10,\n      \"eventType\": \"WorkflowExecutionCancelRequested\"\n    }\n  ]")
        assertContains(json, "\"unmapped\": 0,\n    \"recognised\": 1\n")
        assertEquals(coverage, CoverageJson.decode(json))

        // Coverage without such an event is written as it always was, and what was written before reads as none.
        val plain = CoverageBuilder.build(mapper, listOf(mapper.map(HistoryJson.of(SilentHistories.all.getValue("Precedence").getValue("nudged"), "Precedence.json"))))
        val text = CoverageJson.encode(plain)
        assertFalse("recognised" in text)
        assertEquals(emptyList(), CoverageJson.decode(text).recognised)
        assertEquals(0, CoverageJson.decode(text).events.recognised)
    }
}
