package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.history.HandlerCalled
import io.github.iw.durableatlas.overlay.history.HistoryFormatException
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.history.Markers
import io.github.iw.durableatlas.overlay.history.Occurrences
import io.github.iw.durableatlas.overlay.history.Outcome
import io.github.iw.durableatlas.overlay.history.StepEnded
import io.github.iw.durableatlas.overlay.history.StepKind
import io.github.iw.durableatlas.overlay.history.StepNoted
import io.github.iw.durableatlas.overlay.history.StepTaken
import io.github.iw.durableatlas.overlay.history.WorkflowClosed
import io.github.iw.durableatlas.overlay.history.WorkflowStarted
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Histories
import io.temporal.api.enums.v1.EventType
import io.temporal.internal.history.LocalActivityMarkerUtils
import io.temporal.internal.history.VersionMarkerUtils
import java.nio.file.Files
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryJsonTest {
    private fun text(name: String): String = Files.readString(Fixtures.sampleHistories.resolve("$name.json"))

    /** A history as the Temporal UI spells event types: `ActivityTaskScheduled` for `EVENT_TYPE_ACTIVITY_TASK_SCHEDULED`. */
    private fun asTheUiSpellsIt(text: String): String =
        text.replace(Regex("\"eventType\": \"EVENT_TYPE_([A-Z_]+)\"")) { match ->
            val name = match.groupValues[1].split('_').joinToString("") { it.lowercase().replaceFirstChar(Char::uppercaseChar) }
            "\"eventType\": \"$name\""
        }

    @Test
    fun `a history is read as the steps the workflow took`() {
        val history = HistoryJson.read(Fixtures.sampleHistories.resolve("OrderWorkflow.json"))

        assertEquals("OrderWorkflow.json", history.source)
        assertEquals("OrderWorkflow", history.workflowType)
        assertEquals(79, history.events)
        // The histories of the sample repository come from a test server, which does not record the workflow id.
        assertNull(history.workflowId)
        assertTrue(Regex("[0-9a-f-]{36}").matches(history.runId.orEmpty()), history.runId)

        val occurrences = history.occurrences
        assertEquals(WorkflowStarted(1, "WorkflowExecutionStarted"), occurrences.first())
        assertEquals(WorkflowClosed(79, "WorkflowExecutionCompleted", CloseStatus.COMPLETED), occurrences.last())
        // Of the 79 events, 42 are workflow task events.
        assertEquals(79 - 42, occurrences.size)
        // 8 say that an activity started and 1 that the child did: notes on the steps they belong to.
        assertEquals(8 + 1, occurrences.filterIsInstance<StepNoted>().size)
        assertEquals(
            listOf(
                StepTaken(5, "MarkerRecorded", StepKind.LOCAL_ACTIVITY, name = "CheckAvailability", outcome = Outcome.COMPLETED),
                StepTaken(6, "ActivityTaskScheduled", StepKind.ACTIVITY, name = "ReserveStock", waits = true),
                StepNoted(10, "ActivityTaskStarted", step = 6),
                StepEnded(11, "ActivityTaskCompleted", step = 6, outcome = Outcome.COMPLETED),
            ),
            occurrences.subList(1, 5),
        )
        assertEquals(
            listOf(
                StepTaken(28, "TimerStarted", StepKind.TIMER, duration = Duration.ofMinutes(30), waits = true),
                HandlerCalled(32, "WorkflowExecutionUpdateAccepted", EntryPointKind.UPDATE, "change-address"),
            ),
            occurrences.filter { it.eventId in 28..32 },
        )
        assertEquals(
            listOf(
                HandlerCalled(34, "WorkflowExecutionSignaled", EntryPointKind.SIGNAL, "payment-received"),
                StepEnded(38, "TimerCanceled", step = 28, outcome = Outcome.CANCELED),
            ),
            occurrences.filter { it.eventId in 34..38 },
        )
        assertEquals(
            StepTaken(51, "MarkerRecorded", StepKind.VERSION, name = "order-audit", version = 1, outcome = Outcome.COMPLETED),
            occurrences.single { it.eventId == 51L },
        )
        assertEquals(
            StepTaken(70, "StartChildWorkflowExecutionInitiated", StepKind.CHILD_WORKFLOW, name = "Shipment", waits = true),
            occurrences.single { it.eventId == 70L },
        )
    }

    @Test
    fun `every kind of marker and step of the sample histories is recognised`() {
        fun steps(name: String) = HistoryJson.decode(text(name), name).occurrences.filterIsInstance<StepTaken>()

        assertEquals(
            listOf(
                StepTaken(5, "MarkerRecorded", StepKind.SIDE_EFFECT, outcome = Outcome.COMPLETED),
                StepTaken(6, "MarkerRecorded", StepKind.MUTABLE_SIDE_EFFECT, name = "batch-size", outcome = Outcome.COMPLETED),
            ),
            steps("SideEffectWorkflow"),
        )
        assertEquals(
            listOf("GreetingService" to "greet", "GreetingService" to "say-goodbye"),
            steps("NexusOperationWorkflow").map { it.service to it.name }.also { assertEquals(2, it.size) },
        )
        assertEquals(listOf(StepKind.SIGNAL_EXTERNAL to "approve"), steps("SignalExternalWorkflow").map { it.kind to it.name })
        assertEquals(listOf("PT30S", "PT1S", "PT5M"), steps("TimerWorkflow").map { it.duration.toString() })

        val failed = HistoryJson.decode(text("SagaWorkflow"), "SagaWorkflow").occurrences
        assertEquals(StepEnded(13, "ActivityTaskFailed", step = 11, outcome = Outcome.FAILED), failed.single { it.eventId == 13L })
        // The workflow rethrew the failure of the activity.
        assertEquals(WorkflowClosed(23, "WorkflowExecutionFailed", CloseStatus.FAILED, setOf("ActivityFailure")), failed.last())
        val thrown = HistoryJson.decode(text("ApplicationFailureWorkflow"), "ApplicationFailureWorkflow").occurrences.last()
        assertEquals(setOf("InvalidAmount", "ApplicationFailure"), (thrown as WorkflowClosed).failure)
    }

    @Test
    fun `both spellings of event types are read alike`() {
        for (name in listOf("OrderWorkflow", "ApprovalWorkflow", "SideEffectWorkflow", "NexusOperationWorkflow", "SagaWorkflow")) {
            val written = text(name)
            val spelled = asTheUiSpellsIt(written)
            assertContains(written, "\"eventType\": \"EVENT_TYPE_WORKFLOW_EXECUTION_STARTED\"")
            assertContains(spelled, "\"eventType\": \"WorkflowExecutionStarted\"")
            assertTrue("EVENT_TYPE_" !in spelled)

            val expected = HistoryJson.decode(written, name)
            val actual = HistoryJson.decode(spelled, name)
            assertEquals(expected.occurrences, actual.occurrences, name)
            assertEquals(expected.events, actual.events, name)
            assertEquals(expected.runId, actual.runId, name)
        }
    }

    @Test
    fun `the workflow id is taken from the first event where it is recorded`() {
        val history = HistoryJson.of(Histories("W").apply { completed() }.build(), "w.json")

        assertEquals("w-1", history.workflowId)
        assertEquals("run-1", history.runId)
        assertEquals("W", history.workflowType)
    }

    @Test
    fun `text that is no history is refused, with the name of its source`() {
        for (text in listOf("", "not json", "[]", "{}", "{\"events\": []}", "{\"events\": [{\"eventId\": \"x\"}]}")) {
            val error = assertFailsWith<HistoryFormatException>(text) { HistoryJson.decode(text, "broken.json") }
            assertTrue(error.message.orEmpty().startsWith("broken.json is not a workflow event history"), error.message)
        }
        // A history has to begin where the workflow does.
        val tail = text("TimerWorkflow").replace("EVENT_TYPE_WORKFLOW_EXECUTION_STARTED", "EVENT_TYPE_WORKFLOW_EXECUTION_SIGNALED")
        assertFailsWith<HistoryFormatException> { HistoryJson.decode(tail, "tail.json") }
    }

    @Test
    fun `event types are named as the UI names them`() {
        assertEquals("ActivityTaskScheduled", Occurrences.typeName(EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED))
        assertEquals("WorkflowExecutionContinuedAsNew", Occurrences.typeName(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_CONTINUED_AS_NEW))
        assertEquals("StartChildWorkflowExecutionInitiated", Occurrences.typeName(EventType.EVENT_TYPE_START_CHILD_WORKFLOW_EXECUTION_INITIATED))
    }

    @Test
    fun `the marker names and details are those of the SDK`() {
        // The side effect markers are named by classes of the SDK that are not public.
        fun constant(type: String, field: String): Any? =
            Class.forName(type).getDeclaredField(field).apply { isAccessible = true }.get(null)

        val sideEffect = "io.temporal.internal.statemachines.SideEffectStateMachine"
        val mutable = "io.temporal.internal.statemachines.MutableSideEffectStateMachine"
        assertEquals(Markers.SIDE_EFFECT, constant(sideEffect, "SIDE_EFFECT_MARKER_NAME"))
        assertEquals(Markers.MUTABLE_SIDE_EFFECT, constant(mutable, "MUTABLE_SIDE_EFFECT_MARKER_NAME"))
        assertEquals(Markers.MUTABLE_SIDE_EFFECT_ID, constant(mutable, "MARKER_ID_KEY"))

        // What the notes on the format say about the two markers the SDK reads for us.
        assertEquals("LocalActivity", LocalActivityMarkerUtils.MARKER_NAME)
        assertEquals("type", LocalActivityMarkerUtils.MARKER_ACTIVITY_TYPE_KEY)
        assertEquals("Version", VersionMarkerUtils.MARKER_NAME)
        assertEquals("changeId", VersionMarkerUtils.MARKER_CHANGE_ID_KEY)
        assertEquals("version", VersionMarkerUtils.MARKER_VERSION_KEY)
    }

    @Test
    fun `a marker whose details are not what the SDK writes still names its kind`() {
        val history = Histories("W").apply {
            otherMarker("LocalActivity")
            otherMarker("Version")
            otherMarker("MutableSideEffect")
        }.build()

        val steps = HistoryJson.of(history, "w.json").occurrences.filterIsInstance<StepTaken>()
        assertEquals(listOf(StepKind.LOCAL_ACTIVITY, StepKind.VERSION, StepKind.MUTABLE_SIDE_EFFECT), steps.map { it.kind })
        assertEquals(listOf(null, null, null), steps.map { it.name })
    }
}
