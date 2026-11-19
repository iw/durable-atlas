package com.example.fixture.workflows.primitives

import com.example.fixture.activities.InventoryActivities
import com.example.fixture.activities.PaymentActivities
import com.example.fixture.activities.ShippingActivities
import io.temporal.activity.ActivityOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Saga
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface SagaWorkflow {
    @WorkflowMethod
    fun run(orderId: String, sku: String, amountCents: Long): String
}

class SagaWorkflowImpl : SagaWorkflow {
    private val options = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .build()
    private val inventory = Workflow.newActivityStub(InventoryActivities::class.java, options)
    private val payments = Workflow.newActivityStub(PaymentActivities::class.java, options)
    private val shipping = Workflow.newActivityStub(ShippingActivities::class.java, options)

    override fun run(orderId: String, sku: String, amountCents: Long): String {
        val saga = Saga(Saga.Options.Builder().setParallelCompensation(false).build())
        try {
            val reservationId = inventory.reserveStock(sku, 1)
            saga.addCompensation { inventory.release(reservationId) }

            val paymentId = payments.charge(orderId, amountCents)
            saga.addCompensation { payments.refund(paymentId) }

            return shipping.shipItem(orderId, sku)
        } catch (e: ActivityFailure) {
            saga.compensate()
            throw e
        }
    }
}
