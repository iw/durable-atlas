package com.example.orders

import com.example.orders.shipping.WarehouseWorkflow
import com.example.orders.shipping.warehouseWorkflowId
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.testing.TestEnvironmentOptions
import io.temporal.testing.TestWorkflowEnvironment
import java.time.Duration
import java.time.Instant

/**
 * An in-memory, time-skipping Temporal service with the workers of this build, the loyalty
 * endpoint and a workflow for each warehouse. Every test uses its own, so that histories do not
 * depend on the order of the tests.
 */
class OrdersTestEnvironment(suggestContinueAsNew: Boolean = false) : AutoCloseable {
    private val env: TestWorkflowEnvironment = TestWorkflowEnvironment.newInstance(
        TestEnvironmentOptions.newBuilder()
            .setInitialTime(INITIAL_TIME)
            .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().setIdentity(IDENTITY).build())
            .setWorkflowServiceStubsOptions(
                WorkflowServiceStubsOptions.newBuilder()
                    .setGrpcClientInterceptors(if (suggestContinueAsNew) listOf(SuggestContinueAsNew) else emptyList())
                    .build(),
            )
            .build(),
    )

    val client: WorkflowClient get() = env.workflowClient

    init {
        registerWorkers(env.workerFactory)
        env.newWorker(WAREHOUSE_TASK_QUEUE).registerWorkflowImplementationTypes(StandInWarehouseWorkflow::class.java)
        env.start()
        env.createNexusEndpoint(LOYALTY_ENDPOINT, LOYALTY_TASK_QUEUE)
        WAREHOUSES.forEach { warehouseId ->
            val warehouse = client.newWorkflowStub(
                WarehouseWorkflow::class.java,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(warehouseWorkflowId(warehouseId))
                    .setTaskQueue(WAREHOUSE_TASK_QUEUE)
                    .build(),
            )
            WorkflowClient.start { warehouse.run(warehouseId) }
        }
    }

    /** Starts the workflow of [order] under the workflow id [workflowId]. */
    fun start(workflowId: String, order: Order): WorkflowStub {
        val stub = client.newUntypedWorkflowStub(
            "OrderWorkflow",
            WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(ORDERS_TASK_QUEUE).build(),
        )
        stub.start(order)
        return stub
    }

    /** Lets [duration] pass on the service's clock. */
    fun sleep(duration: Duration) = env.sleep(duration)

    /** Saves the history of one run (the latest run when [runId] is empty) as `<name>.json`. */
    fun saveHistory(name: String, workflowId: String, runId: String = ""): WorkflowExecutionHistory {
        val history = if (runId.isEmpty()) client.fetchHistory(workflowId) else client.fetchHistory(workflowId, runId)
        HistoryFiles.save(name, history)
        return history
    }

    override fun close() {
        env.close()
    }

    companion object {
        val WAREHOUSES = listOf("north", "south")

        private const val WAREHOUSE_TASK_QUEUE = "warehouse"
        private val INITIAL_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")
        private const val IDENTITY = "orders-test"

        /**
         * Runs [block] against a fresh environment. Returns Unit so that it can be the
         * expression body of a test method (JUnit ignores test methods that return a value).
         */
        fun use(suggestContinueAsNew: Boolean = false, block: (OrdersTestEnvironment) -> Unit) {
            OrdersTestEnvironment(suggestContinueAsNew).use(block)
        }
    }
}
