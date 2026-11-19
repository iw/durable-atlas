package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.mapping.EdgeRef
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.mapping.Hits
import io.github.iw.durableatlas.overlay.testing.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The histories of the demo project laid over the graph its analysis is expected to give. */
class DemoOverlayTest {
    private val mapper = ExecutionMapper(Fixtures.demo)

    private fun overlay(path: String): ExecutionTrace = mapper.map(Fixtures.demoHistory(path))

    private fun ExecutionTrace.counts(): Map<String, Int> = nodes.mapValues { it.value.count }.toSortedMap()

    /** What every order does before it waits for its payment. */
    private val beforePayment = mapOf(
        "n00-start" to 1, "n01-validate-order" to 1, "n02-fraud-check-version" to 1, "n03-fraud-check" to 1,
        "n04-parallel" to 1, "n05-reserve-stock" to 1, "n06-authorise-payment" to 1, "n07-await-payment" to 1,
    )

    /** What a paid order with two shipments does up to the end of the returns window. */
    private val paid = beforePayment + mapOf(
        "n12-shipments" to 1, "n13-shipment-workflow" to 2, "n14-shipment-ready" to 2, "n15-invoice-number" to 1,
        "n16-award-points" to 1, "n17-discount-policy" to 1, "n18-returns-window" to 1, "n19-continue-as-new-suggested" to 1,
        "signal-approvePayment.start" to 1, "signal-approvePayment.end" to 1,
    )

    private val compensated = beforePayment + mapOf(
        "n08-compensation" to 1, "n09-release-stock" to 1, "n10-void-payment" to 1, "n11-order-cancelled" to 1,
    )

    @Test
    fun `the paid order`() {
        val trace = overlay("paid")

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(CloseStatus.COMPLETED, trace.status)
        assertEquals(listOf("n21-end"), trace.closedAt)
        val handlers = mapOf(
            "update-changeAddress.start" to 1, "update-changeAddress.end" to 1,
            "validator-changeAddress.start" to 1, "validator-changeAddress.end" to 1,
        )
        assertEquals((paid + handlers + ("n21-end" to 1)).toSortedMap(), trace.counts())
        assertEquals(
            mapOf("main" to 1, "validator-changeAddress" to 1, "update-changeAddress" to 1, "signal-approvePayment" to 1),
            trace.entryPoints.mapValues { it.value.count },
        )

        // Version 2 was recorded, the payment was approved, and continue-as-new was not suggested.
        assertEquals(Hits(1), trace.edges[EdgeRef("n02-fraud-check-version", "n03-fraud-check", label = "v2")])
        assertFalse(EdgeRef("n02-fraud-check-version", "n04-parallel", label = "v1") in trace.edges)
        assertEquals(Hits(1), trace.edges[EdgeRef("n07-await-payment", "n12-shipments", label = "paid")])
        assertEquals(Hits(1), trace.edges[EdgeRef("signal-approvePayment", "n07-await-payment", EdgeKind.WAKES)])
        assertEquals(Hits(1), trace.edges[EdgeRef("n19-continue-as-new-suggested", "n21-end", label = "no")])
        // Two shipments: the loop is entered once, begins twice and comes round twice.
        assertEquals(Hits(1), trace.edges[EdgeRef("n07-await-payment", "n12-shipments", label = "paid")])
        assertEquals(Hits(2), trace.edges[EdgeRef("n12-shipments", "n13-shipment-workflow")])
        assertEquals(Hits(2), trace.edges[EdgeRef("n14-shipment-ready", "n12-shipments")])
        // Both lanes of the parallel region are entered and left.
        for (lane in listOf("n05-reserve-stock", "n06-authorise-payment")) {
            assertEquals(Hits(1), trace.edges[EdgeRef("n04-parallel", lane)])
            assertEquals(Hits(1), trace.edges[EdgeRef(lane, "n04-parallel")])
        }
    }

    @Test
    fun `the cancelled order`() {
        val trace = overlay("cancelled")

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(CloseStatus.FAILED, trace.status)
        assertEquals(listOf("n11-order-cancelled"), trace.closedAt)
        assertEquals((compensated + mapOf("signal-cancelOrder.start" to 1, "signal-cancelOrder.end" to 1)).toSortedMap(), trace.counts())
        // The timer of the await was cancelled, not fired: it is left on the edge for a cancelled order.
        assertEquals(Hits(1), trace.edges[EdgeRef("n07-await-payment", "n08-compensation", label = "cancelled")])
        assertFalse(EdgeRef("n07-await-payment", "n08-compensation", EdgeKind.TIMEOUT) in trace.edges)
        assertEquals(Hits(1), trace.edges[EdgeRef("signal-cancelOrder", "n07-await-payment", EdgeKind.WAKES)])
        assertFalse(EdgeRef("signal-approvePayment", "n07-await-payment", EdgeKind.WAKES) in trace.edges)
        assertEquals(Hits(1), trace.edges[EdgeRef("n09-release-stock", "n10-void-payment")])
    }

    @Test
    fun `the order that is not paid in time`() {
        val trace = overlay("timeout")

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(CloseStatus.FAILED, trace.status)
        assertEquals(listOf("n11-order-cancelled"), trace.closedAt)
        assertEquals(compensated.toSortedMap(), trace.counts())
        // The timer fired: the timeout edge was taken, and no signal woke the await.
        assertEquals(Hits(1), trace.edges[EdgeRef("n07-await-payment", "n08-compensation", EdgeKind.TIMEOUT)])
        assertFalse(EdgeRef("n07-await-payment", "n08-compensation", label = "cancelled") in trace.edges)
        assertEquals(emptyList(), trace.edges.keys.filter { it.kind == EdgeKind.WAKES })
    }

    @Test
    fun `the order that continues as new`() {
        val trace = overlay("continue-as-new")

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(CloseStatus.CONTINUED_AS_NEW, trace.status)
        assertEquals(listOf("n20-continue-as-new"), trace.closedAt)
        assertEquals((paid + ("n20-continue-as-new" to 1)).toSortedMap(), trace.counts())
        assertEquals(Hits(1), trace.edges[EdgeRef("n19-continue-as-new-suggested", "n20-continue-as-new", label = "yes")])
    }

    @Test
    fun `the order with a promotion runs one activity that the graph does not have`() {
        val trace = overlay("promotion")

        val event = trace.unmapped.single()
        assertEquals("ActivityTaskScheduled", event.eventType)
        assertEquals("LookUpPromotion", event.name)
        assertEquals(68L, event.eventId)
        // The discount policy is chosen while the workflow runs: that call is where the activity hides.
        assertEquals(listOf("n17-discount-policy"), event.likelySources)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals((paid + ("n21-end" to 1)).toSortedMap(), trace.counts())
    }
}
