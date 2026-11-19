package com.example.fixture.workflows.order

import com.example.fixture.activities.InventoryActivities
import com.example.fixture.activities.NotificationActivities
import com.example.fixture.activities.PaymentActivities
import com.example.fixture.helpers.PaymentProcessor
import com.example.fixture.helpers.TaskQueues
import com.example.fixture.helpers.WAREHOUSE_PHONE
import com.example.fixture.helpers.activityStub
import io.temporal.activity.LocalActivityOptions
import io.temporal.failure.ApplicationFailure
import io.temporal.failure.TemporalFailure
import io.temporal.workflow.Async
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.Promise
import io.temporal.workflow.Saga
import io.temporal.workflow.Workflow
import java.time.Duration

class OrderWorkflowImpl : OrderWorkflow {
    private val inventory = activityStub<InventoryActivities>()
    private val payments = activityStub<PaymentActivities>()
    private val notifications = activityStub<NotificationActivities>()
    private val localInventory = Workflow.newLocalActivityStub(
        InventoryActivities::class.java,
        LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build(),
    )
    private val paymentProcessor = PaymentProcessor()

    private var status = "RECEIVED"
    private var paymentReference: String? = null
    private var cancelReason: String? = null
    private var shippingAddress = "address on file"

    override fun processOrder(order: Order): OrderResult {
        val saga = Saga(Saga.Options.Builder().build())
        try {
            // Loop over the order lines: check and reserve stock.
            for (line in order.lines) {
                if (!localInventory.checkAvailability(line.sku)) {
                    throw ApplicationFailure.newFailure("${line.sku} is out of stock", "OutOfStock")
                }
                val reservationId = inventory.reserveStock(line.sku, line.quantity)
                saga.addCompensation { inventory.release(reservationId) }
            }

            // Wait for the payment signal, a cancellation, or the deadline.
            status = "AWAITING_PAYMENT"
            notifications.email(order.customerEmail, "Payment requested for order ${order.orderId}")
            val signalled = Workflow.await(Duration.ofMinutes(30)) {
                paymentReference != null || cancelReason != null
            }
            if (!signalled || cancelReason != null) {
                saga.compensate()
                notifications.email(order.customerEmail, "Order ${order.orderId} was cancelled")
                status = "CANCELLED"
                return OrderResult(status)
            }

            // Charge and capture through the helper library.
            val totalCents = order.lines.sumOf { it.quantity * it.unitPriceCents }
            val paymentId = paymentProcessor.chargeAndCapture(order.orderId, totalCents)
            saga.addCompensation { payments.refund(paymentId) }

            // Version gate: the audit entry was added after the first release.
            val version = Workflow.getVersion("order-audit", Workflow.DEFAULT_VERSION, 1)
            if (version >= 1) {
                notifications.audit("order ${order.orderId} paid with $paymentId")
            }

            // Parallel fan-out: confirm to the customer and alert the warehouse.
            val confirmation: Promise<Void> = Async.procedure {
                notifications.email(order.customerEmail, "Order ${order.orderId} confirmed")
            }
            val pickRequest: Promise<Void> = Async.procedure {
                notifications.sms(WAREHOUSE_PHONE, "Pick order ${order.orderId} for $shippingAddress")
            }
            Promise.allOf(confirmation, pickRequest).get()

            // Child workflow: shipment runs on its own task queue.
            status = "SHIPPING"
            val shipment = Workflow.newChildWorkflowStub(
                ShipmentWorkflow::class.java,
                ChildWorkflowOptions.newBuilder()
                    .setWorkflowId("shipment-${order.orderId}")
                    .setTaskQueue(TaskQueues.SHIPPING)
                    .build(),
            )
            val trackingIds = shipment.ship(order.orderId, order.lines.map { it.sku })

            status = "COMPLETED"
            return OrderResult(status, paymentId, trackingIds)
        } catch (e: TemporalFailure) {
            saga.compensate()
            status = "FAILED"
            throw e
        }
    }

    override fun paymentReceived(paymentReference: String) {
        this.paymentReference = paymentReference
    }

    override fun cancelOrder(reason: String) {
        cancelReason = reason
    }

    override fun status(): String = status

    override fun changeAddress(address: String): String {
        val previous = shippingAddress
        shippingAddress = address
        return previous
    }

    override fun validateAddress(address: String) {
        require(address.isNotBlank()) { "address must not be blank" }
        check(status == "RECEIVED" || status == "AWAITING_PAYMENT") { "order is already being shipped" }
    }
}
