package com.example.fixture.workflows.worker

import com.example.fixture.helpers.TaskQueues
import com.example.fixture.workflows.handlers.ApprovalWorkflow
import com.example.fixture.workflows.order.Order
import com.example.fixture.workflows.order.OrderLine
import com.example.fixture.workflows.order.OrderWorkflow
import com.example.fixture.workflows.primitives.AwaitWorkflow
import com.example.fixture.workflows.primitives.GREETING_NEXUS_ENDPOINT
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.temporal.api.common.v1.WorkflowExecution
import io.temporal.api.nexus.v1.EndpointSpec
import io.temporal.api.nexus.v1.EndpointTarget
import io.temporal.api.operatorservice.v1.CreateNexusEndpointRequest
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionRequest
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowFailedException
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.VersioningBehavior
import io.temporal.common.WorkerDeploymentVersion
import io.temporal.common.WorkflowExecutionHistory
import io.temporal.serviceclient.OperatorServiceStubs
import io.temporal.serviceclient.OperatorServiceStubsOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.Worker
import io.temporal.worker.WorkerDeploymentOptions
import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkerFactoryOptions
import io.temporal.worker.WorkerOptions
import io.temporal.worker.WorkerPlugin
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import java.util.function.Consumer
import kotlin.system.exitProcess

/**
 * Runs the fixture's workflows on a Temporal server, along the paths the history-capture tests
 * take in the test environment, so that the server holds real executions of every workflow type.
 *
 * ```
 * ./gradlew :workflows:runOnServer --args="--build-id build-a --build-id build-b"
 * ```
 *
 * The server is the one at `TEMPORAL_ADDRESS` (by default `127.0.0.1:7233`), the namespace
 * `TEMPORAL_NAMESPACE` (by default `default`); the namespace must exist. Arguments:
 *
 * - `--build-id <id>`, any number of times: one round of executions per build id, each run by
 *   workers of the Worker Deployment Version made of `--deployment` and that build id, which is
 *   made the deployment's current version first. Without a build id there is one round, run by
 *   workers without a version.
 * - `--deployment <name>`: the Worker Deployment the versions belong to, by default
 *   `sample-monorepo`.
 * - `--quick`: leave out the executions that wait minutes for a timer, in every round. Without
 *   it only the rounds after the first leave them out.
 *
 * An execution of `ApprovalWorkflow` is begun before each change of build id and finished after
 * it, so its workflow tasks are completed by workers of both versions.
 *
 * A real server does not skip time: the first round takes as long as the longest timer, the 30
 * minutes `OrderWorkflow` waits for a payment. Prints one line per execution and exits with
 * status 1 if an execution did not end as its path says it should.
 */
fun main(args: Array<String>) {
    val options = RunOptions.parse(args)
    val service = WorkflowServiceStubs.newConnectedServiceStubs(
        WorkflowServiceStubsOptions.newBuilder().setTarget(options.address).build(),
        Duration.ofSeconds(10),
    )
    var failures = 0
    try {
        val client = WorkflowClient.newInstance(
            service,
            WorkflowClientOptions.newBuilder().setNamespace(options.namespace).setIdentity(IDENTITY).build(),
        )
        createNexusEndpoint(service, options.namespace)

        val rounds: List<String?> = options.buildIds.ifEmpty { listOf(null) }
        var handover: Handover? = null
        rounds.forEachIndexed { index, buildId ->
            val factory = startWorkers(client, options.deployment, buildId)
            try {
                if (buildId != null) makeCurrent(service, options.namespace, options.deployment, buildId)
                handover?.let { failures += report(listOf(it.finish())) }
                val scenarios = Scenarios(client, quick = options.quick || index > 0)
                failures += report(scenarios.runAll())
                handover = if (index < rounds.lastIndex) Handover.begin(client, "ApprovalWorkflow-handover-$buildId") else null
            } finally {
                factory.shutdown()
                factory.awaitTermination(30, TimeUnit.SECONDS)
            }
        }
    } finally {
        service.shutdownNow()
        service.awaitTermination(5, TimeUnit.SECONDS)
    }
    exitProcess(if (failures == 0) 0 else 1)
}

private const val IDENTITY = "fixture-server-run"

