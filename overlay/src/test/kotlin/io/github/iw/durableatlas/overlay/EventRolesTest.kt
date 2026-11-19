package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.overlay.history.EventRole
import io.github.iw.durableatlas.overlay.history.EventRoles
import io.github.iw.durableatlas.overlay.history.HandlerCalled
import io.github.iw.durableatlas.overlay.history.HandlerEnded
import io.github.iw.durableatlas.overlay.history.Occurrence
import io.github.iw.durableatlas.overlay.history.Occurrences
import io.github.iw.durableatlas.overlay.history.Outside
import io.github.iw.durableatlas.overlay.history.StepCancelled
import io.github.iw.durableatlas.overlay.history.StepEnded
import io.github.iw.durableatlas.overlay.history.StepKind
import io.github.iw.durableatlas.overlay.history.StepNoted
import io.github.iw.durableatlas.overlay.history.StepTaken
import io.github.iw.durableatlas.overlay.history.UnknownStep
import io.github.iw.durableatlas.overlay.history.WorkflowClosed
import io.github.iw.durableatlas.overlay.history.WorkflowStarted
import io.github.iw.durableatlas.overlay.testing.Histories
import io.temporal.api.enums.v1.EventType
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every type of event a history can hold has a role, and the reading of histories keeps to it:
 * what is part of the path of the workflow is read as something, what was done to the workflow
 * from outside as that, and bookkeeping as nothing.
 */
class EventRolesTest {
    private val types = EventType.entries.filter { it != EventType.UNRECOGNIZED && it != EventType.EVENT_TYPE_UNSPECIFIED }

    /** What an event of [type] that holds nothing but its type is read as, after the start of a workflow. */
    private fun read(type: EventType): Occurrence? {
        val history = Histories().apply { bare(type) }.build()
        return Occurrences.of(history).singleOrNull { it.eventId == 2L }
    }

    @Test
    fun `every type of event the API knows has a role`() {
        assertEquals(60, types.size)
        val roles = types.groupBy(EventRoles::of).mapValues { it.value.size }
        assertEquals(mapOf(EventRole.PATH to 47, EventRole.OUTSIDE to 7, EventRole.BOOKKEEPING to 6), roles)
        assertEquals(EventRole.BOOKKEEPING, EventRoles.of(EventType.UNRECOGNIZED))
        assertEquals(EventRole.BOOKKEEPING, EventRoles.of(EventType.EVENT_TYPE_UNSPECIFIED))
    }

    @Test
    fun `bookkeeping is read as nothing, and what was done from outside as that`() {
        for (type in types) {
            when (EventRoles.of(type)) {
                EventRole.BOOKKEEPING -> assertNull(read(type), type.name)
                EventRole.OUTSIDE -> assertEquals(Outside(2, Occurrences.typeName(type)), read(type), type.name)
                EventRole.PATH -> assertTrue(read(type) !is Outside, type.name)
            }
        }
        assertEquals(
            listOf(
                "WorkflowExecutionCancelRequested", "WorkflowExecutionUpdateRejected", "WorkflowPropertiesModifiedExternally",
                "ActivityPropertiesModifiedExternally", "WorkflowExecutionOptionsUpdated", "WorkflowExecutionPaused", "WorkflowExecutionUnpaused",
            ),
            types.filter { EventRoles.of(it) == EventRole.OUTSIDE }.map(Occurrences::typeName),
        )
    }

    @Test
    fun `what is part of the path is read as a step, as what became of one, as a handler or as the start or the end`() {
        // Three of them say something only together with an event before them, which a history of one event lacks:
        // an update that was admitted is remembered for its name, and the answer to a request to cancel belongs to that request.
        val alone = setOf(
            EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_ADMITTED,
            EventType.EVENT_TYPE_REQUEST_CANCEL_EXTERNAL_WORKFLOW_EXECUTION_FAILED,
            EventType.EVENT_TYPE_EXTERNAL_WORKFLOW_EXECUTION_CANCEL_REQUESTED,
        )
        val read = LinkedHashMap<KClass<out Occurrence>, MutableList<String>>()
        for (type in types.filter { EventRoles.of(it) == EventRole.PATH }) {
            // The start of the workflow is the first event of every history.
            val occurrence = if (type == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED) Occurrences.of(Histories().build()).single() else read(type)
            if (type in alone) {
                assertNull(occurrence, type.name)
            } else {
                read.getOrPut(checkNotNull(occurrence) { type.name }::class) { ArrayList() } += Occurrences.typeName(type)
            }
        }
        assertEquals(setOf(WorkflowStarted::class, StepTaken::class, StepEnded::class, StepCancelled::class, StepNoted::class, HandlerCalled::class, HandlerEnded::class, WorkflowClosed::class, UnknownStep::class), read.keys)
        assertEquals(listOf("ActivityTaskCancelRequested", "NexusOperationCancelRequested"), read.getValue(StepCancelled::class))
        assertEquals(
            listOf("ActivityTaskStarted", "ChildWorkflowExecutionStarted", "NexusOperationStarted", "NexusOperationCancelRequestCompleted", "NexusOperationCancelRequestFailed"),
            read.getValue(StepNoted::class),
        )
        // A marker that says nothing of what it is for is a step this tool has no name for.
        assertEquals(listOf("MarkerRecorded"), read.getValue(UnknownStep::class))
        assertEquals(
            listOf(
                "ActivityTaskScheduled", "TimerStarted", "RequestCancelExternalWorkflowExecutionInitiated", "StartChildWorkflowExecutionInitiated",
                "SignalExternalWorkflowExecutionInitiated", "UpsertWorkflowSearchAttributes", "WorkflowPropertiesModified", "NexusOperationScheduled",
            ),
            read.getValue(StepTaken::class),
        )
    }

    @Test
    fun `an upsert is read as a step that names what it writes`() {
        val history = Histories().apply { searchAttributes("Stage", "Owner"); memo("note"); searchAttributes("TemporalChangeVersion") }.build()
        val steps = Occurrences.of(history).filterIsInstance<StepTaken>()
        assertEquals(listOf(StepKind.UPSERT_SEARCH_ATTRIBUTES, StepKind.UPSERT_MEMO), steps.map { it.kind })
        assertEquals(listOf(setOf("Stage", "Owner"), setOf("note")), steps.map { it.keys })
        assertEquals(listOf("Owner, Stage", "note"), steps.map { it.name })
        // Neither waits for anything: the step is done when the event is written.
        assertEquals(listOf(false, false), steps.map { it.waits })
    }
}
