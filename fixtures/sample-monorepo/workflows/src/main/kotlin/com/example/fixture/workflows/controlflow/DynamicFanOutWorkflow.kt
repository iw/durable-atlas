package com.example.fixture.workflows.controlflow

import com.example.fixture.activities.ShippingActivities
import com.example.fixture.helpers.activityStub
import io.temporal.workflow.Async
import io.temporal.workflow.Promise
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface DynamicFanOutWorkflow {
    @WorkflowMethod
    fun run(skus: List<String>): Long
}

class DynamicFanOutWorkflowImpl : DynamicFanOutWorkflow {
    private val shipping = activityStub<ShippingActivities>()

    override fun run(skus: List<String>): Long {
        // One asynchronous branch per element, started from inside a collection lambda.
        val quotes: List<Promise<Long>> = skus.map { sku -> Async.function { shipping.quoteShipping(sku) } }
        Promise.allOf(quotes).get()
        return quotes.sumOf { it.get() }
    }
}
