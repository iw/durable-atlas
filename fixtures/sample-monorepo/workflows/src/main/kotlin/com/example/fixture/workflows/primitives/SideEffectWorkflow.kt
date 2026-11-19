package com.example.fixture.workflows.primitives

import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface SideEffectWorkflow {
    @WorkflowMethod
    fun run(): String
}

class SideEffectWorkflowImpl : SideEffectWorkflow {
    override fun run(): String {
        val region = Workflow.sideEffect(String::class.java) {
            System.getProperty("fixture.region", "eu-west-1")
        }
        val batchSize = Workflow.mutableSideEffect(
            "batch-size",
            Int::class.javaObjectType,
            { previous, current -> previous == current },
            { Integer.getInteger("fixture.batchSize", 50) },
        )
        return "$region/$batchSize"
    }
}
