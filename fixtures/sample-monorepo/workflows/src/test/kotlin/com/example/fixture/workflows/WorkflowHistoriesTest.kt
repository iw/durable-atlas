package com.example.fixture.workflows

import com.example.fixture.helpers.TaskQueues
import com.example.fixture.workflows.handlers.ApprovalWorkflow
import com.example.fixture.workflows.primitives.AwaitWorkflow
import io.temporal.api.enums.v1.EventType
import io.temporal.client.WorkflowFailedException
import io.temporal.common.WorkflowExecutionHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Runs every other fixture workflow once with fixed inputs and saves its history as
 * `<WorkflowType>.json`.
 */
class WorkflowHistoriesTest {
    // --- one primitive each ---

    @Test
    fun typedActivity() = capture("TypedActivityWorkflow", "SKU-1") { result, _ ->
        assertEquals("pay-res-SKU-1-1", result)
    }

    @Test
    fun localActivity() = capture("LocalActivityWorkflow", "SKU-1") { result, history ->
        assertEquals("res-SKU-1-1", result)
        assertEquals(1, history.count(EventType.EVENT_TYPE_MARKER_RECORDED))
    }

    @Test
    fun untypedActivity() = capture("UntypedActivityWorkflow", "SKU-1") { result, _ ->
        assertEquals("res-SKU-1-1", result)
    }

    @Test
    fun childWorkflow() = CaptureEnvironment.use { env ->
        val started = env.start("ParentWorkflow", "ParentWorkflow-1", TaskQueues.FIXTURE, "World")
        assertEquals("Hello, World", started.stub.getResult(String::class.java))
        val history = env.saveHistory("ParentWorkflow", "ParentWorkflow-1")
        assertEquals(1, history.count(EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED))
        env.saveHistory("GreetingChildWorkflow", "ParentWorkflow-1-greeting")
    }

    @Test
    fun timer() = capture("TimerWorkflow") { _, history ->
        assertEquals(3, history.count(EventType.EVENT_TYPE_TIMER_STARTED))
    }

    @Test
    fun await() = CaptureEnvironment.use { env ->
        val started = env.start("AwaitWorkflow", "AwaitWorkflow-1", TaskQueues.FIXTURE)
        env.client.newWorkflowStub(AwaitWorkflow::class.java, "AwaitWorkflow-1").release()
        // One signal only: the second await runs into its ten minute timeout.
        assertEquals("timed-out", started.stub.getResult(String::class.java))
        env.saveHistory("AwaitWorkflow", "AwaitWorkflow-1")
    }

    @Test
    fun parallel() = capture("ParallelWorkflow", "SKU-1") { result, _ ->
        assertEquals("res-SKU-1-1", result)
    }

    @Test
    fun versionGate() = capture("VersionGateWorkflow", "1001", "SKU-1") { result, history ->
        assertEquals("exp-1001-SKU-1", result)
        assertEquals(1, history.count(EventType.EVENT_TYPE_MARKER_RECORDED))
    }

