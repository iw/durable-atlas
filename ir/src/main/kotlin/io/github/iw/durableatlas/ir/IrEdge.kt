package io.github.iw.durableatlas.ir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A directed connection between two nodes. [from] may also be the id of an entry point, which is
 * how a handler is linked to the await it wakes. The workflow method is linked the same way to
 * an await that its own code ends from elsewhere: from another lane, or while a handler waits.
 * Such an edge carries the properties in question as its [label], because the entry point of
 * the workflow method does not list what it writes.
 *
 * `wakes` and `cancels` edges say what one thing does to another, not where the flow goes: they
 * are no part of the flow, never count among the edges that say where a node goes on to, and
 * tell nothing about lanes.
 *
 * Where [to] is a region that [from] lies in, at any depth, the edge leads to the end of that
 * region; see [IrDocument] for how edges and regions go together.
 */
@Serializable
data class IrEdge(
    val from: String,
    val to: String,
    val kind: EdgeKind = EdgeKind.FLOW,
    /** Condition text, exception type or other caption shown on the edge. */
    val label: String? = null,
)

@Serializable
enum class EdgeKind {
    /** Ordinary control flow. This is the default and is left out of the JSON form. */
    @SerialName("flow")
    FLOW,

    /** Control flow taken when an exception is caught. */
    @SerialName("exception")
    EXCEPTION,

    /**
     * Control flow taken when a timeout elapses: out of an await whose time is up, also to the
     * clause that catches the `TimeoutException` of a promise that was waited for a time at most.
     */
    @SerialName("timeout")
    TIMEOUT,

    /**
     * A handler writes state that the target await is waiting on, or the workflow method does
     * where it runs beside the await: in another lane, or while a handler waits.
     */
    @SerialName("wakes")
    WAKES,

    /**
     * A [NodeKind.CANCEL_SCOPE] node cancels the target if it still runs: a step that was started
     * in the scope, or a region that holds such steps.
     */
    @SerialName("cancels")
    CANCELS,
    ;

    /** Whether an edge of this kind is a way the flow takes, and not something one node does to another. */
    val isFlow: Boolean
        get() = this != WAKES && this != CANCELS
}
