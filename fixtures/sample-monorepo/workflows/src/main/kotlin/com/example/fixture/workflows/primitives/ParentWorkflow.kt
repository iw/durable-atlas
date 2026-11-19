package com.example.fixture.workflows.primitives

import io.temporal.workflow.ChildWorkflowOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod

@WorkflowInterface
interface ParentWorkflow {
    @WorkflowMethod
    fun run(name: String): String
}

class ParentWorkflowImpl : ParentWorkflow {
    override fun run(name: String): String {
        val child = Workflow.newChildWorkflowStub(
            GreetingChildWorkflow::class.java,
            ChildWorkflowOptions.newBuilder()
                .setWorkflowId("${Workflow.getInfo().workflowId}-greeting")
                .build(),
        )
        return child.greet(name)
    }
}

@WorkflowInterface
interface GreetingChildWorkflow {
    @WorkflowMethod
    fun greet(name: String): String
}

/** A workflow with no Temporal primitives at all. */
class GreetingChildWorkflowImpl : GreetingChildWorkflow {
    override fun greet(name: String): String = "Hello, $name"
}
