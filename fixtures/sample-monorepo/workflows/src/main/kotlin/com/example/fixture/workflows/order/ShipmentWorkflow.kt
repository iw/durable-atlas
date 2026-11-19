package com.example.fixture.workflows.order

import com.example.fixture.activities.ShippingActivities
import com.example.fixture.helpers.activityStub
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface ShipmentWorkflow {
    /** Workflow type `Shipment` (explicit name; the default would be `ShipmentWorkflow`). */
    @WorkflowMethod(name = "Shipment")
    fun ship(orderId: String, skus: List<String>): List<String>
}

class ShipmentWorkflowImpl : ShipmentWorkflow {
    private val shipping = activityStub<ShippingActivities>()

    override fun ship(orderId: String, skus: List<String>): List<String> {
        val trackingIds = mutableListOf<String>()
        for (sku in skus) {
            trackingIds += shipping.shipItem(orderId, sku)
        }
        return trackingIds
    }
}
