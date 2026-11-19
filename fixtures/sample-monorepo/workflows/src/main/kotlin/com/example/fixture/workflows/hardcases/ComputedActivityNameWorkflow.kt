package com.example.fixture.workflows.hardcases

import com.example.fixture.helpers.defaultActivityOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface ComputedActivityNameWorkflow {
    @WorkflowMethod
    fun run(shippingMode: String, orderId: String, sku: String): String
}

class ComputedActivityNameWorkflowImpl : ComputedActivityNameWorkflow {
    private val activities = Workflow.newUntypedActivityStub(defaultActivityOptions())

    override fun run(shippingMode: String, orderId: String, sku: String): String {
        // The activity type is only known at run time ("ShipItem" or "ShipExpress").
        val activityName = "Ship" + shippingMode.replaceFirstChar { it.uppercase() }
        return activities.execute(activityName, String::class.java, orderId, sku)
    }
}