    @Test
    fun continueAsNew() = CaptureEnvironment.use { env ->
        val started = env.start("ContinueAsNewWorkflow", "ContinueAsNewWorkflow-1", TaskQueues.FIXTURE, 0, 2)
        assertEquals(2, started.stub.getResult(Int::class.javaObjectType))
        // The first run of the chain, which ends by continuing as new.
        val history = env.saveHistory("ContinueAsNewWorkflow", "ContinueAsNewWorkflow-1", started.execution.runId)
        assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW, history.lastEvent.eventType)
    }

    @Test
    fun sideEffect() = capture("SideEffectWorkflow") { result, history ->
        assertEquals("eu-west-1/50", result)
        assertEquals(2, history.count(EventType.EVENT_TYPE_MARKER_RECORDED))
    }

    @Test
    fun nexusOperation() = capture("NexusOperationWorkflow", "World") { result, history ->
        assertEquals("Hello, World / Goodbye, World", result)
        assertEquals(2, history.count(EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED))
    }

    @Test
    fun saga() = CaptureEnvironment.use { env ->
        // The charge is declined, so the reservation is compensated and the workflow fails.
        val started = env.start("SagaWorkflow", "SagaWorkflow-1", TaskQueues.FIXTURE, "1001", "SKU-1", 2_000_000L)
        assertFailsWith<WorkflowFailedException> { started.stub.getResult(String::class.java) }
        val history = env.saveHistory("SagaWorkflow", "SagaWorkflow-1")
        assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED, history.lastEvent.eventType)
    }

    @Test
    fun applicationFailure() = CaptureEnvironment.use { env ->
        val started = env.start("ApplicationFailureWorkflow", "ApplicationFailureWorkflow-1", TaskQueues.FIXTURE, "1001", 0L)
        assertFailsWith<WorkflowFailedException> { started.stub.getResult(String::class.java) }
        val history = env.saveHistory("ApplicationFailureWorkflow", "ApplicationFailureWorkflow-1")
        assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED, history.lastEvent.eventType)
    }

    @Test
    fun methodReference() = CaptureEnvironment.use { env ->
        // The charge is declined, so the reservation is released: both through references to stub methods.
        val started = env.start(
            "MethodReferenceWorkflow",
            "MethodReferenceWorkflow-1",
            TaskQueues.FIXTURE,
            "1001",
            "SKU-1",
            2_000_000L,
        )
        assertFailsWith<WorkflowFailedException> { started.stub.getResult(String::class.java) }
        val history = env.saveHistory("MethodReferenceWorkflow", "MethodReferenceWorkflow-1")
        assertEquals(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED, history.lastEvent.eventType)
        assertEquals(4, history.count(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED))
    }

    // --- handlers and signalling another workflow ---

    @Test
    fun approvalAndSignalExternal() = CaptureEnvironment.use { env ->
        val approvalId = "ApprovalWorkflow-1"
        val approval = env.start("ApprovalWorkflow", approvalId, TaskQueues.FIXTURE, "request-7")
        val handlers = env.client.newWorkflowStub(ApprovalWorkflow::class.java, approvalId)

        assertEquals(1, handlers.setRequiredApprovals(2))
        assertEquals("", handlers.addNote("needs two approvers"))
        assertEquals("pending", handlers.currentStatus())

        // First approval comes from another workflow, the second from a client.
        val signaller = env.start("SignalExternalWorkflow", "SignalExternalWorkflow-1", TaskQueues.FIXTURE, approvalId)
        signaller.stub.getResult(Void::class.java)
        handlers.onApprove("client")

        assertEquals("approved", approval.stub.getResult(String::class.java))
        assertEquals(2, handlers.approvalCount())

        val history = env.saveHistory("ApprovalWorkflow", approvalId)
        assertEquals(2, history.count(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED))
        assertEquals(2, history.count(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED))
        val signallerHistory = env.saveHistory("SignalExternalWorkflow", "SignalExternalWorkflow-1")
        assertEquals(1, signallerHistory.count(EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED))
    }

    // --- control flow ---

    @Test
    fun branch() = capture("BranchWorkflow", "email", true) { result, _ ->
        assertEquals("email/urgent", result)
    }

    @Test
    fun loop() = CaptureEnvironment.use { env ->
        val started = env.start("LoopWorkflow", "LoopWorkflow-1", TaskQueues.FIXTURE, "1001", listOf("SKU-1", "SKU-2"))
        assertEquals(998L, started.stub.getResult(Long::class.javaObjectType))
        env.saveHistory("LoopWorkflow", "LoopWorkflow-1")
    }

    @Test
    fun tryCatch() = capture("TryCatchWorkflow", "1001", 2_000_000L) { result, _ ->
        // The charge is declined, so the catch block and the finally block both run.
        assertEquals("charge-failed", result)
    }

    @Test
    fun earlyReturn() = capture("EarlyReturnWorkflow", "OOS-1") { result, _ ->
        assertEquals("unavailable", result)
    }

    @Test
    fun scopeFunction() = capture("ScopeFunctionWorkflow", "1001", "SKU-1") { result, history ->
        assertEquals("res-SKU-1-1", result)
        assertEquals(6, history.count(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED))
    }

    @Test
    fun dynamicFanOut() = CaptureEnvironment.use { env ->
        val skus = listOf("SKU-1", "SKU-2", "SKU-3")
        val started = env.start("DynamicFanOutWorkflow", "DynamicFanOutWorkflow-1", TaskQueues.FIXTURE, skus)
        assertEquals(1497L, started.stub.getResult(Long::class.javaObjectType))
        val history = env.saveHistory("DynamicFanOutWorkflow", "DynamicFanOutWorkflow-1")
        assertEquals(3, history.count(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED))
    }

    // --- calls through the helper library ---

    @Test
    fun helperInlining() = CaptureEnvironment.use { env ->
        val skus = listOf("SKU-1", "SKU-2")
        val started = env.start("HelperInliningWorkflow", "HelperInliningWorkflow-1", TaskQueues.FIXTURE, "1001", skus)
        assertEquals("pay-1001", started.stub.getResult(String::class.java))
        val history = env.saveHistory("HelperInliningWorkflow", "HelperInliningWorkflow-1")
        assertEquals(8, history.count(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED))
    }

    // --- hard cases ---

    @Test
    fun computedActivityName() = capture("ComputedActivityNameWorkflow", "express", "1001", "SKU-1") { result, _ ->
        assertEquals("exp-1001-SKU-1", result)
    }

    @Test
    fun dynamicDispatch() = capture("DynamicDispatchWorkflow", "sms", "+15550123") { _, history ->
        assertEquals(1, history.count(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED))
    }

    @Test
    fun recursion() = CaptureEnvironment.use { env ->
        // Never in stock: six availability checks with five sleeps between them.
        val started = env.start("RecursionWorkflow", "RecursionWorkflow-1", TaskQueues.FIXTURE, "OOS-1")
        assertEquals(false, started.stub.getResult(Boolean::class.javaObjectType))
        val history = env.saveHistory("RecursionWorkflow", "RecursionWorkflow-1")
        assertEquals(6, history.count(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED))
        assertEquals(5, history.count(EventType.EVENT_TYPE_TIMER_STARTED))
    }

    @Test
    fun deepHelperChain() = capture("DeepHelperChainWorkflow", "SKU-1") { result, history ->
        assertEquals("res-SKU-1-1", result)
        assertEquals(1, history.count(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED))
    }

    /** Runs a workflow that completes with a string (or nothing) on the fixture task queue. */
    private fun capture(
        workflowType: String,
        vararg args: Any?,
        verify: (result: String?, history: WorkflowExecutionHistory) -> Unit,
    ) = CaptureEnvironment.use { env ->
        val workflowId = "$workflowType-1"
        val started = env.start(workflowType, workflowId, TaskQueues.FIXTURE, *args)
        val result = started.stub.getResult(String::class.java)
        verify(result, env.saveHistory(workflowType, workflowId))
    }

    private fun WorkflowExecutionHistory.count(type: EventType): Int = events.count { it.eventType == type }
}
