package com.example.fixture.workflows.primitives

import com.example.fixture.activities.ShippingActivities
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface VersionGateWorkflow {
    @WorkflowMethod
    fun run(orderId: String, sku: String): String
}

class VersionGateWorkflowImpl : VersionGateWorkflow {
    private val shipping = Workflow.newActivityStub(
        ShippingActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(),
    )

    override fun run(orderId: String, sku: String): String {
        val version = Workflow.getVersion("express-shipping", Workflow.DEFAULT_VERSION, 1)
        return if (version == Workflow.DEFAULT_VERSION) {
            shipping.shipItem(orderId, sku)
        } else {
            shipping.shipExpress(orderId, sku)
        }
    }
}
