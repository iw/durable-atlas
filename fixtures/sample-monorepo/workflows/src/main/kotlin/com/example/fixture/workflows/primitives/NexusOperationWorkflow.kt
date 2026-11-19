package com.example.fixture.workflows.primitives

import com.example.fixture.nexus.GreetingService
import io.temporal.workflow.NexusOperationOptions
import io.temporal.workflow.NexusServiceOptions
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

const val GREETING_NEXUS_ENDPOINT = "fixture-greeting-endpoint"

@WorkflowInterface
interface NexusOperationWorkflow {
    @WorkflowMethod
    fun run(name: String): String
}

class NexusOperationWorkflowImpl : NexusOperationWorkflow {
    private val greetings = Workflow.newNexusServiceStub(
        GreetingService::class.java,
        NexusServiceOptions.newBuilder()
            .setEndpoint(GREETING_NEXUS_ENDPOINT)
            .setOperationOptions(
                NexusOperationOptions.newBuilder()
                    .setScheduleToCloseTimeout(Duration.ofSeconds(30))
                    .build(),
            )
            .build(),
    )

    override fun run(name: String): String {
        // Synchronous call through the typed stub: operation "greet".
        val hello = greetings.greet(name)
        // Started through a handle: operation "say-goodbye".
        val goodbye = Workflow.startNexusOperation(greetings::farewell, name)
        return "$hello / ${goodbye.result.get()}"
    }
}
