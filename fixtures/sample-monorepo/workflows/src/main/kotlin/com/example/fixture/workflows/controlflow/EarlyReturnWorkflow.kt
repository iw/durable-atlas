package com.example.fixture.workflows.controlflow

import com.example.fixture.activities.InventoryActivities
import com.example.fixture.activities.NotificationActivities
import com.example.fixture.helpers.OPS_EMAIL
import com.example.fixture.helpers.activityStub
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface EarlyReturnWorkflow {
    @WorkflowMethod
    fun run(sku: String): String
}

class EarlyReturnWorkflowImpl : EarlyReturnWorkflow {
    private val inventory = activityStub<InventoryActivities>()
    private val notifications = activityStub<NotificationActivities>()

    override fun run(sku: String): String {
        // Guard clause: no primitive in the arm, but it skips everything below.
        if (sku.isBlank()) {
            return "rejected"
        }

        val available = inventory.checkAvailability(sku)
        if (!available) {
            notifications.email(OPS_EMAIL, "$sku is out of stock")
            return "unavailable"
        }

        return inventory.reserveStock(sku, 1)
    }
}
