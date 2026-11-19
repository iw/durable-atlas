package io.github.iw.durableatlas.overlay.history

import io.github.iw.durableatlas.ir.EntryPointKind
import io.temporal.api.enums.v1.EventType
import io.temporal.api.failure.v1.Failure
import io.temporal.api.history.v1.History
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.internal.history.VersionMarkerUtils
import java.time.Duration

/**
 * Reads the events of a history as [Occurrence]s. Workflow task events and whatever else says
 * nothing about the path of the workflow are left out; [EventRoles] says of every type of
 * event which it is.
 */
internal class Occurrences private constructor() {
    /** Names of updates by update id, from events that hold the request apart from its acceptance. */
    private val admitted = HashMap<String, String>()

    /** The event that started each child workflow, by the id of the child. */
    private val children = HashMap<String, Long>()

    /** The requests to cancel a workflow that is no child of this one: they are steps. */
    private val cancelRequests = HashSet<Long>()

    /** The requests to cancel a child workflow, each with the event that started the child. */
    private val childCancelRequests = HashMap<Long, Long>()

    private fun of(event: HistoryEvent): Occurrence? {
        val id = event.eventId
        val type = typeName(event.eventType)
        fun step(kind: StepKind, name: String?, service: String? = null) =
            StepTaken(id, type, kind, name = name, service = service, waits = true)
        fun ended(step: Long, outcome: Outcome) = StepEnded(id, type, step, outcome)
        fun closed(status: CloseStatus, failure: Set<String> = emptySet()) = WorkflowClosed(id, type, status, failure)
        fun upsert(kind: StepKind, keys: Set<String>) =
            StepTaken(id, type, kind, name = keys.sorted().joinToString(", ").ifEmpty { null }, outcome = Outcome.COMPLETED, keys = keys)

        return when (event.eventType) {
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED -> WorkflowStarted(id, type)

            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED ->
                step(StepKind.ACTIVITY, event.activityTaskScheduledEventAttributes.activityType.name)
            EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED ->
                ended(event.activityTaskCompletedEventAttributes.scheduledEventId, Outcome.COMPLETED)
            EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED ->
                ended(event.activityTaskFailedEventAttributes.scheduledEventId, Outcome.FAILED)
            EventType.EVENT_TYPE_ACTIVITY_TASK_TIMED_OUT ->
                ended(event.activityTaskTimedOutEventAttributes.scheduledEventId, Outcome.FAILED)
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED ->
                ended(event.activityTaskCanceledEventAttributes.scheduledEventId, Outcome.CANCELED)
            // The workflow cancelled the scope the activity runs in. The activity ends with an event of its own.
            EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED ->
                StepCancelled(id, type, event.activityTaskCancelRequestedEventAttributes.scheduledEventId, noted = true)
            // A worker has picked the activity up: it runs on, and ends with an event of its own.
            EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED ->
                StepNoted(id, type, event.activityTaskStartedEventAttributes.scheduledEventId)

            EventType.EVENT_TYPE_MARKER_RECORDED -> marker(event, type)

            EventType.EVENT_TYPE_TIMER_STARTED -> {
                val timeout = event.timerStartedEventAttributes.startToFireTimeout
                StepTaken(
                    id, type, StepKind.TIMER,
                    duration = Duration.ofSeconds(timeout.seconds, timeout.nanos.toLong()),
                    waits = true,
                )
            }
            EventType.EVENT_TYPE_TIMER_FIRED -> ended(event.timerFiredEventAttributes.startedEventId, Outcome.COMPLETED)
            EventType.EVENT_TYPE_TIMER_CANCELED -> ended(event.timerCanceledEventAttributes.startedEventId, Outcome.CANCELED)

            EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED -> {
                val attributes = event.startChildWorkflowExecutionInitiatedEventAttributes
                children[attributes.workflowId] = id
                step(StepKind.CHILD_WORKFLOW, attributes.workflowType.name)
            }
            EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_FAILED ->
                ended(event.startChildWorkflowExecutionFailedEventAttributes.initiatedEventId, Outcome.FAILED)
            // The child has begun to run: it runs on, and ends with an event of its own.
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED ->
                StepNoted(id, type, event.childWorkflowExecutionStartedEventAttributes.initiatedEventId)
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED ->
                ended(event.childWorkflowExecutionCompletedEventAttributes.initiatedEventId, Outcome.COMPLETED)
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED ->
                ended(event.childWorkflowExecutionFailedEventAttributes.initiatedEventId, Outcome.FAILED)
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TIMED_OUT ->
                ended(event.childWorkflowExecutionTimedOutEventAttributes.initiatedEventId, Outcome.FAILED)
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_TERMINATED ->
                ended(event.childWorkflowExecutionTerminatedEventAttributes.initiatedEventId, Outcome.FAILED)
            EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED ->
                ended(event.childWorkflowExecutionCanceledEventAttributes.initiatedEventId, Outcome.CANCELED)

            EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED ->
                step(StepKind.SIGNAL_EXTERNAL, event.signalExternalWorkflowExecutionInitiatedEventAttributes.signalName)
            EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_SIGNALED ->
                ended(event.externalWorkflowExecutionSignaledEventAttributes.initiatedEventId, Outcome.COMPLETED)
            EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED ->
                ended(event.signalExternalWorkflowExecutionFailedEventAttributes.initiatedEventId, Outcome.FAILED)

            EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED -> {
                val attributes = event.requestCancelExternalWorkflowExecutionInitiatedEventAttributes
                if (attributes.childWorkflowOnly) {
                    // A child is cancelled through the scope it was started in, like an activity.
                    children[attributes.workflowExecution.workflowId]?.let { child ->
                        childCancelRequests[id] = child
                        StepCancelled(id, type, child, noted = true)
                    }
                } else {
                    cancelRequests += id
                    step(StepKind.CANCEL_EXTERNAL, name = null)
                }
            }
            // What became of the request: of one to another workflow, the end of that step; of one to a child, a note on the child.
            EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED ->
                requested(id, type, event.externalWorkflowExecutionCancelRequestedEventAttributes.initiatedEventId, Outcome.COMPLETED)
            EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED ->
                requested(id, type, event.requestCancelExternalWorkflowExecutionFailedEventAttributes.initiatedEventId, Outcome.FAILED)

            EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED -> {
                val attributes = event.nexusOperationScheduledEventAttributes
                step(StepKind.NEXUS_OPERATION, attributes.operation, attributes.service)
            }
            EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED ->
                ended(event.nexusOperationCompletedEventAttributes.scheduledEventId, Outcome.COMPLETED)
            EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED ->
                ended(event.nexusOperationFailedEventAttributes.scheduledEventId, Outcome.FAILED)
            EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT ->
                ended(event.nexusOperationTimedOutEventAttributes.scheduledEventId, Outcome.FAILED)
            EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED ->
                ended(event.nexusOperationCanceledEventAttributes.scheduledEventId, Outcome.CANCELED)
            // The operation runs on: the handler has taken it up and will say later how it ended.
            EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED ->
                StepNoted(id, type, event.nexusOperationStartedEventAttributes.scheduledEventId)
            // The workflow cancelled the scope the operation runs in, like that of an activity.
            EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED ->
                StepCancelled(id, type, event.nexusOperationCancelRequestedEventAttributes.scheduledEventId, noted = true)
            // What became of that request. The operation ends with an event of its own.
            EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_COMPLETED ->
                StepNoted(id, type, event.nexusOperationCancelRequestCompletedEventAttributes.scheduledEventId)
            EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_FAILED ->
                StepNoted(id, type, event.nexusOperationCancelRequestFailedEventAttributes.scheduledEventId)

            EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES -> {
                val keys = event.upsertWorkflowSearchAttributesEventAttributes.searchAttributes.indexedFieldsMap.keys
                // Where it is told to, the SDK keeps the versions of a workflow in a search attribute of its own.
                // That is no step of the workflow: it belongs to the marker of the version before it.
                if (keys == setOf(VERSIONS_ATTRIBUTE)) null else upsert(StepKind.UPSERT_SEARCH_ATTRIBUTES, keys)
            }
            EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED ->
                upsert(StepKind.UPSERT_MEMO, event.workflowPropertiesModifiedEventAttributes.upsertedMemo.fieldsMap.keys)

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED ->
                HandlerCalled(id, type, EntryPointKind.SIGNAL, event.workflowExecutionSignaledEventAttributes.signalName)
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED -> {
                val request = event.workflowExecutionUpdateAdmittedEventAttributes.request
                admitted[request.meta.updateId] = request.input.name
                null
            }
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED -> {
                val attributes = event.workflowExecutionUpdateAcceptedEventAttributes
                val name = attributes.acceptedRequest.input.name.ifEmpty { admitted[attributes.protocolInstanceId].orEmpty() }
                HandlerCalled(id, type, EntryPointKind.UPDATE, name)
            }
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED ->
                HandlerEnded(id, type, event.workflowExecutionUpdateCompletedEventAttributes.acceptedEventId)

            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED -> closed(CloseStatus.COMPLETED)
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED ->
                closed(CloseStatus.FAILED, failureNames(event.workflowExecutionFailedEventAttributes.failure))
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT -> closed(CloseStatus.TIMED_OUT)
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED -> closed(CloseStatus.CANCELED, setOf(CANCELED_FAILURE))
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED -> closed(CloseStatus.TERMINATED)
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW -> closed(CloseStatus.CONTINUED_AS_NEW)

            else -> if (EventRoles.of(event.eventType) == EventRole.OUTSIDE) Outside(id, type) else null
        }
    }

