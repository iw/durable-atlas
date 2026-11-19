package com.example.fixture.workflows.primitives

import com.example.fixture.activities.InventoryActivities
import com.example.fixture.activities.PaymentActivities
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface TypedActivityWorkflow {
    @WorkflowMethod
    fun run(sku: String): String
}

class TypedActivityWorkflowImpl : TypedActivityWorkflow {
    private val options = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .build()
    private val inventory = Workflow.newActivityStub(InventoryActivities::class.java, options)
    private val payments = Workflow.newActivityStub(PaymentActivities::class.java, options)

    override fun run(sku: String): String {
        val reservationId = inventory.reserveStock(sku, 1)
        val paymentId = payments.charge(reservationId, 500)
        payments.capture(paymentId)
        inventory.release(reservationId)
        return paymentId
    }
}
