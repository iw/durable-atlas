package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.analyser.code.Call
import io.github.iw.durableatlas.ir.EntryPointKind

/** Fully qualified names of what graph extraction recognises in the Temporal SDK and around it. */
internal object TemporalApi {
    const val WORKFLOW = "io.temporal.workflow.Workflow"
    const val ACTIVITY_STUB = "io.temporal.workflow.ActivityStub"
    const val CHILD_WORKFLOW_STUB = "io.temporal.workflow.ChildWorkflowStub"
    const val EXTERNAL_WORKFLOW_STUB = "io.temporal.workflow.ExternalWorkflowStub"
    const val NEXUS_SERVICE_STUB = "io.temporal.workflow.NexusServiceStub"
    const val SAGA = "io.temporal.workflow.Saga"
    const val ASYNC = "io.temporal.workflow.Async"
    const val PROMISE = "io.temporal.workflow.Promise"
    const val COMPLETABLE_PROMISE = "io.temporal.workflow.CompletablePromise"
    const val QUEUE_CONSUMER = "io.temporal.workflow.QueueConsumer"
    const val QUEUE_PRODUCER = "io.temporal.workflow.QueueProducer"
    const val WORKFLOW_QUEUE = "io.temporal.workflow.WorkflowQueue"
    const val CANCELLATION_SCOPE = "io.temporal.workflow.CancellationScope"
    const val WORKFLOW_LOCK = "io.temporal.workflow.WorkflowLock"
    const val WORKFLOW_SEMAPHORE = "io.temporal.workflow.WorkflowSemaphore"
    const val WORKFLOW_INFO = "io.temporal.workflow.WorkflowInfo"
    const val APPLICATION_FAILURE = "io.temporal.failure.ApplicationFailure"
    const val APPLICATION_FAILURE_BUILDER = "io.temporal.failure.ApplicationFailure.Builder"

    const val ACTIVITY_OPTIONS = "io.temporal.activity.ActivityOptions"
    const val LOCAL_ACTIVITY_OPTIONS = "io.temporal.activity.LocalActivityOptions"
    const val ACTIVITY_OPTIONS_BUILDER = "$ACTIVITY_OPTIONS.Builder"
    const val LOCAL_ACTIVITY_OPTIONS_BUILDER = "$LOCAL_ACTIVITY_OPTIONS.Builder"
    const val WORKFLOW_IMPLEMENTATION_OPTIONS = "io.temporal.worker.WorkflowImplementationOptions"
    const val WORKFLOW_IMPLEMENTATION_OPTIONS_BUILDER = "$WORKFLOW_IMPLEMENTATION_OPTIONS.Builder"

    /** The function of a worker that is told the classes that implement workflows, with or without options for them. */
    const val REGISTER_WORKFLOWS = "io.temporal.worker.Worker.registerWorkflowImplementationTypes"
    const val SEARCH_ATTRIBUTE_KEY = "io.temporal.common.SearchAttributeKey"
    const val SEARCH_ATTRIBUTE_UPDATE = "io.temporal.common.SearchAttributeUpdate"

    /** The functions that make the update of one search attribute: one that sets it, one that removes it. */
    val SEARCH_ATTRIBUTE_UPDATES = setOf("valueSet", "valueUnset")
    const val CHILD_WORKFLOW_OPTIONS_BUILDER = "io.temporal.workflow.ChildWorkflowOptions.Builder"
    const val NEXUS_SERVICE_OPTIONS_BUILDER = "io.temporal.workflow.NexusServiceOptions.Builder"
    const val NEXUS_OPERATION_OPTIONS_BUILDER = "io.temporal.workflow.NexusOperationOptions.Builder"
    const val SAGA_OPTIONS_BUILDER = "io.temporal.workflow.Saga.Options.Builder"
    const val RETRY_OPTIONS_BUILDER = "io.temporal.common.RetryOptions.Builder"
    const val TIMER_OPTIONS = "io.temporal.workflow.TimerOptions"
    const val TIMER_OPTIONS_BUILDER = "io.temporal.workflow.TimerOptions.Builder"

