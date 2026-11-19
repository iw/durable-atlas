package com.example.fixture.workflows.controlflow

import com.example.fixture.activities.NotificationActivities
import com.example.fixture.activities.PaymentActivities
import com.example.fixture.helpers.OPS_EMAIL
import com.example.fixture.helpers.activityStub
import io.temporal.failure.ActivityFailure
import io.temporal.failure.CanceledFailure
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface TryCatchWorkflow {
    @WorkflowMethod
    fun run(orderId: String, amountCents: Long): String
}

class TryCatchWorkflowImpl : TryCatchWorkflow {
    private val payments = activityStub<PaymentActivities>()
    private val notifications = activityStub<NotificationActivities>()

    override fun run(orderId: String, amountCents: Long): String {
        try {
            return payments.charge(orderId, amountCents)
        } catch (e: ActivityFailure) {
            notifications.email(OPS_EMAIL, "charge failed for $orderId")
            return "charge-failed"
        } catch (e: CanceledFailure) {
            throw e
        } finally {
            notifications.audit("charge attempted for $orderId")
        }
    }
}
