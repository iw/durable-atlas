package io.github.iw.durableatlas.overlay.testing

import com.google.protobuf.Duration
import io.temporal.api.common.v1.ActivityType
import io.temporal.api.common.v1.Payload
import io.temporal.api.common.v1.Payloads
import io.temporal.api.common.v1.WorkflowType
import io.temporal.api.enums.v1.EventType
import io.temporal.api.failure.v1.ApplicationFailureInfo
import io.temporal.api.failure.v1.Failure
import io.temporal.api.history.v1.History
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.api.history.v1.WorkflowExecutionStartedEventAttributes
import io.temporal.api.update.v1.Input
import io.temporal.api.update.v1.Request
import io.temporal.common.converter.DefaultDataConverter
import io.temporal.internal.history.VersionMarkerUtils

/**
 * Builds event histories the way a server writes them, without one: each function adds an event
 * and returns its id, which the event that ends the step refers to. The first event, the start
 * of the workflow, is always there.
 */
class Histories(workflowType: String = "W") {
    private val events = ArrayList<HistoryEvent>()

    init {
        add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED) {
            workflowExecutionStartedEventAttributesBuilder
                .setWorkflowType(WorkflowType.newBuilder().setName(workflowType))
                .setWorkflowId("w-1")
                .setOriginalExecutionRunId("run-1")
        }
    }

    fun build(): History = History.newBuilder().addAllEvents(events).build()

    private fun add(type: EventType, attributes: HistoryEvent.Builder.() -> Unit): Long {
        val id = events.size + 1L
        events += HistoryEvent.newBuilder().setEventId(id).setEventType(type).apply(attributes).build()
        return id
    }

    private fun payloads(value: Any): Payloads = DefaultDataConverter.STANDARD_INSTANCE.toPayloads(value).get()

    private fun marker(name: String, details: Map<String, Any>, failed: Boolean = false): Long =
        add(EventType.EVENT_TYPE_MARKER_RECORDED) {
            val attributes = markerRecordedEventAttributesBuilder.setMarkerName(name)
            details.forEach { (key, value) -> attributes.putDetails(key, payloads(value)) }
            if (failed) attributes.setFailure(Failure.newBuilder().setMessage("failed"))
        }

    // --- steps ---

    fun activity(name: String): Long =
        add(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED) {
            activityTaskScheduledEventAttributesBuilder.setActivityType(ActivityType.newBuilder().setName(name))
        }

    fun activityCompleted(scheduled: Long): Long =
        add(EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) { activityTaskCompletedEventAttributesBuilder.setScheduledEventId(scheduled) }

    /** A worker has picked the activity up. */
    fun activityStarted(scheduled: Long): Long =
        add(EventType.EVENT_TYPE_ACTIVITY_TASK_STARTED) { activityTaskStartedEventAttributesBuilder.setScheduledEventId(scheduled) }

    fun activityFailed(scheduled: Long): Long =
        add(EventType.EVENT_TYPE_ACTIVITY_TASK_FAILED) { activityTaskFailedEventAttributesBuilder.setScheduledEventId(scheduled) }

    /** An activity that is scheduled and completes before anything else happens. */
    fun ran(name: String): Long = activity(name).also { activityCompleted(it) }

    fun localActivity(name: String, failed: Boolean = false): Long =
        marker("LocalActivity", mapOf("type" to name, "activityId" to "1"), failed)

    fun version(changeId: String, version: Int): Long =
        add(EventType.EVENT_TYPE_MARKER_RECORDED) {
            val command = VersionMarkerUtils.createMarkerAttributes(changeId, version, false)
            markerRecordedEventAttributesBuilder.setMarkerName(command.markerName).putAllDetails(command.detailsMap)
        }

    fun sideEffect(): Long = marker("SideEffect", mapOf("data" to "x"))

    fun mutableSideEffect(id: String): Long = marker("MutableSideEffect", mapOf("id" to id, "data" to 1, "skipCount" to 0))

    fun otherMarker(name: String): Long = marker(name, emptyMap())

    fun timer(seconds: Long): Long =
        add(EventType.EVENT_TYPE_TIMER_STARTED) {
            timerStartedEventAttributesBuilder.setTimerId("t").setStartToFireTimeout(Duration.newBuilder().setSeconds(seconds))
        }

    fun timerFired(started: Long): Long =
        add(EventType.EVENT_TYPE_TIMER_FIRED) { timerFiredEventAttributesBuilder.setStartedEventId(started) }

    fun timerCanceled(started: Long): Long =
        add(EventType.EVENT_TYPE_TIMER_CANCELED) { timerCanceledEventAttributesBuilder.setStartedEventId(started) }

    fun child(workflowType: String): Long =
        add(EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED) {
            startChildWorkflowExecutionInitiatedEventAttributesBuilder.setWorkflowType(WorkflowType.newBuilder().setName(workflowType))
        }

    /** A child workflow that is started under the id [workflowId], by which a request to cancel it names it. */
    fun child(workflowType: String, workflowId: String): Long =
        add(EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED) {
            startChildWorkflowExecutionInitiatedEventAttributesBuilder
                .setWorkflowType(WorkflowType.newBuilder().setName(workflowType))
                .setWorkflowId(workflowId)
        }

    fun childStarted(initiated: Long): Long =
        add(EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_STARTED) {
            childWorkflowExecutionStartedEventAttributesBuilder.setInitiatedEventId(initiated)
        }

    fun childFailed(initiated: Long): Long =
        add(EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_FAILED) {
            childWorkflowExecutionFailedEventAttributesBuilder.setInitiatedEventId(initiated)
        }

    fun childCanceled(initiated: Long): Long =
        add(EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_CANCELED) {
            childWorkflowExecutionCanceledEventAttributesBuilder.setInitiatedEventId(initiated)
        }

    // --- cancellation ---

    /** The workflow asks for an activity to be cancelled: the scope it runs in was cancelled. */
    fun activityCancelRequested(scheduled: Long): Long =
        add(EventType.EVENT_TYPE_ACTIVITY_TASK_CANCEL_REQUESTED) {
            activityTaskCancelRequestedEventAttributesBuilder.setScheduledEventId(scheduled)
        }

    fun activityCanceled(scheduled: Long): Long =
        add(EventType.EVENT_TYPE_ACTIVITY_TASK_CANCELED) { activityTaskCanceledEventAttributesBuilder.setScheduledEventId(scheduled) }

    /**
     * The workflow asks for the workflow with the id [workflowId] to be cancelled.
     *
     * @param child whether that is a child of its own, cancelled with the scope it was started in
     */
    fun cancelRequested(workflowId: String, child: Boolean = false): Long =
        add(EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED) {
            val attributes = requestCancelExternalWorkflowExecutionInitiatedEventAttributesBuilder.setChildWorkflowOnly(child)
            attributes.workflowExecutionBuilder.setWorkflowId(workflowId)
        }

    fun cancelDelivered(initiated: Long): Long =
        add(EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED) {
            externalWorkflowExecutionCancelRequestedEventAttributesBuilder.setInitiatedEventId(initiated)
        }

    fun cancelFailed(initiated: Long): Long =
        add(EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED) {
            requestCancelExternalWorkflowExecutionFailedEventAttributesBuilder.setInitiatedEventId(initiated)
        }

    fun childCompleted(initiated: Long): Long =
        add(EventType.EVENT_TYPE_CHILD_WORKFLOW_EXECUTION_COMPLETED) {
            childWorkflowExecutionCompletedEventAttributesBuilder.setInitiatedEventId(initiated)
        }

    // --- what the workflow writes about itself ---

    /** The workflow sets or removes the search attributes called [keys]. */
    fun searchAttributes(vararg keys: String): Long =
        add(EventType.EVENT_TYPE_UPSERT_WORKFLOW_SEARCH_ATTRIBUTES) {
            val attributes = upsertWorkflowSearchAttributesEventAttributesBuilder.searchAttributesBuilder
            keys.forEach { attributes.putIndexedFields(it, Payload.getDefaultInstance()) }
        }

    /** The workflow sets or removes the entries of its memo called [keys]. */
    fun memo(vararg keys: String): Long =
        add(EventType.EVENT_TYPE_WORKFLOW_PROPERTIES_MODIFIED) {
            val attributes = workflowPropertiesModifiedEventAttributesBuilder.upsertedMemoBuilder
            keys.forEach { attributes.putFields(it, Payload.getDefaultInstance()) }
        }

    // --- Nexus operations ---

    fun nexus(service: String, operation: String): Long =
        add(EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED) {
            nexusOperationScheduledEventAttributesBuilder.setService(service).setOperation(operation)
        }

    /** The handler has taken the operation up, and it runs on. */
    fun nexusStarted(scheduled: Long): Long =
        add(EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED) { nexusOperationStartedEventAttributesBuilder.setScheduledEventId(scheduled) }

    fun nexusCompleted(scheduled: Long): Long =
        add(EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED) { nexusOperationCompletedEventAttributesBuilder.setScheduledEventId(scheduled) }

    fun nexusCanceled(scheduled: Long): Long =
        add(EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED) { nexusOperationCanceledEventAttributesBuilder.setScheduledEventId(scheduled) }

    /** The workflow asks for the operation to be cancelled: the scope it runs in was cancelled. */
    fun nexusCancelRequested(scheduled: Long): Long =
        add(EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED) {
            nexusOperationCancelRequestedEventAttributesBuilder.setScheduledEventId(scheduled)
        }

    /** The request with the event [requested] reached the handler of the operation. */
    fun nexusCancelDelivered(requested: Long, scheduled: Long): Long =
        add(EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_COMPLETED) {
            nexusOperationCancelRequestCompletedEventAttributesBuilder.setRequestedEventId(requested).setScheduledEventId(scheduled)
        }

    fun nexusCancelFailed(requested: Long, scheduled: Long): Long =
        add(EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUEST_FAILED) {
            nexusOperationCancelRequestFailedEventAttributesBuilder.setRequestedEventId(requested).setScheduledEventId(scheduled)
        }

    // --- from outside ---

    /** Someone asks the workflow to cancel. */
    fun cancelRequestedFromOutside(): Long =
        add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCEL_REQUESTED) {
            workflowExecutionCancelRequestedEventAttributesBuilder.setIdentity("operator")
        }

    /** An event of [type] that holds nothing but its type. */
    fun bare(type: EventType): Long = add(type) {}

    // --- retries ---

    /**
     * What the SDK writes where a retry begins: the options of the retry, as a mutable side
     * effect under an id it makes up.
     */
    fun retryBegins(number: Int = 1): Long = mutableSideEffect("0f8fad5b-d9cb-469f-a165-70867728950%d".format(number % 10))

    /** The pause between two attempts of a retry, which ends before the next attempt begins. */
    fun backOff(seconds: Long = 1): Long = timer(seconds).also { timerFired(it) }

    // --- handlers ---

    fun signal(name: String): Long =
        add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED) { workflowExecutionSignaledEventAttributesBuilder.setSignalName(name) }

    fun updateAccepted(name: String): Long =
        add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED) {
            workflowExecutionUpdateAcceptedEventAttributesBuilder
                .setAcceptedRequest(Request.newBuilder().setInput(Input.newBuilder().setName(name)))
        }

    fun updateCompleted(accepted: Long): Long =
        add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED) {
            workflowExecutionUpdateCompletedEventAttributesBuilder.setAcceptedEventId(accepted)
        }

    // --- ends ---

    fun completed(): Long = add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_COMPLETED) { workflowExecutionCompletedEventAttributesBuilder }

    /** @param type the type of the application failure the workflow threw; `null` for an activity failure that escaped */
    fun failed(type: String? = null): Long =
        add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_FAILED) {
            val failure = Failure.newBuilder().setMessage("failed")
            if (type != null) {
                failure.setApplicationFailureInfo(ApplicationFailureInfo.newBuilder().setType(type))
            } else {
                failure.activityFailureInfoBuilder.setActivityType(ActivityType.newBuilder().setName("A"))
            }
            workflowExecutionFailedEventAttributesBuilder.setFailure(failure)
        }

    fun terminated(): Long = add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TERMINATED) { workflowExecutionTerminatedEventAttributesBuilder }

    fun timedOut(): Long = add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_TIMED_OUT) { workflowExecutionTimedOutEventAttributesBuilder }

    fun canceled(): Long = add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CANCELED) { workflowExecutionCanceledEventAttributesBuilder }

    fun continuedAsNew(): Long =
        add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW) { workflowExecutionContinuedAsNewEventAttributesBuilder }

    /** An event that says nothing about the path of the workflow. */
    fun workflowTask(): Long = add(EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) { workflowTaskCompletedEventAttributesBuilder }

    /** A workflow task completed by a worker of a Worker Deployment Version, as a 1.32 server records it. */
    fun workflowTask(deployment: String, buildId: String): Long =
        add(EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
            val attributes = workflowTaskCompletedEventAttributesBuilder.setWorkerDeploymentName(deployment)
            attributes.deploymentVersionBuilder.setDeploymentName(deployment).setBuildId(buildId)
        }

    /** Changes the first event, the start of the workflow. */
    fun started(change: WorkflowExecutionStartedEventAttributes.Builder.() -> Unit): Histories {
        events[0] = events[0].toBuilder().apply { workflowExecutionStartedEventAttributesBuilder.change() }.build()
        return this
    }

    /** Changes the event with the id [id]. */
    fun change(id: Long, change: HistoryEvent.Builder.() -> Unit): Histories {
        events[(id - 1).toInt()] = events[(id - 1).toInt()].toBuilder().apply(change).build()
        return this
    }
}
