package com.example.fixture.workflows.handlers

import io.temporal.workflow.QueryMethod
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.UpdateMethod
import io.temporal.workflow.UpdateValidatorMethod
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface ApprovalWorkflow {
    @WorkflowMethod
    fun run(requestId: String): String

    /** Signal `approve` (explicit name). */
    @SignalMethod(name = "approve")
    fun onApprove(approver: String)

    /** Signal `reject` (default name: the method name). */
    @SignalMethod
    fun reject(reason: String)

    /** Query `status` (explicit name). */
    @QueryMethod(name = "status")
    fun currentStatus(): String

    /** Query `approvalCount` (default name: the method name). */
    @QueryMethod
    fun approvalCount(): Int

    /** Update `set-required-approvals` (explicit name), guarded by a validator. */
    @UpdateMethod(name = "set-required-approvals")
    fun setRequiredApprovals(count: Int): Int

    @UpdateValidatorMethod(updateName = "set-required-approvals")
    fun validateRequiredApprovals(count: Int)

    /** Update `addNote` (default name: the method name), no validator. */
    @UpdateMethod
    fun addNote(note: String): String
}
