package com.example.fixture.workflows.primitives

import com.example.fixture.activities.InventoryActivities
import io.temporal.activity.ActivityOptions
import io.temporal.activity.LocalActivityOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface LocalActivityWorkflow {
    @WorkflowMethod
    fun run(sku: String): String
}

class LocalActivityWorkflowImpl : LocalActivityWorkflow {
    // Two stubs of the same interface: only the creating call tells them apart.
    private val localInventory = Workflow.newLocalActivityStub(
        InventoryActivities::class.java,
        LocalActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5)).build(),
    )
    private val inventory = Workflow.newActivityStub(
        InventoryActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(),
    )

    override fun run(sku: String): String {
        val available = localInventory.checkAvailability(sku)
        return inventory.reserveStock(sku, if (available) 1 else 0)
    }
}
