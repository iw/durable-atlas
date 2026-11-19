package io.github.iw.durableatlas.overlay.mapping

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.Join
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.UnknownReasons
import io.github.iw.durableatlas.ir.WaitsFor
import io.github.iw.durableatlas.ir.normalized
import io.github.iw.durableatlas.ir.validate

/**
 * An edge of a workflow graph as hit counts name it: an edge of the document, or one of the
 * edges between a region and its children that the document leaves implicit.
 */
data class EdgeRef(val from: String, val to: String, val kind: EdgeKind = EdgeKind.FLOW, val label: String? = null) {
    constructor(edge: IrEdge) : this(edge.from, edge.to, edge.kind, edge.label)
}

/** The places of a node that edges attach to. */
internal enum class Port {
    /** A node that is no region. */
    NODE,

    /** Where the flow arrives at a region from outside. */
    IN,

    /** Where each round of a loop begins and ends. */
    HEAD,

    /** Where the flow goes on after a region. */
    OUT,
}

internal class Vertex(val node: IrNode, val port: Port) {
    /** Where the flow can go from here, as the document says. */
    val out = ArrayList<Arc>()

    /** Further ways on that the document cannot rule out, tried when [out] leads nowhere. */
    val shortcuts = ArrayList<Arc>()

    override fun toString(): String = if (port == Port.NODE) node.id else "${node.id}.${port.name.lowercase()}"
}

/**
 * One move of the flow.
 *
 * @param edge the edge whose hits it counts as; `null` for moves inside a region's own frame and
 *   for shortcuts
 */
internal class Arc(val from: Vertex, val to: Vertex, val kind: EdgeKind, val label: String?, val edge: EdgeRef?) {
    override fun toString(): String = "$from -> $to"
}

/** One branch of a parallel region: the children that edges connect, and where it is entered. */
internal class Lane(val children: Set<String>, val entries: Set<Vertex>)

/**
 * A document as the walk over an event history needs it: a graph in which a region is not one
 * node but a frame around its children, with the edges the document implies written out.
 *
 * - An edge into a region ends where the region is entered ([Port.IN]), an edge out of it
 *   starts where it is left ([Port.OUT]).
 * - An edge from a node to a region around it, its own or one further out, ends where the flow
 *   inside that region ends: at the [Port.HEAD] of a loop, where the next round begins, and at
 *   the [Port.OUT] of any other region. The regions in between are left on the way.
 * - A region leads to each child that nothing inside it leads to. Each child that is no end of
 *   the flow leads to the end of the region, unless an edge of its own says where it goes: an
 *   edge of any kind to a sibling or to the end of a region around it, or a flow edge to a node
 *   outside the region. A failure or a timeout that is handled outside the region is one more
 *   way on, and leaves that one in place. These are the [implied] edges.
 * - The body of a loop hangs on its [Port.HEAD]: a round ends there and the next one begins
 *   there, so a loop is entered once however many rounds it takes. A loop can take no round.
 * - A compensation runs what was registered by the time it is called, each step as often as it
 *   was registered. Any of its steps can therefore be the first, be repeated or be left out;
 *   these moves are shortcuts.
 * - A parallel region that is not waited for is left where it is entered, at its [Port.IN]: the
 *   flow goes on from there while the lanes run. The lanes end at its [Port.OUT], from which
 *   nothing leads on.
 * - A parallel region that the flow leaves when the first of its lanes has ended is left at
 *   its [Port.OUT] like any other, by the lane that gets there first. For the other lanes
 *   nothing leads on from there: that is for the walk to keep to, see [raced].
 * - A step of a retry that fails ends the attempt: the flow is back at the [Port.HEAD] of the
 *   retry's loop, where the workflow pauses before the next attempt begins. Such a move is no
 *   edge of the document and counts as none.
 * - `wakes` and `cancels` edges are no moves of the flow. They are kept apart, for the counts.
 */
internal class FlowGraph(document: IrDocument) {
    val document: IrDocument = document.normalized()

    private val nodes: Map<String, IrNode> = this.document.nodes.associateBy { it.id }
    private val children: Map<String, List<IrNode>> = this.document.nodes.filter { it.parent != null }.groupBy { it.parent!! }

    /** Enclosing regions of each node, innermost first. */
    private val ancestors: Map<String, List<String>> =
        this.document.nodes.associate { node -> node.id to generateSequence(node.parent) { nodes.getValue(it).parent }.toList() }

    private val vertices = LinkedHashMap<Pair<String, Port>, Vertex>()

    /** Edges the document implies between regions and their children, in a stable order. */
    val implied: List<EdgeRef>

