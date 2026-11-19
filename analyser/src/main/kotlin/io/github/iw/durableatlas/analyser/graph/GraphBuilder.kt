package io.github.iw.durableatlas.analyser.graph

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeIds
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.SourceLocation

/** The graph of one entry point: its `start` node, and every node and edge reached from it. */
internal class Graph(
    val start: String,
    val nodes: List<IrNode>,
    val edges: List<IrEdge>,
    /** The nodes each cancellation scope holds whose code the entry point runs. */
    val scopes: Map<ScopeKey, Set<String>> = emptyMap(),
    /** The nodes where a cancellation scope is cancelled. */
    val cancels: List<Cancellation> = emptyList(),
    /** What each wait on a workflow queue waits for there, by node id. */
    val queueWaits: Map<String, QueueWait> = emptyMap(),
    /** For each wait that the entry point itself can end from another lane: the properties in question, by node id. */
    val wokenByFlow: Map<String, List<String>> = emptyMap(),
)

/**
 * A place where a cancellation scope is cancelled.
 *
 * @param node the node that stands for the place
 * @param scope what says which scope it is: its [ScopeKey], or the property that holds it
 * @param done the steps that the flow has waited for by the time it gets to the place: they
 *   are over, and nothing is left of them to cancel
 */
internal class Cancellation(val node: String, val scope: Any, val done: Set<String>)

/**
 * Collects the nodes and edges of one entry point and gives the nodes their ids.
 *
 * An id is derived from the entry point, the place in the source, the calls that were inlined to
 * get there and the kind of node, so it stays the same for as long as that line does. Further
 * nodes of the same kind on the same line are numbered in the order they are met.
 */
internal class GraphBuilder(private val entryPointId: String) {
    private val nodes = ArrayList<IrNode>()
    private val edges = LinkedHashSet<IrEdge>()
    private val taken = HashMap<Slot, Int>()
    private val scopes = LinkedHashMap<ScopeKey, MutableSet<String>>()
    private val cancels = ArrayList<Cancellation>()
    private val queueWaits = LinkedHashMap<String, QueueWait>()
    private val wokenByFlow = LinkedHashMap<String, List<String>>()

    /** How many nodes there are so far. */
    val size: Int get() = nodes.size

    /** The nodes added since there were [count] of them. */
    fun since(count: Int): List<IrNode> = nodes.subList(count, nodes.size).toList()

    /** Notes that the cancellation scope [key] holds the nodes [ids]. */
    fun hold(key: ScopeKey, ids: List<String>) {
        scopes.getOrPut(key) { LinkedHashSet() } += ids
    }

    /**
     * Notes that the node [id] cancels the scope [scope] names: a [ScopeKey], or the property
     * that holds the scope. The steps [done] are over by then.
     */
    fun cancel(id: String, scope: Any, done: Set<String>) {
        cancels += Cancellation(id, scope, done)
    }

    /** Notes what the wait [id] waits on apart from what its node says: see [Graph.queueWaits] and [Graph.wokenByFlow]. */
    fun wait(id: String, queue: QueueWait?, wokenByFlow: List<String>) {
        if (queue != null) queueWaits[id] = queue
        if (wokenByFlow.isNotEmpty()) this.wokenByFlow[id] = wokenByFlow
    }

    /** What an id is unique for: further nodes in the same slot are numbered. */
    private data class Slot(val location: SourceLocation?, val inliningPath: List<String>, val role: String)

    /**
     * Adds a node made from [template], whose id is ignored.
     *
     * @param location where the node comes from, if anywhere
     * @param inliningPath the places that code was brought in from to reach the node, outermost first
     * @param anchored whether the id depends on [location]. The `start` of an entry point is
     *   found by what it is, wherever its function stands.
     */
    fun add(
        template: IrNode,
        location: SourceLocation?,
        inliningPath: List<String> = emptyList(),
        anchored: Boolean = true,
    ): String {
        val role = roleOf(template.kind)
        val anchor = location.takeIf { anchored }
        val ordinal = taken.merge(Slot(anchor, inliningPath, role), 1, Int::plus)!!
        val discriminator = if (ordinal == 1) role else "$role#$ordinal"
        val id = NodeIds.of(entryPointId, anchor, inliningPath, discriminator)
        nodes += template.copy(id = id, loc = location)
        return id
    }

    /** Adds an edge; one that is there already is not added again. */
    fun connect(from: String, to: String, kind: EdgeKind = EdgeKind.FLOW, label: String? = null) {
        edges += IrEdge(from = from, to = to, kind = kind, label = label)
    }

    /** The edges that start at [from]. */
    fun outgoing(from: String): List<IrEdge> = edges.filter { it.from == from }

    fun build(start: String): Graph =
        Graph(start, nodes.toList(), edges.toList(), scopes.toMap(), cancels.toList(), queueWaits.toMap(), wokenByFlow.toMap())

    private companion object {
        /** The name a kind has in the JSON form, which is what ids are derived from. */
        fun roleOf(kind: NodeKind): String = NodeKind.serializer().descriptor.getElementName(kind.ordinal)
    }
}
