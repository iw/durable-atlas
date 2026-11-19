package com.example.fixture.workflows.hardcases

import com.example.fixture.activities.NotificationActivities
import com.example.fixture.helpers.EmailNotifier
import com.example.fixture.helpers.Notifier
import com.example.fixture.helpers.SmsNotifier
import com.example.fixture.helpers.activityStub
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface DynamicDispatchWorkflow {
    @WorkflowMethod
    fun run(channel: String, recipient: String)
}

class DynamicDispatchWorkflowImpl : DynamicDispatchWorkflow {
    private val notifications = activityStub<NotificationActivities>()

    override fun run(channel: String, recipient: String) {
        val notifier: Notifier = if (channel == "sms") SmsNotifier(notifications) else EmailNotifier(notifications)
        // Notifier has two implementations, so the target of this call is not known statically.
        notifier.notify(recipient, "Your order has shipped")
    }
}
