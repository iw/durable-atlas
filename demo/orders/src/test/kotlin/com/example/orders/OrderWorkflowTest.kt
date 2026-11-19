package com.example.orders

import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.client.WorkflowFailedException
import io.temporal.client.WorkflowStub
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.failure.ApplicationFailure
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Runs the order workflow along each of its paths and saves one history per path. */
class OrderWorkflowTest {
    @Test
    fun `paid order is shipped and invoiced`() = OrdersTestEnvironment.use { env ->
        val order = order("1001")
        val run = env.start("order-1001", order)
        val workflow = env.client.newWorkflowStub(OrderWorkflow::class.java, "order-1001")

        awaitStatus(workflow, OrderStatus.AWAITING_PAYMENT)
        assertEquals("1 Example Street", workflow.changeAddress("2 Example Square"))
        workflow.approvePayment()

        // Waiting for the result lets the environment skip over the returns window.
        val result = run.getResult(OrderResult::class.java)
        assertEquals("auth-1001", result.authorisationId)
        assertTrue(result.invoiceNumber.startsWith("INV-"))
        assertEquals(10_999, result.invoicedCents)
        assertEquals(109, result.loyaltyPoints)
        assertEquals(listOf("trk-1001-S1", "trk-1001-S2"), result.trackingNumbers)
        assertEquals(OrderStatus.COMPLETED, workflow.getStatus())

        val history = env.saveHistory("OrderWorkflow.paid", "order-1001")
        assertEquals(listOf("FraudCheck", "AuthorisePayment", "ReserveStock"), scheduledActivities(history))
        assertEquals(listOf("LocalActivity", "Version", "SideEffect"), markers(history))
        assertEquals(listOf("ShipmentWorkflow", "ShipmentWorkflow"), childWorkflows(history))
        assertEquals(listOf("shipmentReady", "shipmentReady"), externalSignals(history))
        assertEquals(listOf("Loyalty/awardPoints"), nexusOperations(history))
        assertEquals(listOf(THIRTY_MINUTES, FOURTEEN_DAYS), timers(history))
        assertEquals(1, count(history, EventType.EVENT_TYPE_TIMER_CANCELED))
        assertEquals(1, count(history, EventType.EVENT_TYPE_TIMER_FIRED))
        assertEquals(1, count(history, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED))
        assertEquals(listOf("approvePayment"), signals(history))
        assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED, history.lastEvent.eventType)
    }

    @Test
    fun `cancelled order is compensated and fails`() = OrdersTestEnvironment.use { env ->
        val run = env.start("order-1002", order("1002"))
        val workflow = env.client.newWorkflowStub(OrderWorkflow::class.java, "order-1002")

        awaitStatus(workflow, OrderStatus.AWAITING_PAYMENT)
        workflow.cancelOrder()
        assertOrderCancelled(run)
        assertEquals(OrderStatus.CANCELLED, workflow.getStatus())

        val history = env.saveHistory("OrderWorkflow.cancelled", "order-1002")
        assertEquals(
            listOf("FraudCheck", "AuthorisePayment", "ReserveStock", "ReleaseStock", "VoidPayment"),
            scheduledActivities(history),
        )
        assertEquals(listOf("cancelOrder"), signals(history))
        assertEquals(1, count(history, EventType.EVENT_TYPE_TIMER_CANCELED))
        assertEquals(0, count(history, EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED))
        assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED, history.lastEvent.eventType)
    }

    @Test
    fun `unpaid order is compensated and fails when the payment window closes`() = OrdersTestEnvironment.use { env ->
        val run = env.start("order-1003", order("1003"))

        // No signal: waiting for the result lets the environment skip to the end of the 30 minutes.
        assertOrderCancelled(run)

        val history = env.saveHistory("OrderWorkflow.timeout", "order-1003")
        assertEquals(
            listOf("FraudCheck", "AuthorisePayment", "ReserveStock", "ReleaseStock", "VoidPayment"),
            scheduledActivities(history),
        )
        assertEquals(emptyList(), signals(history))
        assertEquals(listOf(THIRTY_MINUTES), timers(history))
        assertEquals(1, count(history, EventType.EVENT_TYPE_TIMER_FIRED))
        assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED, history.lastEvent.eventType)
    }

    @Test
    fun `promotion code is looked up when the order is invoiced`() = OrdersTestEnvironment.use { env ->
        val run = env.start("order-1004", order("1004").copy(promotionCode = "WELCOME10"))
        val workflow = env.client.newWorkflowStub(OrderWorkflow::class.java, "order-1004")

        awaitStatus(workflow, OrderStatus.AWAITING_PAYMENT)
        workflow.approvePayment()
        assertEquals(9_900, run.getResult(OrderResult::class.java).invoicedCents)

        // The lookup happens behind the discount policy, which is chosen while the workflow runs.
        val history = env.saveHistory("OrderWorkflow.promotion", "order-1004")
        assertEquals(
            listOf("FraudCheck", "AuthorisePayment", "ReserveStock", "LookUpPromotion"),
            scheduledActivities(history),
        )
        assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED, history.lastEvent.eventType)
    }

    @Test
    fun `order continues as new when suggested`() = OrdersTestEnvironment.use(suggestContinueAsNew = true) { env ->
        val run = env.start("order-1005", order("1005"))
        val firstRunId = checkNotNull(run.execution).runId
        val workflow = env.client.newWorkflowStub(OrderWorkflow::class.java, "order-1005")

        awaitStatus(workflow, OrderStatus.AWAITING_PAYMENT)
        workflow.approvePayment()
        awaitStatus(workflow, OrderStatus.RETURNS_WINDOW)
        env.sleep(Duration.ofDays(14))
        awaitLastEvent(env, "order-1005", firstRunId, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW)

        val history = env.saveHistory("OrderWorkflow.continue-as-new", "order-1005", firstRunId)
        assertEquals(listOf(THIRTY_MINUTES, FOURTEEN_DAYS), timers(history))
        assertEquals(1, count(history, EventType.EVENT_TYPE_TIMER_FIRED))
        assertEquals(0, count(history, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED))
    }

    private fun order(orderId: String) = Order(
        orderId = orderId,
        customerId = "customer-42",
        shippingAddress = "1 Example Street",
        lines = listOf(
            OrderLine(sku = "SKU-CHAIR", quantity = 2, unitPriceCents = 4_500),
            OrderLine(sku = "SKU-LAMP", quantity = 1, unitPriceCents = 1_999),
        ),
        shipments = listOf(
            Shipment(shipmentId = "S1", warehouseId = "north", skus = listOf("SKU-CHAIR")),
            Shipment(shipmentId = "S2", warehouseId = "south", skus = listOf("SKU-LAMP")),
        ),
    )

    private fun assertOrderCancelled(run: WorkflowStub) {
        val failure = assertFailsWith<WorkflowFailedException> { run.getResult(OrderResult::class.java) }
        assertEquals("OrderCancelled", assertIs<ApplicationFailure>(failure.cause).type)
    }

    /** Polls in real time: the environment's clock stands still until something waits for a result. */
    private fun awaitStatus(workflow: OrderWorkflow, expected: OrderStatus) =
        awaitTrue("order did not reach $expected") { workflow.getStatus() == expected }

    private fun awaitLastEvent(env: OrdersTestEnvironment, workflowId: String, runId: String, expected: EventType) =
        awaitTrue("run $runId of $workflowId did not end with $expected") {
            env.client.fetchHistory(workflowId, runId).lastEvent.eventType == expected
        }

    private fun awaitTrue(otherwise: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { otherwise }
            Thread.sleep(20)
        }
    }

    private fun scheduledActivities(history: WorkflowExecutionHistory): List<String> =
        events(history, EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED) {
            it.activityTaskScheduledEventAttributes.activityType.name
        }

    private fun markers(history: WorkflowExecutionHistory): List<String> =
        events(history, EventType.EVENT_TYPE_MARKER_RECORDED) { it.markerRecordedEventAttributes.markerName }

    private fun childWorkflows(history: WorkflowExecutionHistory): List<String> =
        events(history, EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED) {
            it.startChildWorkflowExecutionInitiatedEventAttributes.workflowType.name
        }

    private fun externalSignals(history: WorkflowExecutionHistory): List<String> =
        events(history, EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED) {
            it.signalExternalWorkflowExecutionInitiatedEventAttributes.signalName
        }

    private fun nexusOperations(history: WorkflowExecutionHistory): List<String> =
        events(history, EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED) {
            it.nexusOperationScheduledEventAttributes.let { operation -> "${operation.service}/${operation.operation}" }
        }

    private fun timers(history: WorkflowExecutionHistory): List<Duration> =
        events(history, EventType.EVENT_TYPE_TIMER_STARTED) {
            Duration.ofSeconds(it.timerStartedEventAttributes.startToFireTimeout.seconds)
        }

    private fun signals(history: WorkflowExecutionHistory): List<String> =
        events(history, EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED) {
            it.workflowExecutionSignaledEventAttributes.signalName
        }

    private fun <T> events(
        history: WorkflowExecutionHistory,
        type: EventType,
        detail: (HistoryEvent) -> T,
    ): List<T> = history.events.filter { it.eventType == type }.map(detail)

    private fun count(history: WorkflowExecutionHistory, type: EventType): Int =
        history.events.count { it.eventType == type }

    private companion object {
        val THIRTY_MINUTES: Duration = Duration.ofMinutes(30)
        val FOURTEEN_DAYS: Duration = Duration.ofDays(14)
    }
}