    /** What became of the request to cancel another workflow that was made with the event [request]. */
    private fun requested(id: Long, type: String, request: Long, outcome: Outcome): Occurrence? =
        if (request in cancelRequests) StepEnded(id, type, request, outcome) else childCancelRequests[request]?.let { StepNoted(id, type, it) }

    private fun marker(event: HistoryEvent, type: String): Occurrence {
        val id = event.eventId
        val attributes = event.markerRecordedEventAttributes
        return when {
            Markers.isLocalActivity(event) ->
                StepTaken(
                    id, type, StepKind.LOCAL_ACTIVITY,
                    name = Markers.activityType(event),
                    outcome = if (attributes.hasFailure()) Outcome.FAILED else Outcome.COMPLETED,
                )
            Markers.isVersion(event) ->
                StepTaken(
                    id, type, StepKind.VERSION,
                    name = Markers.changeId(event),
                    version = Markers.version(event),
                    outcome = Outcome.COMPLETED,
                )
            attributes.markerName == Markers.SIDE_EFFECT ->
                StepTaken(id, type, StepKind.SIDE_EFFECT, outcome = Outcome.COMPLETED)
            attributes.markerName == Markers.MUTABLE_SIDE_EFFECT ->
                StepTaken(id, type, StepKind.MUTABLE_SIDE_EFFECT, name = Markers.mutableSideEffectId(event), outcome = Outcome.COMPLETED)
            else -> UnknownStep(id, type, attributes.markerName.ifEmpty { null })
        }
    }