    /** The `wakes` edges that end at each await. */
    val wakes: Map<String, List<IrEdge>> = this.document.edges.filter { it.kind == EdgeKind.WAKES }.groupBy { it.to }

    /** The `cancels` edges that end at each step or region. */
    private val cancels: Map<String, List<IrEdge>> = this.document.edges.filter { it.kind == EdgeKind.CANCELS }.groupBy { it.to }

    /** The `cancels` edges that reach the node [id]: those that end at it, or at a region it lies in. */
    fun cancelling(id: String): List<IrEdge> = (listOf(id) + ancestors.getValue(id)).flatMap { cancels[it].orEmpty() }

    /** The loops that are retries: what they hold runs again for as long as it fails. */
    val retries: List<IrNode> = this.document.nodes.filter { it.kind == NodeKind.LOOP && it.retry }

    /** The entry point whose graph each node belongs to: the first that reaches it, the workflow method first. */
    val owner: Map<String, String>

    private val lanes = HashMap<String, List<Lane>>()
    private val upstream = HashMap<Vertex, Set<Vertex>>()
    private val observable = HashMap<Vertex, Boolean>()

    init {
        val problems = this.document.validate()
        require(problems.isEmpty()) {
            "Workflow IR of '${this.document.workflowType}' is inconsistent: ${problems.joinToString("; ")}"
        }
        for (node in this.document.nodes) {
            if (node.kind.isContainer) {
                add(node, Port.IN)
                if (node.kind == NodeKind.LOOP) add(node, Port.HEAD)
                add(node, Port.OUT)
            } else {
                add(node, Port.NODE)
            }
        }
        val flow = this.document.edges.filter { it.kind.isFlow }
        flow.forEach(::connect)
        implied = this.document.nodes.filter { it.kind.isContainer }.flatMap { frame(it, flow) }
        retries.forEach { retried(it, flow) }
        owner = owners()
    }

    val entryPoints: List<EntryPoint> get() = document.entryPoints

    val main: EntryPoint? = this.document.entryPoints.firstOrNull { it.kind == EntryPointKind.WORKFLOW_METHOD }

    fun node(id: String): IrNode = nodes.getValue(id)

    /** The ids of the nodes; an entry point is none. */
    val nodeIds: Set<String> get() = nodes.keys

    /** Where the flow arrives at a node. */
    fun arrival(id: String): Vertex {
        val node = nodes.getValue(id)
        return vertices.getValue(id to if (node.kind.isContainer) Port.IN else Port.NODE)
    }

    /** Where each round of the loop [id] begins and ends: for a retry, where the workflow pauses between two attempts. */
    fun head(id: String): Vertex = vertices.getValue(id to Port.HEAD)

    /** Where the flow goes on from a node. What is not waited for is left where it is entered. */
    private fun departure(id: String): Vertex {
        val node = nodes.getValue(id)
        return vertices.getValue(id to if (!node.kind.isContainer) Port.NODE else if (unawaited(node)) Port.IN else Port.OUT)
    }

    /** Whether [region] is a parallel region that the flow does not wait for: its lanes run on beside what follows it. */
    fun unawaited(region: IrNode): Boolean = region.kind == NodeKind.PARALLEL && region.waitsFor != null

    /** Whether [region] is a parallel region that the flow leaves when the first of its lanes has ended: the others run on. */
    fun raced(region: IrNode): Boolean = region.kind == NodeKind.PARALLEL && region.join == Join.FIRST

    /** Where the flow goes on after the region [id], and where its lanes end. */
    fun exit(id: String): Vertex = vertices.getValue(id to Port.OUT)

    /**
     * Whether the flow goes on from the step [node] without waiting for it to end. A child
     * workflow whose start is waited for from outside the region it stands in is no such step:
     * its region says the same of itself, and its lane waits for it like for any of its steps.
     */
    fun aside(node: IrNode): Boolean {
        if (node.waitsFor == null) return false
        val region = node.parent?.let(nodes::get)
        return !(node.waitsFor == WaitsFor.START && region != null && unawaited(region) && region.waitsFor == WaitsFor.START)
    }

    val all: Collection<Vertex> get() = vertices.values

    /** Vertices that stand for a node being reached: every node once. */
    val arrivals: List<Vertex> = this.document.nodes.map { arrival(it.id) }

    /** Whether [vertex] lies inside [region]. The places a region is entered and left at lie outside it. */
    fun inside(vertex: Vertex, region: IrNode): Boolean =
        region.id in ancestors.getValue(vertex.node.id) || (vertex.node.id == region.id && vertex.port == Port.HEAD)

