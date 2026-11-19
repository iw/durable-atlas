package io.github.iw.durableatlas.overlay.history

import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.History
import io.temporal.common.WorkflowExecutionHistory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Reads event histories from JSON: the form `temporal workflow show --output json` prints and the
 * Temporal UI downloads, an object whose `events` are the history. Event types may be spelled as
 * the server's API does (`EVENT_TYPE_ACTIVITY_TASK_SCHEDULED`) or as the UI does
 * (`ActivityTaskScheduled`).
 *
 * The text is parsed by the Temporal SDK, into the types its client returns for a history
 * fetched from a server.
 */
object HistoryJson {
    /** @throws HistoryFormatException if the file does not hold a history */
    fun read(file: Path): ExecutionHistory = decode(Files.readAllBytes(file).toString(Charsets.UTF_8), file.name)

    /**
     * @param source what to call the history in reports
     * @throws HistoryFormatException if [text] is not a history
     */
    fun decode(text: String, source: String): ExecutionHistory {
        val history = try {
            WorkflowExecutionHistory.fromJson(text).history
        } catch (e: RuntimeException) {
            // The SDK reports text it cannot parse and histories it finds incomplete in its own ways.
            throw HistoryFormatException("$source is not a workflow event history: ${e.message ?: e.javaClass.simpleName}", e)
        }
        return of(history, source)
    }

    /**
     * The text [decode] reads back: the history as the Temporal SDK writes it, which is the form
     * `temporal workflow show --output json` prints.
     */
    internal fun encode(history: History): String =
        WorkflowExecutionHistory(history).toJson(true).replace("\r\n", "\n").trimEnd() + "\n"

    /** @throws HistoryFormatException if the history does not begin with the start of a workflow */
    internal fun of(history: History, source: String): ExecutionHistory {
        val first = history.eventsList.firstOrNull()
        if (first == null || first.eventType != EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED) {
            throw HistoryFormatException("$source is not a workflow event history: it does not begin with the start of a workflow")
        }
        val started = first.workflowExecutionStartedEventAttributes
        val type = started.workflowType.name
        if (type.isEmpty()) throw HistoryFormatException("$source is not a workflow event history: its first event names no workflow type")
        val versions = WorkerVersions.of(history)
        return ExecutionHistory(
            source = source,
            workflowType = type,
            workflowId = started.workflowId.ifEmpty { null },
            runId = started.originalExecutionRunId.ifEmpty { started.firstExecutionRunId }.ifEmpty { null },
            events = history.eventsCount,
            occurrences = Occurrences.of(history),
            workerVersions = versions.all,
            workerVersion = versions.deciding,
        )
    }
}
