package io.github.iw.durableatlas.overlay.mapping

import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.history.ExecutionHistory

/**
 * How often something was reached.
 *
 * @param uncertain how many of the [count] are not known for sure: the history fits several
 *   nodes or several ways through the graph, and each of them was counted
 */
data class Hits(val count: Int, val uncertain: Int = 0)

/** An event and the node, or the nodes, it was laid on. Handler events name their entry points. */
data class Match(val eventId: Long, val eventType: String, val name: String?, val nodes: List<String>)

/**
 * An event that was a step of the workflow and has no node.
 *
 * @param name what the step is called in the history: an activity type, a signal name and so on
 * @param likelySources unknown calls the execution had reached when the event was written, any
 *   of which may hide the step
 */
data class UnmappedEvent(val eventId: Long, val eventType: String, val name: String?, val likelySources: List<String>)

/**
 * An event the overlay knows and has no node for, because it is no step of the workflow:
 * something was done to the workflow from outside, as when it is asked to cancel.
 */
data class RecognisedEvent(val eventId: Long, val eventType: String)

/** Why a match is not certain. */
enum class Doubt(val text: String) {
    /** Several nodes fit the event and nothing in the history tells them apart. Each was counted. */
    CANDIDATES("candidates"),

    /** The event has one node, but several ways lead there from the node before. Each was counted. */
    PATHS("paths"),

    /** The event was laid on the one node of its name although the execution had no way to it. */
    OUT_OF_ORDER("outOfOrder"),
}

/**
 * A match the history does not settle.
 *
 * @param nodes the nodes in doubt: the candidates, or the nodes on some of the ways only
 * @param edges the edges on some of the ways only
 */
data class AmbiguousMatch(
    val doubt: Doubt,
    val eventId: Long,
    val eventType: String,
    val name: String?,
    val nodes: List<String>,
    val edges: List<EdgeRef> = emptyList(),
)

/**
 * One execution laid over the graph of its workflow type: what its history reached, how often,
 * and which of its events found no place.
 *
 * @param closedAt the nodes the execution ended at; empty where the end has no node, as when it
 *   was terminated or a failure escaped that no `throw` in the workflow raised
 * @param nodes hits by node id; nodes that were never reached are missing
 * @param edges hits by edge, implied edges and `wakes` edges included
 * @param entryPoints hits by entry point id: how often each handler was called
 * @param matches every event that was laid on the graph, in the order of the history
 * @param mapped events laid on exactly one node
 * @param recognised events that are no step of the workflow and have no node: what was done to
 *   it from outside
 */
class ExecutionTrace(
    val history: ExecutionHistory,
    val status: CloseStatus,
    val closedAt: List<String>,
    val nodes: Map<String, Hits>,
    val edges: Map<EdgeRef, Hits>,
    val entryPoints: Map<String, Hits>,
    val matches: List<Match>,
    val unmapped: List<UnmappedEvent>,
    val ambiguous: List<AmbiguousMatch>,
    val mapped: Int,
    val recognised: List<RecognisedEvent> = emptyList(),
) {
    /** Events laid on several nodes at once. */
    val ambiguousEvents: Int get() = ambiguous.count { it.doubt == Doubt.CANDIDATES }
}
