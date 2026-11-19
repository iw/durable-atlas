package com.example.fixture.workflows.primitives

import com.example.fixture.activities.InventoryActivities
import com.example.fixture.activities.NotificationActivities
import com.example.fixture.activities.ShippingActivities
import com.example.fixture.helpers.OPS_EMAIL
import com.example.fixture.helpers.WAREHOUSE_PHONE
import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Async
import io.temporal.workflow.Promise
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface ParallelWorkflow {
    @WorkflowMethod
    fun run(sku: String): String
}

class ParallelWorkflowImpl : ParallelWorkflow {
    private val options = ActivityOptions.newBuilder()
        .setStartToCloseTimeout(Duration.ofSeconds(30))
        .build()
    private val inventory = Workflow.newActivityStub(InventoryActivities::class.java, options)
    private val shipping = Workflow.newActivityStub(ShippingActivities::class.java, options)
    private val notifications = Workflow.newActivityStub(NotificationActivities::class.java, options)

    override fun run(sku: String): String {
        // Two Async.function branches joined with Promise.allOf.
        val reservation: Promise<String> = Async.function { inventory.reserveStock(sku, 1) }
        val quote: Promise<Long> = Async.function { shipping.quoteShipping(sku) }
        Promise.allOf(reservation, quote).get()

        // Two Async.procedure branches joined with Promise.anyOf.
        val email: Promise<Void> = Async.procedure { notifications.email(OPS_EMAIL, "reserved $sku") }
        val sms: Promise<Void> = Async.procedure { notifications.sms(WAREHOUSE_PHONE, "reserved $sku") }
        Promise.anyOf(email, sms).get()

        // A single asynchronous branch joined with Promise.get.
        val audit: Promise<Void> = Async.procedure { notifications.audit("reserved $sku") }
        audit.get()

        return reservation.get()
    }
}
