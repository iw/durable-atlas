package com.example.orders

import com.example.orders.shipping.ShipmentReady
import com.example.orders.shipping.WarehouseWorkflow
import io.temporal.workflow.Workflow

/** Stands in for a warehouse's own workflow, which runs elsewhere: accepts signals and never ends. */
class StandInWarehouseWorkflow : WarehouseWorkflow {
    override fun run(warehouseId: String) {
        Workflow.await { false }
    }

    override fun shipmentReady(notice: ShipmentReady) = Unit
}
