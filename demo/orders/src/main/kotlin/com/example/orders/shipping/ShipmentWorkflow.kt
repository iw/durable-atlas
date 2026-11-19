package com.example.orders.shipping

import com.example.orders.Shipment
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

data class ShipmentRequest(
    val orderId: String = "",
    val reservationId: String = "",
    val shipment: Shipment = Shipment(),
    val address: String = "",
)

/** Gets one shipment of an order ready for collection and returns its tracking number. */
@WorkflowInterface
interface ShipmentWorkflow {
    @WorkflowMethod
    fun ship(request: ShipmentRequest): String
}

@ActivityInterface
interface ShippingActivities {
    /** Has the stock of a shipment picked and packed. */
    fun packShipment(request: ShipmentRequest)

    /** Books a carrier for a packed shipment and returns the tracking number. */
    fun bookCarrier(request: ShipmentRequest): String
}

class ShipmentWorkflowImpl : ShipmentWorkflow {
    private val shipping = Workflow.newActivityStub(
        ShippingActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(5)).build(),
    )

    override fun ship(request: ShipmentRequest): String {
        shipping.packShipment(request)
        return shipping.bookCarrier(request)
    }
}

/** Stands in for the warehouse floor and the carrier. */
class ShippingActivitiesImpl : ShippingActivities {
    override fun packShipment(request: ShipmentRequest) = Unit

    override fun bookCarrier(request: ShipmentRequest): String =
        "trk-${request.orderId}-${request.shipment.shipmentId}"
}
