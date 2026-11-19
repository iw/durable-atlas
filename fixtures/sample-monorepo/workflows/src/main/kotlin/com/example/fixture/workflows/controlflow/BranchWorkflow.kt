package com.example.fixture.workflows.controlflow

import com.example.fixture.activities.NotificationActivities
import com.example.fixture.helpers.OPS_EMAIL
import com.example.fixture.helpers.WAREHOUSE_PHONE
import com.example.fixture.helpers.activityStub
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface BranchWorkflow {
    @WorkflowMethod
    fun run(channel: String, urgent: Boolean): String
}

class BranchWorkflowImpl : BranchWorkflow {
    private val notifications = activityStub<NotificationActivities>()

    override fun run(channel: String, urgent: Boolean): String {
        // if/else with a primitive in each arm.
        if (urgent) {
            notifications.sms(WAREHOUSE_PHONE, "urgent request")
        } else {
            Workflow.sleep(Duration.ofMinutes(1))
        }

        // when with a primitive in two of three arms.
        when (channel) {
            "email" -> notifications.email(OPS_EMAIL, "request received")
            "sms" -> notifications.sms(WAREHOUSE_PHONE, "request received")
            else -> Unit
        }

        // No primitive in either arm: contributes nothing to the diagram.
        val label = if (urgent) "urgent" else "normal"
        return "$channel/$label"
    }
}
