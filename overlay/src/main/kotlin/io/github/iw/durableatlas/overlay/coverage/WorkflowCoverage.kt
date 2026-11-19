package io.github.iw.durableatlas.overlay.coverage

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.NodeKind
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * What the executions of one workflow type reached in its graph: hit counts for every node, edge
 * and handler, and the events that found no place or no certain one.
 *
 * Property order is the key order of the JSON form, so it must not be rearranged casually.
 *
 * @param commit the commit the graph was made from
 * @param executions the number of histories laid over the graph
 * @param neverHit ids of the nodes no execution reached, of those a history can show at all:
 *   the nodes of query handlers are left out, because queries leave no event
 * @param closes how the executions ended, and where
 * @param recognised the events that are no step of a workflow and have no node, because they
 *   say what was done to an execution from outside: a request to cancel it, and the like. Left
 *   out of the JSON form when there are none
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WorkflowCoverage(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = SCHEMA_VERSION,
    val workflowType: String,
    val commit: String,
    val executions: Int,
    val events: EventCounts,
    val entryPoints: List<EntryPointCoverage>,
    val nodes: List<NodeCoverage>,
    val edges: List<EdgeCoverage>,
    val neverHit: List<String>,
    val closes: List<CloseCoverage>,
    val ambiguous: List<AmbiguousEvent>,
    val unmapped: List<UnmappedStep>,
    val recognised: List<OutsideEvent> = emptyList(),
    val histories: List<HistoryCoverage>,
) {
    /** Nodes a history can show, and how many of them were reached. */
    val recordedNodes: Int get() = nodes.count { it.recorded }
    val hitNodes: Int get() = nodes.count { it.recorded && it.hits > 0 }

    companion object {
        /** Version of the JSON format written by this module. */
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * How many events were laid on the graph.
 *
 * @param mapped events with exactly one node
 * @param ambiguous events that fit several nodes and were counted for each
 * @param unmapped events that are a step of the workflow and have no node
 * @param recognised events that are no step of the workflow and have no node: what was done to
 *   it from outside. They are no part of [total]; zero is left out of the JSON form
 */
@Serializable
data class EventCounts(val mapped: Int, val ambiguous: Int, val unmapped: Int, val recognised: Int = 0) {
    /** The events that are steps of the workflow, with a node or without. */
    val total: Int get() = mapped + ambiguous + unmapped

    operator fun plus(other: EventCounts): EventCounts =
        EventCounts(mapped + other.mapped, ambiguous + other.ambiguous, unmapped + other.unmapped, recognised + other.recognised)
}

/**
 * @param recorded whether histories show calls of this handler. Queries leave no event
 * @param hits how often it was called
 * @param executions in how many executions it was called
 */
@Serializable
data class EntryPointCoverage(
    val id: String,
    val kind: EntryPointKind,
    val name: String,
    val recorded: Boolean = true,
    val hits: Int,
    val executions: Int,
)

/**
 * @param recorded whether histories can show this node; false for the nodes of query handlers
 * @param hits how often the node was reached, over all executions
 * @param uncertain how many of the hits are not certain, see [AmbiguousEvent]
 * @param executions how many executions reached it
 */
@Serializable
data class NodeCoverage(
    val id: String,
    val kind: NodeKind,
    val name: String? = null,
    val recorded: Boolean = true,
    val hits: Int,
    val uncertain: Int = 0,
    val executions: Int,
)

/**
 * @param implied true for an edge between a region and one of its children that the graph
 *   leaves unwritten: into the children the region begins with, and back from those it ends with
 */
@Serializable
data class EdgeCoverage(
    val from: String,
    val to: String,
    val kind: EdgeKind = EdgeKind.FLOW,
    val label: String? = null,
    val implied: Boolean = false,
    val recorded: Boolean = true,
    val hits: Int,
    val uncertain: Int = 0,
    val executions: Int,
)

/**
 * @param status `completed`, `failed`, `timedOut`, `canceled`, `terminated`, `continuedAsNew`,
 *   or `running` for a history that ends before the execution does
 * @param nodes where these executions ended; empty if the end has no node
 */
@Serializable
data class CloseCoverage(val status: String, val nodes: List<String>, val executions: Int)

/**
 * A match the history does not settle.
 *
 * @param doubt `candidates`: several nodes fit the event, and each was counted. `paths`: the
 *   event has one node, but several ways lead there, and what lies on only some of them was
 *   counted too. `outOfOrder`: the event was laid on the only node of its name although the
 *   execution had no way to it
 * @param nodes the nodes in doubt
 * @param edges the edges in doubt
 */
@Serializable
data class AmbiguousEvent(
    val history: String,
    val eventId: Long,
    val eventType: String,
    val name: String? = null,
    val doubt: String,
    val nodes: List<String>,
    val edges: List<EdgeName> = emptyList(),
)

@Serializable
data class EdgeName(val from: String, val to: String, val kind: EdgeKind = EdgeKind.FLOW, val label: String? = null)

/**
 * An event that is a step of the workflow and has no node.
 *
 * @param likelySources unknown calls the execution had reached, any of which may hide the step
 */
@Serializable
data class UnmappedStep(
    val history: String,
    val eventId: Long,
    val eventType: String,
    val name: String? = null,
    val likelySources: List<String> = emptyList(),
)

/**
 * An event that is no step of the workflow and has no node: it says what was done to the
 * execution from outside, as `WorkflowExecutionCancelRequested` does.
 */
@Serializable
data class OutsideEvent(val history: String, val eventId: Long, val eventType: String)

@Serializable
data class HistoryCoverage(
    val file: String,
    val workflowId: String? = null,
    val runId: String? = null,
    val status: String,
    val closedAt: List<String>,
    val events: EventCounts,
)