private class RunOptions(
    val address: String,
    val namespace: String,
    val deployment: String,
    val buildIds: List<String>,
    val quick: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): RunOptions {
            var deployment = "sample-monorepo"
            val buildIds = ArrayList<String>()
            var quick = false
            var index = 0
            fun value(): String = args.getOrNull(++index) ?: usage("${args[index - 1]} needs a value")
            while (index < args.size) {
                when (args[index]) {
                    "--build-id" -> buildIds += value()
                    "--deployment" -> deployment = value()
                    "--quick" -> quick = true
                    else -> usage("unknown argument ${args[index]}")
                }
                index++
            }
            return RunOptions(
                address = System.getenv("TEMPORAL_ADDRESS")?.ifEmpty { null } ?: "127.0.0.1:7233",
                namespace = System.getenv("TEMPORAL_NAMESPACE")?.ifEmpty { null } ?: "default",
                deployment = deployment,
                buildIds = buildIds,
                quick = quick,
            )
        }

        private fun usage(problem: String): Nothing {
            System.err.println("$problem\nusage: [--build-id <id>]... [--deployment <name>] [--quick]")
            exitProcess(1)
        }
    }
}

/** How an execution ended, as one line of the report. */
private class Ended(val scenario: String, val workflowId: String, val runId: String, val outcome: String, val expected: String) {
    val asExpected: Boolean get() = outcome == expected
}

private fun report(ended: List<Ended>): Int {
    for (execution in ended) {
        val verdict = if (execution.asExpected) execution.outcome else "${execution.outcome}, expected ${execution.expected}"
        println("${execution.scenario}\t${execution.workflowId}\t${execution.runId}\t$verdict")
    }
    return ended.count { !it.asExpected }
}

/** The Nexus endpoint `NexusOperationWorkflow` calls through; it is left alone if it is already there. */
private fun createNexusEndpoint(service: WorkflowServiceStubs, namespace: String) {
    val operator = OperatorServiceStubs.newServiceStubs(
        OperatorServiceStubsOptions.newBuilder().setChannel(service.rawChannel).validateAndBuildWithDefaults(),
    )
    val request = CreateNexusEndpointRequest.newBuilder()
        .setSpec(
            EndpointSpec.newBuilder()
                .setName(GREETING_NEXUS_ENDPOINT)
                .setTarget(
                    EndpointTarget.newBuilder().setWorker(
                        EndpointTarget.Worker.newBuilder().setNamespace(namespace).setTaskQueue(TaskQueues.FIXTURE),
                    ),
                ),
        )
        .build()
    try {
        operator.blockingStub().createNexusEndpoint(request)
    } catch (e: StatusRuntimeException) {
        if (e.status.code != Status.Code.ALREADY_EXISTS) throw e
    }
}

/** Starts the fixture's workers; with a build id, as a Worker Deployment Version whose workflows move to newer versions. */
private fun startWorkers(client: WorkflowClient, deployment: String, buildId: String?): WorkerFactory {
    val factoryOptions = WorkerFactoryOptions.newBuilder()
    if (buildId != null) factoryOptions.setPlugins(DeploymentVersionPlugin(WorkerDeploymentVersion(deployment, buildId)))
    val factory = WorkerFactory.newInstance(client, factoryOptions.build())
    registerFixtureWorkers(factory)
    factory.start()
    return factory
}

/** Gives every worker of a factory the same Worker Deployment Version, without touching how the workers are registered. */
private class DeploymentVersionPlugin(private val version: WorkerDeploymentVersion) : WorkerPlugin {
    override fun getName(): String = "fixture.deployment-version"

    override fun configureWorker(taskQueue: String, builder: WorkerOptions.Builder) {
        builder.setDeploymentOptions(
            WorkerDeploymentOptions.newBuilder()
                .setVersion(version)
                .setUseVersioning(true)
                .setDefaultVersioningBehavior(VersioningBehavior.AUTO_UPGRADE)
                .build(),
        )
    }

    override fun configureWorkerFactory(builder: WorkerFactoryOptions.Builder) = Unit

    override fun initializeWorker(taskQueue: String, worker: Worker) = Unit

    override fun startWorker(taskQueue: String, worker: Worker, next: BiConsumer<String, Worker>) = next.accept(taskQueue, worker)

    override fun shutdownWorker(taskQueue: String, worker: Worker, next: BiConsumer<String, Worker>) = next.accept(taskQueue, worker)

    override fun startWorkerFactory(factory: WorkerFactory, next: Consumer<WorkerFactory>) = next.accept(factory)

    override fun shutdownWorkerFactory(factory: WorkerFactory, next: Consumer<WorkerFactory>) = next.accept(factory)

    override fun replayWorkflowExecution(worker: Worker, history: WorkflowExecutionHistory, next: WorkerPlugin.ReplayCallback) =
        next.replay(worker, history)
}

