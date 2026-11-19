package com.example.fixture.workflows.primitives

import com.example.fixture.activities.InventoryActivities
import com.example.fixture.activities.NotificationActivities
import com.example.fixture.activities.PaymentActivities
import com.example.fixture.helpers.OPS_EMAIL
import io.temporal.activity.ActivityOptions
import io.temporal.failure.ActivityFailure
import io.temporal.workflow.Async
import io.temporal.workflow.Promise
import io.temporal.workflow.Saga
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

/**
 * The forms of `Async` and `Saga` that take a reference to a stub's method together with its
 * arguments, where the other workflows hand over a lambda.
 */
@WorkflowInterface
interface MethodReferenceWorkflow {
    @WorkflowMethod
    fun run(orderId: String, sku: String, amountCents: Long): String
}

class MethodReferenceWorkflowImpl : MethodReferenceWorkflow {
    private val options = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .build()
    private val inventory = Workflow.newActivityStub(InventoryActivities::class.java, options)
    private val payments = Workflow.newActivityStub(PaymentActivities::class.java, options)
    private val notifications = Workflow.newActivityStub(NotificationActivities::class.java, options)

    override fun run(orderId: String, sku: String, amountCents: Long): String {
        val saga = Saga(Saga.Options.Builder().build())
        try {
            // Async.function and Async.procedure, each given a method of a stub and its arguments.
            val reservation: Promise<String> = Async.function(inventory::reserveStock, sku, 1)
            val notice: Promise<Void> = Async.procedure(notifications::email, OPS_EMAIL, "reserving $sku")
            Promise.allOf(reservation, notice).get()

            // Saga.addCompensation, given a method of a stub and its argument.
            saga.addCompensation(inventory::release, reservation.get())

            return payments.charge(orderId, amountCents)
        } catch (e: ActivityFailure) {
            saga.compensate()
            throw e
        }
    }
}
