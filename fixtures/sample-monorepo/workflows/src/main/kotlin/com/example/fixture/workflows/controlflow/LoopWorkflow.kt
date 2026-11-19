package com.example.fixture.workflows.controlflow

import com.example.fixture.activities.InventoryActivities
import com.example.fixture.activities.ShippingActivities
import com.example.fixture.helpers.activityStub
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface LoopWorkflow {
    @WorkflowMethod
    fun run(orderId: String, skus: List<String>): Long
}

class LoopWorkflowImpl : LoopWorkflow {
    private val inventory = activityStub<InventoryActivities>()
    private val shipping = activityStub<ShippingActivities>()

    override fun run(orderId: String, skus: List<String>): Long {
        // for
        for (sku in skus) {
            inventory.reserveStock(sku, 1)
        }

        // while
        var pauses = 0
        while (pauses < 2) {
            Workflow.sleep(Duration.ofSeconds(10))
            pauses++
        }

        // do-while
        var available: Boolean
        do {
            available = inventory.checkAvailability(skus.first())
        } while (!available)

        // forEach
        skus.forEach { sku ->
            shipping.shipItem(orderId, sku)
        }

        // map
        val quotes = skus.map { sku -> shipping.quoteShipping(sku) }

        // No primitive in the body: contributes nothing to the diagram.
        var total = 0L
        for (quote in quotes) {
            total += quote
        }
        return total
    }
}