/**
 * Makes a version the current one of its deployment, so that new executions and those that move
 * with the deployment are given to its workers. The server knows a version only once its workers
 * have polled every task queue, so the call is repeated until it is accepted.
 */
private fun makeCurrent(service: WorkflowServiceStubs, namespace: String, deployment: String, buildId: String) {
    val request = SetWorkerDeploymentCurrentVersionRequest.newBuilder()
        .setNamespace(namespace)
        .setDeploymentName(deployment)
        .setBuildId(buildId)
        .setIdentity(IDENTITY)
        .build()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
    while (true) {
        try {
            service.blockingStub().setWorkerDeploymentCurrentVersion(request)
            return
        } catch (e: StatusRuntimeException) {
            if (System.nanoTime() > deadline) throw e
            Thread.sleep(1_000)
        }
    }
}

/** An approval that is asked for while one version is current and given once the next one is. */
private class Handover private constructor(private val client: WorkflowClient, private val started: Started) {
    fun finish(): Ended {
        val workflowId = started.execution.workflowId
        val handlers = client.newWorkflowStub(ApprovalWorkflow::class.java, workflowId)
        handlers.onApprove("first")
        handlers.onApprove("second")
        return ended("handover", started, expected = "completed")
    }

    companion object {
        fun begin(client: WorkflowClient, workflowId: String): Handover {
            val started = start(client, "ApprovalWorkflow", workflowId, TaskQueues.FIXTURE, "request-8")
            val handlers = client.newWorkflowStub(ApprovalWorkflow::class.java, workflowId)
            handlers.setRequiredApprovals(2)
            handlers.addNote("approved after the next release")
            return Handover(client, started)
        }
    }
}

private class Started(val stub: WorkflowStub, val execution: WorkflowExecution)

private fun start(client: WorkflowClient, workflowType: String, workflowId: String, taskQueue: String, vararg args: Any?): Started {
    val stub = client.newUntypedWorkflowStub(
        workflowType,
        WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(taskQueue).build(),
    )
    return Started(stub, stub.start(*args))
}

/** Waits for the execution [started] to end. A chain of runs is followed to its last run. */
private fun ended(scenario: String, started: Started, expected: String): Ended {
    val outcome = try {
        started.stub.getResult(Any::class.java)
        "completed"
    } catch (e: WorkflowFailedException) {
        "failed"
    }
    return Ended(scenario, started.execution.workflowId, started.execution.runId, outcome, expected)
}

/**
 * The executions of one round: every path of `WorkflowHistoriesTest` and `OrderWorkflowHistoryTest`,
 * with the same inputs, signals and updates. They run side by side.
 */
private class Scenarios(private val client: WorkflowClient, private val quick: Boolean) {
    private val order = Order(
        orderId = "1001",
        customerEmail = "customer@example.com",
        lines = listOf(
            OrderLine(sku = "SKU-CHAIR", quantity = 2, unitPriceCents = 4_500),
            OrderLine(sku = "SKU-LAMP", quantity = 1, unitPriceCents = 1_999),
        ),
    )

    private class Scenario(val name: String, val slow: Boolean, val run: () -> Ended)

    private fun simple(workflowType: String, vararg args: Any?, expected: String = "completed", slow: Boolean = false) =
        Scenario(workflowType, slow) {
            ended(workflowType, start(client, workflowType, "$workflowType-1", TaskQueues.FIXTURE, *args), expected)
        }

