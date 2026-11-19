package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.validate
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.testing.Graphs
import io.github.iw.durableatlas.overlay.testing.Histories
import io.temporal.api.enums.v1.EventType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Histories laid over documents in which a `fail` node stands only where the execution of the
 * workflow may fail: an exception that fails the workflow task alone has no node, and neither
 * has the decision that only leads to one.
 */
class RealFailuresOverlayTest {
    private fun overlay(document: IrDocument, events: Histories.() -> Unit): ExecutionTrace =
        ExecutionMapper(document).map(HistoryJson.of(Histories(document.workflowType).apply(events).build(), "test.json"))

    private fun ExecutionTrace.counts(): Map<String, Int> = nodes.mapValues { it.value.count }

    private fun ExecutionTrace.taken(): Set<String> = edges.keys.map { "${it.from}>${it.to}" }.toSet()

    /**
     * `start → A → asks → B → end`: the call between the steps may throw an exception a worker
     * is told to fail workflows on, and one of the domain that is drawn nowhere.
     */
    private val document: IrDocument = Graphs()
        .activity("A")
        .node("asks", NodeKind.BRANCH) { it.copy(label = "Checks.payable throws?") }
        .node("fail", NodeKind.FAIL) { it.copy(name = "RejectedPayment") }
        .activity("B")
        .node("end", NodeKind.END)
        .flow("start", "A", "asks")
        .edge("asks", "fail", label = "yes")
        .edge("asks", "B", label = "no")
        .flow("B", "end")
        .build()

    /** `start → A → B → end`: what the code between the steps throws fails the workflow task, so nothing is drawn of it. */
    private val plain: IrDocument = Graphs().activity("A").activity("B").node("end", NodeKind.END).flow("start", "A", "B", "end").build()

    @Test
    fun `the documents are ones the overlay accepts`() {
        assertEquals(emptyList(), document.validate())
        assertEquals(emptyList(), plain.validate())
    }

    @Test
    fun `a recorded failure of the execution ends at the failure the sources show`() {
        val trace = overlay(document) {
            ran("A")
            failed("RejectedPayment")
        }

        assertEquals(CloseStatus.FAILED, trace.status)
        assertEquals(listOf("fail"), trace.closedAt)
        assertEquals(setOf("start>A", "A>asks", "asks>fail"), trace.taken())
        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
    }

    @Test
    fun `a workflow task that fails moves nothing, and the execution goes on where it stood`() {
        val trace = overlay(plain) {
            ran("A")
            bare(EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED)
            ran("B")
            completed()
        }

        assertEquals(CloseStatus.COMPLETED, trace.status)
        assertEquals(mapOf("start" to 1, "A" to 1, "B" to 1, "end" to 1), trace.counts())
        assertEquals(emptyList(), trace.unmapped)
    }

    @Test
    fun `an execution that fails in a way the document does not draw is shown as far as it got, without a node it ends at`() {
        // A worker outside the analysed sources may be told to fail workflows on an exception: the sources cannot say so.
        val trace = overlay(plain) {
            ran("A")
            failed("DomainFault")
        }

        assertEquals(CloseStatus.FAILED, trace.status)
        assertEquals(emptyList(), trace.closedAt)
        assertEquals(mapOf("start" to 1, "A" to 1), trace.counts())
        assertEquals(setOf("start>A"), trace.taken())
        // The failure is laid on no node and is no step that went missing: it is not listed as one.
        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
    }
}
