package com.example.fixture.workflows.primitives

import com.example.fixture.workflows.handlers.ApprovalWorkflow
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface SignalExternalWorkflow {
    @WorkflowMethod
    fun run(approvalWorkflowId: String)
}

class SignalExternalWorkflowImpl : SignalExternalWorkflow {
    override fun run(approvalWorkflowId: String) {
        val approval = Workflow.newExternalWorkflowStub(ApprovalWorkflow::class.java, approvalWorkflowId)
        // The signal is named "approve" on the target interface, not "onApprove".
        approval.onApprove("signal-external-workflow")
    }
}
