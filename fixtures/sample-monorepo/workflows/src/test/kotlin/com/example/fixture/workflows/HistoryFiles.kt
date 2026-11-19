package com.example.fixture.workflows

import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.HistoryEvent
import io.temporal.common.WorkflowExecutionHistory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Writes captured histories under `build/histories` and keeps the checked-in copies honest.
 *
 * - `-Ddurable-atlas.updateHistories=true` overwrites the checked-in copy with the fresh capture.
 * - Otherwise the fresh capture must describe the same execution as the checked-in copy.
 */
object HistoryFiles {
    private val outputDir: Path = Paths.get(System.getProperty("durable-atlas.historiesOut", "build/histories"))
    private val checkedInDir: Path = Paths.get(System.getProperty("durable-atlas.historiesCheckedIn", "../histories"))
    private val update: Boolean = System.getProperty("durable-atlas.updateHistories", "false").toBoolean()

    fun save(name: String, history: WorkflowExecutionHistory) {
        val json = history.toJson(true).replace("\r\n", "\n").trimEnd() + "\n"
        val fileName = "$name.json"

        Files.createDirectories(outputDir)
        Files.writeString(outputDir.resolve(fileName), json)

        val checkedIn = checkedInDir.resolve(fileName)
        if (update) {
            Files.createDirectories(checkedInDir)
            Files.writeString(checkedIn, json)
            return
        }
        assertTrue(Files.exists(checkedIn), "$checkedIn is missing; $REFRESH_HINT")
        val expected = WorkflowExecutionHistory.fromJson(Files.readString(checkedIn))
        assertEquals(
            signature(expected),
            signature(history),
            "$checkedIn no longer matches what the workflow does; $REFRESH_HINT",
        )
    }

    /**
     * What happened in a history, ignoring everything that varies between runs: ids, timestamps,
     * workflow task events (their number depends on timing) and the order of concurrent events.
     */
    fun signature(history: WorkflowExecutionHistory): List<String> =
        history.events
            .filterNot { it.eventType in WORKFLOW_TASK_EVENTS }
            .map(::describe)
            .sorted()

    private fun describe(event: HistoryEvent): String {
        val detail = when (event.eventType) {
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED ->
                event.workflowExecutionStartedEventAttributes.workflowType.name
            EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED ->
                event.activityTaskScheduledEventAttributes.activityType.name
            EventType.EVENT_TYPE_MARKER_RECORDED ->
                event.markerRecordedEventAttributes.markerName
            EventType.EVENT_TYPE_TIMER_STARTED ->
                event.timerStartedEventAttributes.startToFireTimeout.seconds.toString() + "s"
            EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED ->
                event.startChildWorkflowExecutionInitiatedEventAttributes.workflowType.name
            EventType.EVENT_TYPE_SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_INITIATED ->
                event.signalExternalWorkflowExecutionInitiatedEventAttributes.signalName
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED ->
                event.workflowExecutionSignaledEventAttributes.signalName
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ACCEPTED ->
                event.workflowExecutionUpdateAcceptedEventAttributes.acceptedRequest.input.name
            EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED ->
                event.nexusOperationScheduledEventAttributes.let { "${it.service}/${it.operation}" }
            else -> ""
        }
        val type = event.eventType.name.removePrefix("EVENT_TYPE_")
        return if (detail.isEmpty()) type else "$type $detail"
    }

    private val WORKFLOW_TASK_EVENTS = setOf(
        EventType.EVENT_TYPE_WORKFLOW_TASK_SCHEDULED,
        EventType.EVENT_TYPE_WORKFLOW_TASK_STARTED,
        EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED,
        EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED,
        EventType.EVENT_TYPE_WORKFLOW_TASK_TIMED_OUT,
    )

    private const val REFRESH_HINT = "refresh with ./gradlew :workflows:test -Ddurable-atlas.updateHistories=true"
}
