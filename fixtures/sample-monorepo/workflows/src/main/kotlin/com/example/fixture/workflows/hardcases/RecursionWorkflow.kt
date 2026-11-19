package com.example.fixture.workflows.hardcases

import com.example.fixture.activities.InventoryActivities
import com.example.fixture.helpers.activityStub
import com.example.fixture.helpers.waitForStock
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface RecursionWorkflow {
    @WorkflowMethod
    fun run(sku: String): Boolean
}

class RecursionWorkflowImpl : RecursionWorkflow {
    private val inventory = activityStub<InventoryActivities>()

    override fun run(sku: String): Boolean {
        // waitForStock calls itself after each failed attempt.
        return waitForStock(inventory, sku, 5)
    }
}