    const val DYNAMIC_WORKFLOW = "io.temporal.workflow.DynamicWorkflow"
    const val DYNAMIC_SIGNAL_HANDLER = "io.temporal.workflow.DynamicSignalHandler"
    const val DYNAMIC_QUERY_HANDLER = "io.temporal.workflow.DynamicQueryHandler"
    const val DYNAMIC_UPDATE_HANDLER = "io.temporal.workflow.DynamicUpdateHandler"
    const val WORKFLOW_INIT = "io.temporal.workflow.WorkflowInit"

    /**
     * The handlers that take every name no other handler takes, each with what it handles. An
     * object handed to `Workflow.registerListener` is asked for them in this order, and is the
     * first of them it implements.
     */
    val DYNAMIC_HANDLERS: Map<String, EntryPointKind> = linkedMapOf(
        DYNAMIC_SIGNAL_HANDLER to EntryPointKind.SIGNAL,
        DYNAMIC_QUERY_HANDLER to EntryPointKind.QUERY,
        DYNAMIC_UPDATE_HANDLER to EntryPointKind.UPDATE,
    )

    const val NEXUS_SERVICE = "io.nexusrpc.Service"
    const val NEXUS_OPERATION = "io.nexusrpc.Operation"

    const val DURATION = "java.time.Duration"

    /** Types every class has; their functions are never those of a stub. */
    val ROOT_TYPES = setOf("kotlin.Any", "java.lang.Object")

    /** Functions of the standard library that run their lambda once, there and then. */
    val SCOPE_FUNCTIONS = setOf("kotlin.run", "kotlin.let", "kotlin.also", "kotlin.apply", "kotlin.with")

    /** Scope functions inside whose lambda `this` is the object given to them. */
    val RECEIVER_SCOPE_FUNCTIONS = setOf("kotlin.run", "kotlin.apply", "kotlin.with")

    /** Functions of `Async` that start what they are given and return a promise for it. */
    val ASYNC_STARTERS = setOf("function", "procedure")

    /** The function of `Async` that starts what it is given and starts it again for as long as it fails. */
    const val ASYNC_RETRY = "retry"

    /** Functions of `Promise` that wait for the promise they are called on. */
    val PROMISE_WAITS = setOf("get", "cancellableGet")

    /** Functions of `Promise` that wait for promises, or make a promise that does. */
    val PROMISE_JOINS = PROMISE_WAITS + setOf("allOf", "anyOf")

    /** Functions of `Promise` that hand over what is to run once the promise is done. */
    val PROMISE_CHAINS: Map<String, Chain> = mapOf(
        "thenApply" to Chain.THEN,
        "thenCompose" to Chain.COMPOSE,
        "handle" to Chain.HANDLE,
        "exceptionally" to Chain.EXCEPTIONALLY,
    )

    /** The types a promise is called on by: one the workflow completes itself is of the second. */
    val PROMISES = setOf(PROMISE, COMPLETABLE_PROMISE)

    /** Functions of a queue that wait until it holds something. */
    val QUEUE_TAKES = setOf("take", "cancellableTake")

    /** Functions of a queue that wait until it holds something, if they are given a time to wait for at most. */
    val QUEUE_POLLS = setOf("poll", "cancellablePoll")

    /** The types either end of a workflow queue is called on by. */
    val QUEUE_ENDS = setOf(QUEUE_CONSUMER, QUEUE_PRODUCER, WORKFLOW_QUEUE)

    /** Functions of a queue that wait until it has room for what they put in. */
    val QUEUE_PUTS = setOf("put", "cancellablePut")

    /** Functions of a queue that wait until it has room, if they are given a time to wait for at most. */
    val QUEUE_OFFERS = setOf("offer", "cancellableOffer")

    /** The functions of `Workflow` that make a workflow queue, which are given how much it holds. */
    val QUEUE_MAKERS = setOf("$WORKFLOW.newWorkflowQueue", "$WORKFLOW.newQueue")

    /** The function of `Async` that gives a promise for a condition to hold, without waiting for it. */
    const val ASYNC_AWAIT = "await"

    /** The function of a stub without an interface that starts what it names and gives a promise for it. */
    const val EXECUTE_ASYNC = "executeAsync"

    /** The function of `Workflow` that starts a timer and gives a promise for it. */
    const val NEW_TIMER = "newTimer"

    /** The function of `Promise` that makes a promise of the first of several to be done. */
    const val ANY_OF = "$PROMISE.anyOf"

    /** The failure a wait raises that was given a time to wait for at most, when that time is up. */
    const val TIMEOUT_EXCEPTION = "java.util.concurrent.TimeoutException"