    /**
     * Whether the flow can pass [vertex] without an event in the history. A branch, an unknown
     * call and the frame of a region never leave one. An await leaves one only if it has to
     * wait and has a timeout, a version gate only the first time its change id is met, a
     * mutable side effect only when its value changes. A scope that is cancelled leaves one
     * for each thing that still ran in it, and none if nothing did.
     */
    fun silent(vertex: Vertex): Boolean =
        vertex.port != Port.NODE || when (vertex.node.kind) {
            NodeKind.START, NodeKind.BRANCH, NodeKind.UNKNOWN, NodeKind.AWAIT, NodeKind.VERSION_GATE, NodeKind.CANCEL_SCOPE -> true
            NodeKind.SIDE_EFFECT -> vertex.node.name != null
            else -> false
        }

    /** The lanes of a parallel region. */
    fun lanes(region: IrNode): List<Lane> = lanes.getOrPut(region.id) {
        val body = children[region.id].orEmpty().map { it.id }
        val lane = body.associateWith { it }.toMutableMap()
        fun find(id: String): String {
            var current = id
            while (lane.getValue(current) != current) current = lane.getValue(current)
            return current
        }
        for (edge in document.edges) {
            if (!edge.kind.isFlow || !within(edge.from, region.id) || !within(edge.to, region.id)) continue
            val a = find(childOn(region.id, edge.from))
            val b = find(childOn(region.id, edge.to))
            if (a != b) lane[maxOf(a, b)] = minOf(a, b)
        }
        val entries = vertices.getValue(region.id to Port.IN).out.map { it.to }.toSet()
        body.groupBy(::find).values.map { members ->
            Lane(members.toSet(), entries.filter { it.node.id in members }.toSet())
        }
    }

    /** Everything the flow can have passed before it reached [vertex], over the edges of the document. */
    fun upstream(vertex: Vertex): Set<Vertex> = upstream.getOrPut(vertex) {
        val into = all.flatMap { it.out }.groupBy { it.to }
        val seen = LinkedHashSet<Vertex>()
        val pending = ArrayDeque(listOf(vertex))
        while (pending.isNotEmpty()) {
            for (arc in into[pending.removeFirst()].orEmpty()) if (seen.add(arc.from)) pending.addLast(arc.from)
        }
        seen
    }

    /** Whether anything that leaves an event, or an unknown call that may hide one, lies at or after [vertex]. */
    fun observableFrom(vertex: Vertex): Boolean = observable.getOrPut(vertex) {
        val seen = hashSetOf(vertex)
        val pending = ArrayDeque(listOf(vertex))
        var found = false
        while (pending.isNotEmpty() && !found) {
            val current = pending.removeFirst()
            found = current.port == Port.NODE && leavesEvent(current)
            for (arc in current.out) if (seen.add(arc.to)) pending.addLast(arc.to)
        }
        found
    }

    private fun leavesEvent(vertex: Vertex): Boolean = when (vertex.node.kind) {
        NodeKind.START, NodeKind.END, NodeKind.FAIL, NodeKind.BRANCH -> false
        NodeKind.AWAIT -> timed(vertex)
        else -> true
    }

    /** Whether an await can time out, as far as the document says. */
    fun timed(vertex: Vertex): Boolean =
        vertex.node.kind == NodeKind.AWAIT && (vertex.node.timeout != null || vertex.out.any { it.kind == EdgeKind.TIMEOUT })

    fun isRecursion(vertex: Vertex): Boolean =
        vertex.port == Port.NODE && vertex.node.kind == NodeKind.UNKNOWN && vertex.node.reason == UnknownReasons.RECURSION

    // --- construction ---

    private fun add(node: IrNode, port: Port) {
        vertices[node.id to port] = Vertex(node, port)
    }

    private fun arc(from: Vertex, to: Vertex, kind: EdgeKind = EdgeKind.FLOW, label: String? = null, edge: EdgeRef? = null) {
        from.out += Arc(from, to, kind, label, edge)
    }

    private fun shortcut(from: Vertex, to: Vertex) {
        if (from.out.none { it.to === to }) from.shortcuts += Arc(from, to, EdgeKind.FLOW, null, null)
    }

    private fun within(id: String, container: String): Boolean = container in ancestors.getValue(id)

    /** The direct child of [container] that is, or encloses, [id]. */
    private fun childOn(container: String, id: String): String {
        val chain = ancestors.getValue(id)
        val index = chain.indexOf(container)
        return if (index == 0) id else chain[index - 1]
    }

    /** Where a round of a region begins: the head of a loop, else its entrance. */
    private fun begin(region: IrNode): Vertex =
        vertices.getValue(region.id to if (region.kind == NodeKind.LOOP) Port.HEAD else Port.IN)

    /** Where the flow inside a region ends: the head of a loop, else its exit. */
    private fun end(region: IrNode): Vertex =
        vertices.getValue(region.id to if (region.kind == NodeKind.LOOP) Port.HEAD else Port.OUT)

