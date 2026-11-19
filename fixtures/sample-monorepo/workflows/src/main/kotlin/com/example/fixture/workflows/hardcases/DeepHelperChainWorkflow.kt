package com.example.fixture.workflows.hardcases

import com.example.fixture.activities.InventoryActivities
import com.example.fixture.helpers.activityStub
import com.example.fixture.helpers.deepChainLevel1
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface DeepHelperChainWorkflow {
    @WorkflowMethod
    fun run(sku: String): String
}

class DeepHelperChainWorkflowImpl : DeepHelperChainWorkflow {
    private val inventory = activityStub<InventoryActivities>()

    override fun run(sku: String): String {
        // Ten helper calls deep before the activity is reached.
        return deepChainLevel1(inventory, sku)
    }
}