    private val all: List<Scenario> = listOf(
        // One primitive each.
        simple("TypedActivityWorkflow", "SKU-1"),
        simple("LocalActivityWorkflow", "SKU-1"),
        simple("UntypedActivityWorkflow", "SKU-1"),
        simple("ParentWorkflow", "World"),
        simple("TimerWorkflow", slow = true),
        Scenario("AwaitWorkflow", slow = true) {
            val started = start(client, "AwaitWorkflow", "AwaitWorkflow-1", TaskQueues.FIXTURE)
            client.newWorkflowStub(AwaitWorkflow::class.java, "AwaitWorkflow-1").release()
            // One signal only: the second await runs into its ten minute timeout.
            ended("AwaitWorkflow", started, "completed")
        },
        simple("ParallelWorkflow", "SKU-1"),
        simple("VersionGateWorkflow", "1001", "SKU-1"),
        simple("ContinueAsNewWorkflow", 0, 2),
        simple("SideEffectWorkflow"),
        simple("NexusOperationWorkflow", "World"),
        // The charge is declined, so what was done is compensated and the workflow fails.
        simple("SagaWorkflow", "1001", "SKU-1", 2_000_000L, expected = "failed"),
        simple("ApplicationFailureWorkflow", "1001", 0L, expected = "failed"),
        simple("MethodReferenceWorkflow", "1001", "SKU-1", 2_000_000L, expected = "failed"),
        // Handlers, and a signal from another workflow.
        Scenario("ApprovalWorkflow", slow = false) {
            val approvalId = "ApprovalWorkflow-1"
            val approval = start(client, "ApprovalWorkflow", approvalId, TaskQueues.FIXTURE, "request-7")
            val handlers = client.newWorkflowStub(ApprovalWorkflow::class.java, approvalId)
            handlers.setRequiredApprovals(2)
            handlers.addNote("needs two approvers")
            handlers.currentStatus()
            // The first approval comes from another workflow, the second from a client.
            ended("SignalExternalWorkflow", start(client, "SignalExternalWorkflow", "SignalExternalWorkflow-1", TaskQueues.FIXTURE, approvalId), "completed")
            handlers.onApprove("client")
            ended("ApprovalWorkflow", approval, "completed")
        },
        // Control flow.
        simple("BranchWorkflow", "email", true),
        simple("LoopWorkflow", "1001", listOf("SKU-1", "SKU-2")),
        simple("TryCatchWorkflow", "1001", 2_000_000L),
        simple("EarlyReturnWorkflow", "OOS-1"),
        simple("ScopeFunctionWorkflow", "1001", "SKU-1"),
        simple("DynamicFanOutWorkflow", listOf("SKU-1", "SKU-2", "SKU-3")),
        // Calls through the helper library.
        simple("HelperInliningWorkflow", "1001", listOf("SKU-1", "SKU-2")),
        // Hard cases.
        simple("ComputedActivityNameWorkflow", "express", "1001", "SKU-1"),
        simple("DynamicDispatchWorkflow", "sms", "+15550123"),
        // Never in stock: six availability checks with five sleeps of 30 seconds between them.
        simple("RecursionWorkflow", "OOS-1", slow = true),
        simple("DeepHelperChainWorkflow", "SKU-1"),
        // The composite workflow along its three paths.
        Scenario("OrderWorkflow paid", slow = false) {
            val started = start(client, "OrderWorkflow", "order-1001", TaskQueues.ORDERS, order)
            val workflow = client.newWorkflowStub(OrderWorkflow::class.java, "order-1001")
            awaitStatus(workflow, "AWAITING_PAYMENT")
            workflow.changeAddress("1 Example Street")
            workflow.paymentReceived("bank-transfer-42")
            ended("OrderWorkflow paid", started, "completed")
        },
        Scenario("OrderWorkflow payment-timeout", slow = true) {
            // No signal: the order is cancelled when the 30 minute deadline passes.
            val unpaid = order.copy(orderId = "1002")
            ended("OrderWorkflow payment-timeout", start(client, "OrderWorkflow", "order-1002", TaskQueues.ORDERS, unpaid), "completed")
        },
        Scenario("OrderWorkflow payment-declined", slow = false) {
            val expensive = order.copy(
                orderId = "1003",
                lines = listOf(OrderLine(sku = "SKU-YACHT", quantity = 1, unitPriceCents = 250_000_000)),
            )
            val started = start(client, "OrderWorkflow", "order-1003", TaskQueues.ORDERS, expensive)
            val workflow = client.newWorkflowStub(OrderWorkflow::class.java, "order-1003")
            awaitStatus(workflow, "AWAITING_PAYMENT")
            workflow.paymentReceived("bank-transfer-43")
            ended("OrderWorkflow payment-declined", started, "failed")
        },
    )

    fun runAll(): List<Ended> {
        val chosen = all.filter { !quick || !it.slow }
        val pool = Executors.newFixedThreadPool(chosen.size)
        try {
            val running = chosen.map { scenario ->
                scenario to pool.submit(
                    Callable {
                        try {
                            scenario.run()
                        } catch (e: RuntimeException) {
                            Ended(scenario.name, "", "", "not run: $e", "run")
                        }
                    },
                )
            }
            return running.map { (_, result) -> result.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun awaitStatus(workflow: OrderWorkflow, expected: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (workflow.status() != expected) {
            check(System.nanoTime() < deadline) { "order did not reach $expected" }
            Thread.sleep(100)
        }
    }
}
