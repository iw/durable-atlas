package io.github.iw.durableatlas.overlay.history

import io.temporal.api.enums.v1.EventType

/** What an event of a workflow history is to the overlay. */
internal enum class EventRole {
    /**
     * It is part of the path of the workflow: a step it took, how a step ended or what became
     * of it, a handler that was called or returned, the start or the end of the execution. It
     * is laid on a node or an entry point, or says how the walk goes on.
     */
    PATH,

    /**
     * Something that was done to the workflow from outside and is no step of it: it was asked
     * to cancel, paused, or given other options, or an update was sent that its validator
     * turned down. No node stands for it; it is reported as an event the overlay knows, not as
     * a step without a node.
     */
    OUTSIDE,

    /**
     * Bookkeeping of the server or of the SDK that says nothing about the path: workflow tasks,
     * the clock of a test server.
     */
    BOOKKEEPING,
}

/**
 * The role of every type of event a history can hold. Each type the API knows is named here, so
 * that a type it gains has to be given a role before the overlay builds again.
 */
internal object EventRoles {
    fun of(type: EventType): EventRole =
        when (type) {
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW,
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT,
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED,
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED,
            EventType.EVENT_TYPE_TIMER_STARTED,
            EventType.EVENT_TYPE_TIMER_FIRED,
            EventType.EVENT_TYPE_TIMER_CANCELED,
            EventType.EVENT_TYPE_MARKER_RECORDED,
            EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED,
            EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT,
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED,
            EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED,
            EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED,
            EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_SIGNALED,
            EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED,
            EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED,
            EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED,
            EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES,
            EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED,
            EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
            EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED,
            EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED,
            EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED,
            EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED,
            EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT,
            EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED,
            EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_COMPLETED,
            EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_FAILED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED,
            // An update that was admitted is only remembered for its name: the one that was accepted is what calls the handler.
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED,
            -> EventRole.PATH

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED,
            EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED_EXTERNALLY,
            EventType.EVENT_TYPE_ACTIVITY_PROPERTIES_MODIFIED_EXTERNALLY,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_OPTIONS_UPDATED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_PAUSED,
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UNPAUSED,
            // An update its validator turned down ran none of the handler: no step of the workflow, and no node.
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_REJECTED,
            -> EventRole.OUTSIDE

            // Workflow tasks are how the worker is driven; what it decided in them is in the events that follow.
            EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED,
            EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED,
            EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
            EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT,
            EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED,
            // The clock of a test server was moved.
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIME_SKIPPING_TRANSITIONED,
            EventType.EVENT_TYPE_UNSPECIFIED,
            EventType.UNRECOGNIZED,
            -> EventRole.BOOKKEEPING
        }
}
