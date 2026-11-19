package io.github.iw.durableatlas.overlay.history

import io.github.iw.durableatlas.ir.EntryPointKind
import java.time.Duration

/** Thrown when text is not an event history this tool can read. */
class HistoryFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * How an execution ended, as its last event says.
 *
 * @param text the status as data files write it
 * @param words the status as pages for people write it
 */
enum class CloseStatus(val text: String, val words: String) {
    COMPLETED("completed", "completed"),
    FAILED("failed", "failed"),
    TIMED_OUT("timedOut", "timed out"),
    CANCELED("canceled", "canceled"),
    TERMINATED("terminated", "terminated"),
    CONTINUED_AS_NEW("continuedAsNew", "continued as new"),

    /** The history ends before the execution does. */
    RUNNING("running", "still running"),
}

/**
 * The event history of one workflow execution, reduced to what an overlay needs: the steps the
 * workflow took, the handlers that were called and how the execution ended, in the order of the
 * events.
 *
 * @param source where the history came from, for reports: a file name
 * @param workflowId the id of the execution, if the history says it. Histories written by the
 *   `temporal` command line hold it only when the server recorded it in the first event
 * @param runId the id of the run the history belongs to, if the history says it
 * @param events the number of events in the history, of every type
 * @param workerVersions the versions of the workers that completed workflow tasks of the
 *   execution, in the order they first did; empty if no worker reported one
 * @param workerVersion the version whose code the history is read against: that of the worker
 *   that completed the last workflow task, which replayed everything before it. `null` if that
 *   worker reported none
 */
class ExecutionHistory internal constructor(
    val source: String,
    val workflowType: String,
    val workflowId: String?,
    val runId: String?,
    val events: Int,
    internal val occurrences: List<Occurrence>,
    val workerVersions: List<WorkerVersion> = emptyList(),
    val workerVersion: WorkerVersion? = null,
) {
    /** How the execution ended; [CloseStatus.RUNNING] if the history ends before it does. */
    val status: CloseStatus
        get() = occurrences.filterIsInstance<WorkflowClosed>().lastOrNull()?.status ?: CloseStatus.RUNNING
}

/** What an event means to the overlay. Events that mean nothing to it have no occurrence. */
internal sealed interface Occurrence {
    val eventId: Long

    /** The type of the event as the Temporal UI spells it, for example `ActivityTaskScheduled`. */
    val eventType: String
}

/** The kinds of step a workflow leaves an event for. */
internal enum class StepKind {
    ACTIVITY,
    LOCAL_ACTIVITY,
    CHILD_WORKFLOW,
    SIGNAL_EXTERNAL,
    NEXUS_OPERATION,
    TIMER,
    SIDE_EFFECT,
    MUTABLE_SIDE_EFFECT,
    VERSION,

    /** Another workflow, which is no child of this one, is asked to cancel. */
    CANCEL_EXTERNAL,

    /** Search attributes of the workflow are set or removed. */
    UPSERT_SEARCH_ATTRIBUTES,

    /** Entries of the workflow's memo are set or removed. */
    UPSERT_MEMO,
}

/** How a step ended. */
internal enum class Outcome {
    /** It did what it was asked; a timer fired. */
    COMPLETED,
    FAILED,
    CANCELED,
}

internal data class WorkflowStarted(override val eventId: Long, override val eventType: String) : Occurrence

/**
 * The workflow took a step.
 *
 * @param name what the step is matched by: the activity type, the workflow type of a child, the
 *   name of a signal or of a Nexus operation, the change id of a version, the id of a mutable
 *   side effect
 * @param waits whether the code that took the step waits for a later event that ends it
 * @param outcome how the step ended, where the event says so itself
 * @param keys what an upsert writes: the names of the search attributes, or of the entries of the memo
 */
internal data class StepTaken(
    override val eventId: Long,
    override val eventType: String,
    val kind: StepKind,
    val name: String? = null,
    val service: String? = null,
    val duration: Duration? = null,
    val version: Int? = null,
    val waits: Boolean = false,
    val outcome: Outcome? = null,
    val keys: Set<String> = emptySet(),
) : Occurrence

/** The step taken with the event [step] ended. */
internal data class StepEnded(
    override val eventId: Long,
    override val eventType: String,
    val step: Long,
    val outcome: Outcome,
) : Occurrence

/**
 * The step taken with the event [step] was asked to cancel by the workflow itself: a scope it
 * ran in was cancelled. The step ends with a later event of its own. A timer has no such event:
 * it is cancelled there and then, which is a [StepEnded].
 *
 * @param noted whether the event is laid on the step itself where no node of the graph cancels
 *   it: the request is then all the history says, and it is about that step
 */
internal data class StepCancelled(
    override val eventId: Long,
    override val eventType: String,
    val step: Long,
    val noted: Boolean = false,
) : Occurrence

/**
 * The history says more about the step taken with the event [step], which goes on: a worker
 * has picked an activity up, a child workflow or a Nexus operation has started, or the request
 * to cancel a child or an operation was delivered or could not be. The event belongs to the
 * node of that step and moves nothing.
 */
internal data class StepNoted(override val eventId: Long, override val eventType: String, val step: Long) : Occurrence

/**
 * Something was done to the workflow from outside that is no step of it: it was asked to
 * cancel, paused, or given other options, or an update was turned down. No node stands for it.
 */
internal data class Outside(override val eventId: Long, override val eventType: String) : Occurrence

/** A signal arrived, or an update was accepted. */
internal data class HandlerCalled(
    override val eventId: Long,
    override val eventType: String,
    val kind: EntryPointKind,
    val name: String,
) : Occurrence

/** The handler called with the event [called] returned. Only updates say so. */
internal data class HandlerEnded(override val eventId: Long, override val eventType: String, val called: Long) : Occurrence

/**
 * The execution ended.
 *
 * @param failure the names the failure goes by, for an execution that failed: the type of an
 *   application failure and the class of exception the SDK raises for it
 */
internal data class WorkflowClosed(
    override val eventId: Long,
    override val eventType: String,
    val status: CloseStatus,
    val failure: Set<String> = emptySet(),
) : Occurrence

/** A step this tool has no name for, such as a marker it does not know. */
internal data class UnknownStep(override val eventId: Long, override val eventType: String, val name: String?) : Occurrence