    private fun connect(edge: IrEdge) {
        val from = nodes.getValue(document.entryPoints.firstOrNull { it.id == edge.from }?.start ?: edge.from)
        val to = nodes.getValue(edge.to)
        val source = if (from.kind.isContainer && within(to.id, from.id)) begin(from) else departure(from.id)
        val target = if (to.kind.isContainer && within(from.id, to.id)) end(to) else arrival(to.id)
        arc(source, target, edge.kind, edge.label, EdgeRef(edge))
    }

    /** Writes out what the document implies about [region] and its children. */
    private fun frame(region: IrNode, flow: List<IrEdge>): List<EdgeRef> {
        val body = children[region.id].orEmpty()
        val entrance = vertices.getValue(region.id to Port.IN)
        val exit = vertices.getValue(region.id to Port.OUT)
        if (region.kind == NodeKind.LOOP) {
            arc(entrance, begin(region))
            arc(end(region), exit)
        }
        if (body.isEmpty()) {
            if (region.kind != NodeKind.LOOP) arc(entrance, exit)
            return emptyList()
        }

        val implied = mutableListOf<EdgeRef>()
        for (child in body) {
            fun inSubtree(id: String) = id == child.id || within(id, child.id)
            val entered = flow.any { edge ->
                edge.to == child.id && !inSubtree(edge.from) && (edge.from == region.id || within(edge.from, region.id))
            }
            if (!entered) {
                val ref = EdgeRef(region.id, child.id)
                arc(begin(region), arrival(child.id), edge = ref)
                implied += ref
            }
            // A child that says where it goes is not left by implication: to a sibling, to the end
            // of this region or of one further out, or, with a flow edge, out of the region for good.
            val left = flow.any { edge ->
                edge.from == child.id && !inSubtree(edge.to) &&
                    (edge.kind == EdgeKind.FLOW || edge.to == region.id || within(edge.to, region.id) || within(region.id, edge.to))
            }
            if (!left && !child.kind.isTerminal) {
                // A compensation is not drawn with edges back from its steps, so these have no name.
                val ref = EdgeRef(child.id, region.id).takeIf { region.kind != NodeKind.COMPENSATION }
                arc(departure(child.id), end(region), edge = ref)
                if (ref != null) implied += ref
            }
        }
        if (region.kind == NodeKind.COMPENSATION) {
            for (child in body) {
                shortcut(entrance, arrival(child.id))
                shortcut(departure(child.id), exit)
                for (next in body) shortcut(departure(child.id), arrival(next.id))
            }
            shortcut(entrance, exit)
        }
        return implied
    }

    /**
     * Writes out what a retry does when a step in it fails: the attempt is over, and the flow is
     * back at the head of the loop. That holds for the steps whose failure a history shows and
     * the workflow waits for, unless a clause inside the retry catches it, and it is the
     * nearest retry around a step that takes its failure. A step that leads to the head anyway
     * needs no second way there.
     */
    private fun retried(region: IrNode, flow: List<IrEdge>) {
        val head = begin(region)
        for (node in document.nodes) {
            if (node.kind !in FAILING || aside(node)) continue
            if (ancestors.getValue(node.id).firstOrNull { nodes.getValue(it).retry } != region.id) continue
            val caughtInside = flow.any { it.from == node.id && it.kind == EdgeKind.EXCEPTION && within(it.to, region.id) }
            val from = departure(node.id)
            if (!caughtInside && from.out.none { it.to === head }) arc(from, head, EdgeKind.EXCEPTION)
        }
    }

    private fun owners(): Map<String, String> {
        val owner = LinkedHashMap<String, String>()
        val ordered = listOfNotNull(main) + document.entryPoints.filter { it !== main }
        for (entryPoint in ordered) {
            val start = arrival(entryPoint.start)
            val seen = hashSetOf(start)
            val pending = ArrayDeque(listOf(start))
            while (pending.isNotEmpty()) {
                val vertex = pending.removeFirst()
                owner.putIfAbsent(vertex.node.id, entryPoint.id)
                // A region belongs to one graph as a whole.
                for (next in vertex.out.map { it.to } + vertex.shortcuts.map { it.to }) if (seen.add(next)) pending.addLast(next)
            }
        }
        return owner
    }

    private companion object {
        /** The kinds of step whose failure a history shows. */
        val FAILING = setOf(
            NodeKind.ACTIVITY, NodeKind.LOCAL_ACTIVITY, NodeKind.CHILD_WORKFLOW, NodeKind.NEXUS_OPERATION,
            NodeKind.SIGNAL_EXTERNAL, NodeKind.CANCEL_EXTERNAL,
        )
    }
}
