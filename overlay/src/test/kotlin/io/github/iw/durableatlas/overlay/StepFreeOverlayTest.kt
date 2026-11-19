package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.UnknownReasons
import io.github.iw.durableatlas.ir.validate
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.mapping.UnmappedEvent
import io.github.iw.durableatlas.overlay.testing.Graphs
import io.github.iw.durableatlas.overlay.testing.Histories
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Histories laid over a document in which a call of a function that is not drawn from the
 * inside stands as the analyser writes it: a node that says something behind the call cannot be
 * followed, and a branch that asks whether the function throws. Both are read like any unknown
 * call and any branch.
 */
class StepFreeOverlayTest {
    private fun overlay(document: IrDocument, events: Histories.() -> Unit): ExecutionTrace =
        ExecutionMapper(document).map(HistoryJson.of(Histories(document.workflowType).apply(events).build(), "test.json"))

    private fun ExecutionTrace.counts(): Map<String, Int> = nodes.mapValues { it.value.count }

    private fun ExecutionTrace.taken(): Set<String> = edges.keys.map { "${it.from}>${it.to}" }.toSet()

    /**
     * `start → unseen → asks`: the call may fail the workflow on `yes`, is caught by a clause
     * that runs `Fallback`, and goes on to `A` on `no`.
     */
    private val document: IrDocument = Graphs()
        .node("unseen", NodeKind.UNKNOWN) {
            it.copy(reason = UnknownReasons.UNSEEN_CODE, label = "Events.enriched", candidates = listOf("com.example.Enricher.enrich(com.example.Event)"))
        }
        .node("asks", NodeKind.BRANCH) { it.copy(label = "Events.create throws?") }
        .node("fail", NodeKind.FAIL) { it.copy(label = "IllegalStateException, UnknownEvent") }
        .activity("A")
        .activity("Fallback")
        .node("end", NodeKind.END)
        .flow("start", "unseen", "asks")
        .edge("asks", "fail", label = "yes")
        .edge("asks", "A", label = "no")
        .edge("asks", "Fallback", EdgeKind.EXCEPTION, "UnknownEvent")
        .flow("A", "end")
        .flow("Fallback", "end")
        .build()

    @Test
    fun `the document is one the overlay accepts`() {
        assertEquals(emptyList(), document.validate())
    }

    @Test
    fun `an execution passes a call that is not drawn without an event, on the way that says it did not throw`() {
        val trace = overlay(document) {
            ran("A")
            completed()
        }

        assertEquals(mapOf("start" to 1, "unseen" to 1, "asks" to 1, "A" to 1, "end" to 1), trace.counts())
        assertEquals(setOf("start>unseen", "unseen>asks", "asks>A", "A>end"), trace.taken())
        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
    }

    @Test
    fun `a failure thrown in a function that is not drawn ends at the failure its branch leads to`() {
        val trace = overlay(document) {
            failed("UnknownEvent")
        }

        assertEquals(CloseStatus.FAILED, trace.status)
        assertEquals(listOf("fail"), trace.closedAt)
        assertEquals(setOf("start>unseen", "unseen>asks", "asks>fail"), trace.taken())
        assertEquals(emptyList(), trace.unmapped)
    }

    @Test
    fun `what a clause catches of it goes on in the clause`() {
        val trace = overlay(document) {
            ran("Fallback")
            completed()
        }

        assertEquals(setOf("start>unseen", "unseen>asks", "asks>Fallback", "Fallback>end"), trace.taken())
        assertEquals(emptyList(), trace.unmapped)
    }

    @Test
    fun `a step without a node may lie behind the call whose code could not be followed`() {
        val trace = overlay(document) {
            ran("Hidden")
            ran("A")
            completed()
        }

        assertEquals(listOf(UnmappedEvent(2, "ActivityTaskScheduled", "Hidden", listOf("unseen"))), trace.unmapped)
        assertEquals(mapOf("start" to 1, "unseen" to 1, "asks" to 1, "A" to 1, "end" to 1), trace.counts())
    }
}
