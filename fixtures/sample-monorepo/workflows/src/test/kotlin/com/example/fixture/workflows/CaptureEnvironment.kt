package com.example.fixture.workflows

import com.example.fixture.helpers.TaskQueues
import com.example.fixture.workflows.primitives.GREETING_NEXUS_ENDPOINT
import com.example.fixture.workflows.worker.registerFixtureWorkers
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.time.Instant

/**
 * An in-memory, time-skipping Temporal service with every fixture worker registered.
 * Each capture uses its own environment so that histories do not depend on test order.
 */
class CaptureEnvironment : AutoCloseable {
    private val env: TestWorkflowEnvironment = TestWorkflowEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder()
            .setInitialTime(INITIAL_TIME)
            .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setIdentity(IDENTITY).build())
            .build(),
    )

    val client: WorkflowClient get() = env.workflowClient

    init {
        registerFixtureWorkers(env.workerFactory)
        env.start()
        env.createNexusEndpoint(GREETING_NEXUS_ENDPOINT, TaskQueues.FIXTURE)
    }

    /** Starts a workflow by type name with a fixed workflow id. */
    fun start(workflowType: String, workflowId: String, taskQueue: String, vararg args: Any?): Started {
        val stub = client.newUntypedWorkflowStub(
            workflowType,
            WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(taskQueue).build(),
        )
        return Started(stub, stub.start(*args))
    }

    /** Saves the history of one run (the latest run when [runId] is empty) as `<name>.json`. */
    fun saveHistory(name: String, workflowId: String, runId: String = ""): WorkflowExecutionHistory {
        val history = if (runId.isEmpty()) client.fetchHistory(workflowId) else client.fetchHistory(workflowId, runId)
        HistoryFiles.save(name, history)
        return history
    }

    override fun close() {
        env.close()
    }

    class Started(val stub: WorkflowStub, val execution: WorkflowExecution)

    companion object {
        private val INITIAL_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")
        private const val IDENTITY = "fixture-test"

        /**
         * Runs [block] against a fresh environment. Returns Unit so that it can be the
         * expression body of a test method (JUnit ignores test methods that return a value).
         */
        fun use(block: (CaptureEnvironment) -> Unit) {
            CaptureEnvironment().use(block)
        }
    }
}
