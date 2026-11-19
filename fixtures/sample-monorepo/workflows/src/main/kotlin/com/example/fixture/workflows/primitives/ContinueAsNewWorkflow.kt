package com.example.fixture.workflows.primitives

import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface ContinueAsNewWorkflow {
    @WorkflowMethod
    fun run(iteration: Int, limit: Int): Int
}

class ContinueAsNewWorkflowImpl : ContinueAsNewWorkflow {
    override fun run(iteration: Int, limit: Int): Int {
        Workflow.sleep(Duration.ofSeconds(1))
        if (iteration >= limit) {
            return iteration
        }
        if (iteration % 2 == 0) {
            // Static form.
            Workflow.continueAsNew(iteration + 1, limit)
        }
        // Stub form: the call never returns to this run.
        val next = Workflow.newContinueAsNewStub(ContinueAsNewWorkflow::class.java)
        return next.run(iteration + 1, limit)
    }
}
