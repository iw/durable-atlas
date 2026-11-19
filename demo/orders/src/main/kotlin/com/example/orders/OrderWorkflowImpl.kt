package com.example.orders

import com.example.orders.activities.InventoryActivities
import com.example.orders.activities.OrderValidationActivities
import com.example.orders.activities.PaymentActivities
import com.example.orders.activities.PromotionActivities
import com.example.orders.loyalty.AwardPointsRequest
import com.example.orders.loyalty.LoyaltyService
import com.example.orders.pricing.DiscountPolicy
import com.example.orders.pricing.PromotionDiscount
import com.example.orders.pricing.TierDiscount
import com.example.orders.shipping.ShipmentReady
import com.example.orders.shipping.ShipmentRequest
import com.example.orders.shipping.ShipmentWorkflow
import com.example.orders.shipping.WarehouseWorkflow
import com.example.orders.shipping.warehouseWorkflowId
import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.failure.ApplicationFailure
import io.temporal.workflow.Async
import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.NexusOperationOptions
import io.temporal.workflow.NexusServiceOptions
import io.temporal.workflow.Promise
import io.temporal.workflow.Saga
import io.temporal.workflow.TimerOptions
import io.temporal.workflow.Workflow
import java.time.Duration
import java.util.UUID

class OrderWorkflowImpl : OrderWorkflow {
    private val validation = Workflow.newLocalActivityStub(
        OrderValidationActivities::class.java,
        LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build(),
    )
    private val activityOptions = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .build()
    private val payments = Workflow.newActivityStub(PaymentActivities::class.java, activityOptions)
    private val inventory = Workflow.newActivityStub(InventoryActivities::class.java, activityOptions)
    private val promotions = Workflow.newActivityStub(PromotionActivities::class.java, activityOptions)
    private val loyalty = Workflow.newNexusServiceStub(
        LoyaltyService::class.java,
        NexusServiceOptions.newBuilder()
            .setEndpoint(LOYALTY_ENDPOINT)
            .setOperationOptions(
                NexusOperationOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofMinutes(1)).build(),
            )
            .build(),
    )

    private var status = OrderStatus.RECEIVED
    private var shippingAddress = ""
    private var paid = false
    private var cancelled = false

    override fun processOrder(order: Order): OrderResult {
        shippingAddress = order.shippingAddress
        validation.validateOrder(order)

        // Orders that started before the fraud check was introduced carry version 1 and skip it.
        val fraudCheckVersion = Workflow.getVersion("fraud-check-v2", 1, 2)
        if (fraudCheckVersion >= 2) {
            payments.fraudCheck(order)
        }

        // Authorise the payment and reserve the stock side by side. Each step registers how to
        // undo it before it starts. Compensations run last registered first, so the stock is
        // released before the payment is voided.
        val saga = Saga(Saga.Options.Builder().build())
        saga.addCompensation { payments.voidPayment(order.orderId) }
        val authorisation = Async.function { payments.authorisePayment(order.orderId, order.totalCents()) }
        saga.addCompensation { inventory.releaseStock(order.orderId) }
        val reservation = Async.function { inventory.reserveStock(order.orderId, order.lines) }
        Promise.allOf(reservation, authorisation).get()
        val reservationId = reservation.get()
        val authorisationId = authorisation.get()

        status = OrderStatus.AWAITING_PAYMENT
        val decided = Workflow.await(Duration.ofMinutes(30)) { paid || cancelled }
        if (cancelled || !decided) {
            // Detached, so that the order is still undone when the workflow itself is being cancelled.
            Workflow.newDetachedCancellationScope { saga.compensate() }.run()
            status = OrderStatus.CANCELLED
            throw ApplicationFailure.newFailure("Order ${order.orderId} was not paid", "OrderCancelled")
        }

        status = OrderStatus.SHIPPING
        val trackingNumbers = mutableListOf<String>()
        for (shipment in order.shipments) {
            val shipmentWorkflow = Workflow.newChildWorkflowStub(
                ShipmentWorkflow::class.java,
                ChildWorkflowOptions.newBuilder()
                    .setWorkflowId("shipment-${order.orderId}-${shipment.shipmentId}")
                    .build(),
            )
            val trackingNumber = shipmentWorkflow.ship(
                ShipmentRequest(order.orderId, reservationId, shipment, shippingAddress),
            )
            trackingNumbers += trackingNumber

            val warehouse = Workflow.newExternalWorkflowStub(
                WarehouseWorkflow::class.java,
                warehouseWorkflowId(shipment.warehouseId),
            )
            warehouse.shipmentReady(ShipmentReady(order.orderId, shipment.shipmentId, trackingNumber))
        }

        // Invoice numbers are not derived from the order, so the one drawn here is recorded.
        val invoiceNumber = Workflow.sideEffect(String::class.java) {
            "INV-" + UUID.randomUUID().toString().take(8).uppercase()
        }
        val account = loyalty.awardPoints(AwardPointsRequest(order.customerId, order.orderId, order.totalCents()))

        val promotionCode = order.promotionCode
        val discountPolicy: DiscountPolicy =
            if (promotionCode != null) PromotionDiscount(promotions, promotionCode) else TierDiscount(account.tier)
        val discountCents = discountPolicy.discountCents(order)

        status = OrderStatus.RETURNS_WINDOW
        Workflow.sleep(Duration.ofDays(14), TimerOptions.newBuilder().setSummary("returns window").build())

        if (Workflow.getInfo().isContinueAsNewSuggested) {
            // The history has grown long: the order carries on in a fresh run.
            Workflow.continueAsNew(order)
        }
        status = OrderStatus.COMPLETED
        return OrderResult(
            orderId = order.orderId,
            authorisationId = authorisationId,
            invoiceNumber = invoiceNumber,
            invoicedCents = order.totalCents() - discountCents,
            loyaltyPoints = account.points,
            trackingNumbers = trackingNumbers,
        )
    }

    override fun approvePayment() {
        paid = true
    }

    override fun cancelOrder() {
        cancelled = true
    }

    override fun getStatus(): OrderStatus = status

    override fun changeAddress(address: String): String {
        val previous = shippingAddress
        shippingAddress = address
        return previous
    }

    override fun validateChangeAddress(address: String) {
        require(address.isNotBlank()) { "The address must not be blank" }
        check(status == OrderStatus.RECEIVED || status == OrderStatus.AWAITING_PAYMENT) {
            "Order shipments are already on their way"
        }
    }
}