    /** The types that keep code out until whoever is in has left: a lock, and a semaphore for several at once. */
    val GUARDS = setOf(WORKFLOW_LOCK, WORKFLOW_SEMAPHORE)

    /** Functions of a lock or a semaphore that wait until it is free. */
    val GUARD_WAITS = setOf("lock", "acquire")

    /** Functions of a lock or a semaphore that wait until it is free, if they are given a time to wait for at most. */
    val GUARD_TRIES = setOf("tryLock", "tryAcquire")

    /** The types whose objects the workflow waits on and its handlers feed or free: they are state like any other property. */
    val WAITED_ON = setOf(PROMISE, COMPLETABLE_PROMISE, QUEUE_CONSUMER, QUEUE_PRODUCER, WORKFLOW_QUEUE, WORKFLOW_LOCK, WORKFLOW_SEMAPHORE)

    const val NEW_PROMISE = "$WORKFLOW.newPromise"

    /** The functions of `Workflow` that make what a workflow waits on and its handlers feed or free. */
    val STATE_MAKERS = QUEUE_MAKERS + setOf(NEW_PROMISE, "$WORKFLOW.newWorkflowLock", "$WORKFLOW.newWorkflowSemaphore")
    const val GET_WORKFLOW_EXECUTION = "$WORKFLOW.getWorkflowExecution"
    const val CHILD_EXECUTION = "$CHILD_WORKFLOW_STUB.getExecution"
    const val EXTERNAL_FROM_TYPED = "$EXTERNAL_WORKFLOW_STUB.fromTyped"
    const val CURRENT_SCOPE = "$CANCELLATION_SCOPE.current"

    /** The functions of `Workflow` that make a cancellation scope. */
    val SCOPE_MAKERS = setOf("$WORKFLOW.newCancellationScope", "$WORKFLOW.newDetachedCancellationScope")

    const val PARENT_CLOSE_POLICY = "io.temporal.api.enums.v1.ParentClosePolicy"
    const val TIME_UNIT = "java.util.concurrent.TimeUnit"

    /** Packages of the standard library whose functions run a lambda for each element of something. */
    val ITERATION_PACKAGES = listOf("kotlin.collections.", "kotlin.sequences.")

    /** Functions of those packages that run their lambda once at most, or only when asked later. */
    val NOT_ITERATIONS = setOf(
        "getOrPut", "getOrElse", "ifEmpty", "elementAtOrElse", "withDefault",
        "buildList", "buildSet", "buildMap", "sequence", "iterator", "generateSequence", "Sequence", "Iterable",
    )

    /** Types whose own `forEach` runs a lambda for each element. */
    val ITERATED_TYPES = setOf(
        "java.lang.Iterable", "java.util.Map", "java.util.Iterator", "java.util.stream.Stream",
        "kotlin.collections.Iterable", "kotlin.collections.Map",
    )

    const val REPEAT = "kotlin.repeat"

    /** What the types of functions are called, before their number of parameters: `kotlin.Function1`. */
    const val FUNCTION_TYPE = "kotlin.Function"

    /**
     * The functions that hand an exception on for it to be thrown: a checked one wrapped, any
     * other as it is. The SDK takes the wrapping off again, so what leaves the workflow is what
     * they were handed.
     */
    val WRAPPERS = setOf(
        "$WORKFLOW.wrap",
        "io.temporal.internal.sync.WorkflowInternal.wrap",
        "io.temporal.serviceclient.CheckedExceptionWrapper.wrap",
    )

    /** The functions of the standard library that never return, each with the class of what it throws. */
    private val NEVER_RETURNING = mapOf(
        "kotlin.error" to "java.lang.IllegalStateException",
        "kotlin.TODO" to "kotlin.NotImplementedError",
    )

    /**
     * The class of what [call], a call that never returns, throws; `null` if it is not known:
     * of a function of the sources, which says so itself, or of a library that does not.
     */
    fun thrownBy(call: Call): String? = NEVER_RETURNING[call.callee?.function?.name]

    /** Static factories of `ApplicationFailure` whose second argument is the failure type. */
    val APPLICATION_FAILURE_FACTORIES = setOf(
        "newFailure", "newNonRetryableFailure", "newFailureWithCause", "newNonRetryableFailureWithCause",
        "newFailureWithCauseAndDelay",
    )
}
