package com.example.fixture.workflows.primitives

import io.temporal.activity.ActivityOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

private const val RELEASE_STOCK_ACTIVITY = "ReleaseStock"

@WorkflowInterface
interface UntypedActivityWorkflow {
    @WorkflowMethod
    fun run(sku: String): String
}

class UntypedActivityWorkflowImpl : UntypedActivityWorkflow {
    private val activities = Workflow.newUntypedActivityStub(
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(),
    )

    override fun run(sku: String): String {
        // Name given as a string literal.
        val reservationId = activities.execute("ReserveStock", String::class.java, sku, 1)
        // Name given as a compile-time constant.
        activities.execute(RELEASE_STOCK_ACTIVITY, Void::class.java, reservationId)
        return reservationId
    }
}
