package com.example.fixture.workflows.primitives

import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

@WorkflowInterface
interface TimerWorkflow {
    @WorkflowMethod
    fun run()
}

class TimerWorkflowImpl : TimerWorkflow {
    override fun run() {
        Workflow.sleep(Duration.ofSeconds(30))
        Workflow.sleep(1_000)
        Workflow.newTimer(Duration.ofMinutes(5)).get()
    }
}
