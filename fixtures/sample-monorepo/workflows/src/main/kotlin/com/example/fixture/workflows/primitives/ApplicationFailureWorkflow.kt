package com.example.fixture.workflows.primitives

import com.example.fixture.activities.PaymentActivities
import io.temporal.activity.ActivityOptions
import io.temporal.failure.ApplicationFailure
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface ApplicationFailureWorkflow {
    @WorkflowMethod
    fun run(orderId: String, amountCents: Long): String
}

class ApplicationFailureWorkflowImpl : ApplicationFailureWorkflow {
    private val payments = Workflow.newActivityStub(
        PaymentActivities::class.java,
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(),
    )

    override fun run(orderId: String, amountCents: Long): String {
        if (amountCents <= 0) {
            throw ApplicationFailure.newFailure("amount must be positive", "InvalidAmount")
        }
        return payments.charge(orderId, amountCents)
    }
}
