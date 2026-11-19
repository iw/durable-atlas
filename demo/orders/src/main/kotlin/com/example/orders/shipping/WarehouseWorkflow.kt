package com.example.orders.shipping

import io.temporal.workflow.SignalMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

data class ShipmentReady(
    val orderId: String = "",
    val shipmentId: String = "",
    val trackingNumber: String = "",
)

/**
 * The long-running workflow that schedules the loading bay of one warehouse. It is run by the
 * warehouse's own workers under the workflow type `Warehouse`; orders only signal it.
 */
@WorkflowInterface
interface WarehouseWorkflow {
    @WorkflowMethod(name = "Warehouse")
    fun run(warehouseId: String)

    /** A packed shipment is waiting to be collected. */
    @SignalMethod
    fun shipmentReady(notice: ShipmentReady)
}

/** The workflow id under which the workflow of a warehouse runs. */
fun warehouseWorkflowId(warehouseId: String): String = "warehouse-$warehouseId"
