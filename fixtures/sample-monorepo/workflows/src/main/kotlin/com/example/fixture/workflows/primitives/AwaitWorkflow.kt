package com.example.fixture.workflows.primitives

import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface AwaitWorkflow {
    @WorkflowMethod
    fun run(): String

    @SignalMethod
    fun release()
}

class AwaitWorkflowImpl : AwaitWorkflow {
    private var released = false

    override fun run(): String {
        // Without timeout: blocks until the first signal.
        Workflow.await { released }
        released = false

        // With timeout: returns false when no second signal arrives in time.
        val releasedAgain = Workflow.await(Duration.ofMinutes(10)) { released }
        if (!releasedAgain) {
            Workflow.sleep(Duration.ofSeconds(1))
            return "timed-out"
        }
        return "released"
    }

    override fun release() {
        released = true
    }
}