    companion object {
        /** The search attribute in which the SDK keeps the versions of a workflow, where it is told to. */
        val VERSIONS_ATTRIBUTE: String = VersionMarkerUtils.TEMPORAL_CHANGE_VERSION.name

        /** Name of the exception the SDK raises in a workflow that is cancelled. */
        const val CANCELED_FAILURE: String = "CanceledFailure"

        fun of(history: History): List<Occurrence> {
            val reader = Occurrences()
            return history.eventsList.mapNotNull(reader::of)
        }

        /** `EVENT_TYPE_ACTIVITY_TASK_SCHEDULED` as `ActivityTaskScheduled`. */
        fun typeName(type: EventType): String =
            type.name.removePrefix("EVENT_TYPE_").split('_').joinToString("") { word ->
                word.lowercase().replaceFirstChar(Char::uppercaseChar)
            }

        /**
         * What a failure may be called where it is thrown: the type given to an application
         * failure, also without its package, and the exception class of the SDK that stands for
         * this kind of failure.
         */
        fun failureNames(failure: Failure): Set<String> {
            val names = LinkedHashSet<String>()
            if (failure.hasApplicationFailureInfo()) {
                val type = failure.applicationFailureInfo.type
                if (type.isNotEmpty()) {
                    names += type
                    names += type.substringAfterLast('.')
                }
            }
            when (failure.failureInfoCase) {
                Failure.FailureInfoCase.APPLICATION_FAILURE_INFO -> names += "ApplicationFailure"
                Failure.FailureInfoCase.TIMEOUT_FAILURE_INFO -> names += "TimeoutFailure"
                Failure.FailureInfoCase.CANCELED_FAILURE_INFO -> names += CANCELED_FAILURE
                Failure.FailureInfoCase.TERMINATED_FAILURE_INFO -> names += "TerminatedFailure"
                Failure.FailureInfoCase.SERVER_FAILURE_INFO -> names += "ServerFailure"
                Failure.FailureInfoCase.ACTIVITY_FAILURE_INFO -> names += "ActivityFailure"
                Failure.FailureInfoCase.CHILD_WORKFLOW_EXECUTION_FAILURE_INFO -> names += "ChildWorkflowFailure"
                Failure.FailureInfoCase.NEXUS_OPERATION_EXECUTION_FAILURE_INFO -> names += "NexusOperationFailure"
                else -> Unit
            }
            return names
        }
    }
}
