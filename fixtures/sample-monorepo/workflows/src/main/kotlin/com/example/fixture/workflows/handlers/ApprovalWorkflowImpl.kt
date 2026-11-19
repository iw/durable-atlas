package com.example.fixture.workflows.handlers

import com.example.fixture.activities.NotificationActivities
import com.example.fixture.helpers.OPS_EMAIL
import com.example.fixture.helpers.activityStub
import io.temporal.workflow.Workflow
import java.time.Duration

class ApprovalWorkflowImpl : ApprovalWorkflow {
    private val notifications = activityStub<NotificationActivities>()

    private var approvals = 0
    private var requiredApprovals = 1
    private var rejectionReason: String? = null
    private var lastNote = ""

    override fun run(requestId: String): String {
        notifications.email(OPS_EMAIL, "approval requested for $requestId")

        val decided = Workflow.await(Duration.ofHours(24)) {
            approvals >= requiredApprovals || rejectionReason != null
        }
        if (!decided) {
            notifications.email(OPS_EMAIL, "approval request $requestId expired")
            return "expired"
        }
        if (rejectionReason != null) {
            return "rejected"
        }
        notifications.audit("request $requestId approved")
        return "approved"
    }

    override fun onApprove(approver: String) {
        approvals += 1
    }

    override fun reject(reason: String) {
        rejectionReason = reason
    }

    override fun currentStatus(): String = when {
        rejectionReason != null -> "rejected"
        approvals >= requiredApprovals -> "approved"
        else -> "pending"
    }

    override fun approvalCount(): Int = approvals

    override fun setRequiredApprovals(count: Int): Int {
        val previous = requiredApprovals
        requiredApprovals = count
        return previous
    }

    override fun validateRequiredApprovals(count: Int) {
        require(count > 0) { "at least one approval is required" }
    }

    override fun addNote(note: String): String {
        // An update handler may block: this one runs an activity.
        notifications.audit("note added: $note")
        val previous = lastNote
        lastNote = note
        return previous
    }
}
