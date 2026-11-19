package com.example.fixture.workflows

import com.example.fixture.helpers.TaskQueues
import com.example.fixture.workflows.order.Order
import com.example.fixture.workflows.order.OrderLine
import com.example.fixture.workflows.order.OrderResult
import com.example.fixture.workflows.order.OrderWorkflow
import io.temporal.api.enums.v1.EventType
import io.temporal.client.WorkflowFailedException
import io.temporal.common.WorkflowExecutionHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Runs the composite workflow along three paths and saves one history per path. */
class OrderWorkflowHistoryTest {
    private val order = Order(
        orderId = "1001",
        customerEmail = "customer@example.com",
        lines = listOf(
            OrderLine(sku = "SKU-CHAIR", quantity = 2, unitPriceCents = 4_500),
            OrderLine(sku = "SKU-LAMP", quantity = 1, unitPriceCents = 1_999),
        ),
    )

    @Test
    fun `paid order is shipped`() = CaptureEnvironment.use { env ->
        val started = env.start("OrderWorkflow", WORKFLOW_ID, TaskQueues.ORDERS, order)
        val workflow = env.client.newWorkflowStub(OrderWorkflow::class.java, WORKFLOW_ID)

        awaitStatus(workflow, "AWAITING_PAYMENT")
        assertEquals("address on file", workflow.changeAddress("1 Example Street"))
        workflow.paymentReceived("bank-transfer-42")

        val result = started.stub.getResult(OrderResult::class.java)
        assertEquals("COMPLETED", result.status)
        assertEquals("pay-1001", result.paymentId)
        assertEquals(listOf("trk-1001-SKU-CHAIR", "trk-1001-SKU-LAMP"), result.trackingIds)

        val history = env.saveHistory("OrderWorkflow", WORKFLOW_ID)
        assertEquals(
            listOf(
                "ReserveStock", "ReserveStock", "NotifyEmail", "PaymentCharge", "CapturePayment",
                "NotifyAudit", "NotifyEmail", "NotifySms",
            ),
            scheduledActivities(history),
        )
        assertEquals(listOf("LocalActivity", "LocalActivity", "Version"), markers(history))
        assertEquals(1, count(history, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED))
        assertEquals(1, count(history, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED))
        assertEquals(1, count(history, EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED))
        assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED, history.lastEvent.eventType)

        val shipment = env.saveHistory("Shipment", "shipment-${order.orderId}")
        assertEquals(listOf("ShipItem", "ShipItem"), scheduledActivities(shipment))
    }

    @Test
    fun `unpaid order is cancelled when the payment deadline passes`() = CaptureEnvironment.use { env ->
        val started = env.start("OrderWorkflow", WORKFLOW_ID, TaskQueues.ORDERS, order)

        // No signal: waiting for the result lets the environment skip to the 30 minute deadline.
        val result = started.stub.getResult(OrderResult::class.java)
        assertEquals("CANCELLED", result.status)
        assertNull(result.paymentId)

        val history = env.saveHistory("OrderWorkflow.payment-timeout", WORKFLOW_ID)
        assertEquals(
            listOf("ReserveStock", "ReserveStock", "NotifyEmail", "ReleaseStock", "ReleaseStock", "NotifyEmail"),
            scheduledActivities(history),
        )
        assertEquals(1, count(history, EventType.EVENT_TYPE_TIMER_FIRED))
        assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED, history.lastEvent.eventType)
    }

    @Test
    fun `declined payment compensates and fails the order`() = CaptureEnvironment.use { env ->
        val expensive = order.copy(lines = listOf(OrderLine(sku = "SKU-YACHT", quantity = 1, unitPriceCents = 250_000_000)))
        val started = env.start("OrderWorkflow", WORKFLOW_ID, TaskQueues.ORDERS, expensive)
        val workflow = env.client.newWorkflowStub(OrderWorkflow::class.java, WORKFLOW_ID)

        awaitStatus(workflow, "AWAITING_PAYMENT")
        workflow.paymentReceived("bank-transfer-43")
        assertFailsWith<WorkflowFailedException> { started.stub.getResult(OrderResult::class.java) }

        val history = env.saveHistory("OrderWorkflow.payment-declined", WORKFLOW_ID)
        assertEquals(
            listOf("ReserveStock", "NotifyEmail", "PaymentCharge", "ReleaseStock"),
            scheduledActivities(history),
        )
        assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED, history.lastEvent.eventType)
    }

    private fun awaitStatus(workflow: OrderWorkflow, expected: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (workflow.status() != expected) {
            check(System.nanoTime() < deadline) { "order did not reach $expected" }
            Thread.sleep(20)
        }
    }

    private fun scheduledActivities(history: WorkflowExecutionHistory): List<String> =
        history.events
            .filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED }
            .map { it.activityTaskScheduledEventAttributes.activityType.name }

    private fun markers(history: WorkflowExecutionHistory): List<String> =
        history.events
            .filter { it.eventType == EventType.EVENT_TYPE_MARKER_RECORDED }
            .map { it.markerRecordedEventAttributes.markerName }

    private fun count(history: WorkflowExecutionHistory, type: EventType): Int =
        history.events.count { it.eventType == type }

    private companion object {
        const val WORKFLOW_ID = "order-1001"
    }
}
