package io.github.iw.durableatlas.render.layout

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.normalized
import io.github.iw.durableatlas.ir.validate
import io.github.iw.durableatlas.render.NodeLimit
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.Simplification

/**
 * Works out the [DiagramLayout] of a document.
 *
 * - **Flows.** Every node belongs to the first entry point (workflow method first) that reaches it
 *   over flow, exception and timeout edges. `wakes` and `cancels` edges are no ways the flow
 *   takes: they are drawn, and count for nothing else here. A region is never split: reaching a container or one
 *   of its children takes the whole region. Nodes that nothing reaches go to the main flow.
 * - **Order.** Nodes are numbered depth first from each entry point's start node. Among the
 *   successors of a node, flow edges come before exception and timeout edges, then earlier source
 *   lines before later ones. Everything a renderer writes follows these numbers.
 * - **Regions.** Loops, parallel sections and other containers with children are regions. The
 *   lanes of a parallel section are the groups of its children that edges connect to each other.
 *   Where the document does not connect a container with its children, edges are added: from the
 *   container to each child that nothing else in the region leads to, and, for loops and parallel
 *   sections, back to the container from each child that is no end of the flow and has no edge
 *   that says where it goes: to another child, to the end of a region around it, or, as a flow
 *   edge, out of the region. A child whose failure or timeout is handled outside the region
 *   still gets the edge back. An edge of the document from a node to a container around it, at
 *   any depth, leads to the end of that region: back to the head of a loop, to the join of a
 *   parallel section. A parallel section that is not waited for is left where it is entered,
 *   at its fork: its lanes end at its other end, and nothing follows them there.
 * - **Limit.** In a document over the node limit, every outermost region is one node. Edges that
 *   cross its border attach to that node, edges inside it are dropped. The same is done for a
 *   diagram that is to be drawn with its regions as single nodes because drawing it in full
 *   took too long.
 *
 * None of this depends on decorations, so a layout can be positioned once and drawn many times.
 */
internal object LayoutBuilder {
    fun build(document: IrDocument, options: RenderOptions): DiagramLayout {
        check(document)
        return Builder(document.normalized(), options).build()
    }

    /** @throws IllegalArgumentException if [document] is not consistent (see `IrDocument.validate`) */
    fun check(document: IrDocument) {
        val problems = document.validate()
        require(problems.isEmpty()) {
            "Workflow IR of '${document.workflowType}' is inconsistent: ${problems.joinToString("; ")}"
        }
    }

    /** Whether [document] has loops, parallel sections or other containers that are drawn as boxes around their steps. */
    fun hasRegions(document: IrDocument): Boolean {
        val parents = document.nodes.mapNotNullTo(HashSet()) { it.parent }
        return document.nodes.any { it.kind == NodeKind.LOOP || it.kind == NodeKind.PARALLEL || (it.kind.isContainer && it.id in parents) }
    }
}

/** An edge of the document with a handler's id replaced by that handler's start node. */
private class Link(val edge: IrEdge, val from: String, val to: String)

private val sourceOrder: Comparator<IrNode> =
    compareBy<IrNode>({ it.loc == null }, { it.loc?.file }, { it.loc?.line }, { it.id })

private class Builder(private val document: IrDocument, private val options: RenderOptions) {
    private val nodes: Map<String, IrNode> = document.nodes.associateBy { it.id }
    private val entryPoints: Map<String, EntryPoint> = document.entryPoints.associateBy { it.id }
    private val children: Map<String, List<IrNode>> =
        document.nodes.filter { it.parent != null }.groupBy { it.parent!! }

    /** Enclosing containers of each node, innermost first. */
    private val ancestors: Map<String, List<String>> =
        document.nodes.associate { node ->
            node.id to generateSequence(node.parent) { nodes.getValue(it).parent }.toList()
        }

    private val collapse: Boolean =
        document.nodes.size > options.maxNodes || options.simplification?.level == Simplification.Level.COLLAPSED

    private val links: List<Link> =
        document.edges.map { Link(it, entryPoints[it.from]?.start ?: it.from, it.to) }
    private val flowLinks: List<Link> = links.filter { it.edge.kind.isFlow }

    private val successors: Map<String, List<String>> =
        flowLinks.groupBy { it.from }.mapValues { (_, outgoing) ->
            outgoing.groupBy { it.to }
                .map { (to, parallel) -> nodes.getValue(to) to parallel.minOf { it.edge.kind } }
                .sortedWith(compareBy<Pair<IrNode, EdgeKind>> { it.second }.thenBy(sourceOrder) { it.first })
                .map { it.first.id }
        }

    /** Position of every node in the depth-first numbering. */
    private val order = HashMap<String, Int>()

    /** Index of the flow that owns each outermost node. */
    private val flowOf = HashMap<String, Int>()

    /** Extra label lines, by node id. */
    private val captions = HashMap<String, String>()

    fun build(): DiagramLayout {
        val main = document.entryPoints.firstOrNull { it.kind == EntryPointKind.WORKFLOW_METHOD }
        val handlers = document.entryPoints.filter { it !== main }
        val flows = handlers.withIndex().associate { (index, handler) -> handler.id to index + 1 }

        main?.let { visit(it.start, MAIN) }
        handlers.forEach { visit(it.start, flows.getValue(it.id)) }
        document.nodes.sortedWith(sourceOrder).forEach { visit(it.id, MAIN) }

        val groups = groups(handlers)
        groups.filter { it.size > 1 }.flatten().forEach { captions[it.start] = NodeText.startCaption(it.kind) }
        val edges = (links + impliedLinks()).mapNotNull(::lay)
        return DiagramLayout(
            document = document,
            main = items(MAIN),
            handlers = groups.map { group ->
                HandlerGroup(entryPoints = group, items = group.flatMap { items(flows.getValue(it.id)) })
            },
            edges = merge(edges),
            limit = NodeLimit(
                total = document.nodes.size,
                max = options.maxNodes,
                collapsedRegions = if (collapse) document.nodes.count { it.parent == null && isRegion(it) } else 0,
                drawn = if (collapse) document.nodes.count { it.parent == null } else document.nodes.size,
            ),
        )
    }

    // --- flows and order ---

    /**
     * Handlers that are drawn together, in the order of the first member of each group. An update
     * and the validators of the same update name form one group, validators first because they
     * run first; every other handler is a group of its own.
     */
    private fun groups(handlers: List<EntryPoint>): List<List<EntryPoint>> {
        val updates = handlers.filter { it.kind == EntryPointKind.UPDATE }.map { it.name }.toSet()
        fun key(handler: EntryPoint): String =
            if (handler.kind in NodeText.MERGED_UPDATE && handler.name in updates) "update ${handler.name}" else "id ${handler.id}"
        return handlers.groupBy(::key).values.map { group -> group.sortedByDescending { it.kind } }
    }

    private fun visit(start: String, flow: Int) {
        val pending = ArrayDeque<String>()
        pending.addLast(start)
        while (pending.isNotEmpty()) {
            val id = pending.removeLast()
            if (id in order) continue
            order[id] = order.size
            flowOf.putIfAbsent(outermost(id), flow)
            // Body first, then what follows, then the enclosing region; the last one pushed is
            // the next one visited.
            val next = bodyOf(id).map { it.id } + successors[id].orEmpty() + listOfNotNull(nodes.getValue(id).parent)
            next.asReversed().forEach { if (it !in order) pending.addLast(it) }
        }
    }

    private fun items(flow: Int): List<Item> =
        document.nodes
            .filter { it.parent == null && flowOf[it.id] == flow }
            .sortedBy { order.getValue(it.id) }
            .map(::item)

    private fun item(node: IrNode): Item = when {
        !isRegion(node) -> Step(node, captions[node.id])
        collapse -> {
            val hidden = descendants(node.id)
            CollapsedRegion(node, hidden.size, hidden.count { it.kind == NodeKind.UNKNOWN })
        }
        node.kind == NodeKind.LOOP -> LoopRegion(node, drawnBodyOf(node.id).map(::item))
        node.kind == NodeKind.PARALLEL -> ParallelRegion(node, lanes(node.id).map { lane -> lane.map(::item) })
        else -> ScopeRegion(node, drawnBodyOf(node.id).map(::item))
    }

    /**
     * Whether a node is drawn as a box around other nodes. Loops and parallel sections always
     * are; other containers only when they contain something.
     */
    private fun isRegion(node: IrNode): Boolean =
        node.kind == NodeKind.LOOP || node.kind == NodeKind.PARALLEL || (node.kind.isContainer && node.id in children)

    /** Direct children in source order, used before the numbering exists. */
    private fun bodyOf(id: String): List<IrNode> = children[id].orEmpty().sortedWith(sourceOrder)

    private fun drawnBodyOf(id: String): List<IrNode> = children[id].orEmpty().sortedBy { order.getValue(it.id) }

    private fun descendants(id: String): List<IrNode> {
        val found = mutableListOf<IrNode>()
        val pending = ArrayDeque(children[id].orEmpty())
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            found += node
            pending += children[node.id].orEmpty()
        }
        return found
    }

    private fun lanes(parallel: String): List<List<IrNode>> {
        val body = drawnBodyOf(parallel)
        val lane = body.associate { it.id to it.id }.toMutableMap()
        fun find(id: String): String {
            var current = id
            while (lane.getValue(current) != current) current = lane.getValue(current)
            return current
        }
        for (link in flowLinks) {
            if (!within(link.from, parallel) || !within(link.to, parallel)) continue
            val a = find(childOn(parallel, link.from))
            val b = find(childOn(parallel, link.to))
            // The lane that starts earlier keeps its name, so the result does not depend on edge order.
            if (a != b) {
                val (first, second) = if (order.getValue(a) < order.getValue(b)) a to b else b to a
                lane[second] = first
            }
        }
        return body.groupBy { find(it.id) }.values.toList()
    }

    // --- structure ---

    private fun outermost(id: String): String = ancestors.getValue(id).lastOrNull() ?: id

    /** Whether [id] is somewhere inside [container]; a node is not inside itself. */
    private fun within(id: String, container: String): Boolean = container in ancestors.getValue(id)

    private fun inSubtree(id: String, top: String): Boolean = id == top || within(id, top)

    /** The direct child of [container] that is, or encloses, [id]. */
    private fun childOn(container: String, id: String): String {
        val chain = ancestors.getValue(id)
        val index = chain.indexOf(container)
        return if (index == 0) id else chain[index - 1]
    }

    private fun expanded(node: IrNode, kind: NodeKind): Boolean = !collapse && node.kind == kind

    // --- edges ---

    /**
     * Edges between containers and their children that the document leaves implicit.
     *
     * A child is entered from its container unless an edge leads to it from elsewhere in the
     * region: from another child, from inside one, or from the container itself.
     *
     * A child that is no end of the flow leads on to the end of its region unless an edge of its
     * own says where it goes: an edge of any kind to another child, to the end of its region or
     * to the end of a region further out, or a flow edge to a node outside the region, as a
     * `break` or a `return` after the step gives. No other edge takes that way on from it:
     * - An exception or timeout edge to a node outside the region is one more way on from the
     *   child. The child still reaches the end of the round or lane when it does not fail.
     * - An edge that starts inside a child says where that one node goes. A loop that a `break`
     *   or a `continue` of the loop around it jumps out of is left the way it is without the jump.
     */
    private fun impliedLinks(): List<Link> {
        if (collapse) return emptyList()
        val incoming = flowLinks.groupBy { it.to }
        val outgoing = flowLinks.groupBy { it.from }
        val implied = mutableListOf<Link>()
        for (container in document.nodes.filter { it.kind.isContainer }.sortedBy { order.getValue(it.id) }) {
            for (child in drawnBodyOf(container.id)) {
                val entered = incoming[child.id].orEmpty().any { !inSubtree(it.from, child.id) && inSubtree(it.from, container.id) }
                if (!entered) implied += link(container.id, child.id)
                if (child.kind.isTerminal || container.kind !in REJOINED) continue
                val left = outgoing[child.id].orEmpty().any {
                    !inSubtree(it.to, child.id) &&
                        (it.edge.kind == EdgeKind.FLOW || inSubtree(it.to, container.id) || within(container.id, it.to))
                }
                if (!left) implied += link(child.id, container.id)
            }
        }
        return implied
    }

    private fun link(from: String, to: String) = Link(IrEdge(from, to), from, to)

    private fun lay(link: Link): LaidEdge? {
        val from = if (collapse) outermost(link.from) else link.from
        val to = if (collapse) outermost(link.to) else link.to
        val hidden = from != link.from || to != link.to
        if (hidden && from == to) return null

        val fromNode = nodes.getValue(from)
        val toNode = nodes.getValue(to)
        // What is not waited for is left as soon as it is entered.
        val leavesParallel = expanded(fromNode, NodeKind.PARALLEL) && !within(to, from) && fromNode.waitsFor == null && link.edge.kind.isFlow
        val closesParallel = expanded(toNode, NodeKind.PARALLEL) && within(from, to)
        return LaidEdge(
            from = Anchor(from, if (leavesParallel) Port.JOIN else Port.NODE),
            to = Anchor(to, if (closesParallel) Port.JOIN else Port.NODE),
            kind = link.edge.kind,
            label = label(link, repeats = expanded(toNode, NodeKind.LOOP) && within(from, to)),
            sources = listOf(link.edge),
        )
    }

    private fun label(link: Link, repeats: Boolean): String? {
        val own = link.edge.label?.singleLine(options.maxLabelLength)?.takeIf { it.isNotEmpty() }
        return when (link.edge.kind) {
            EdgeKind.FLOW -> own ?: NodeText.REPEAT.takeIf { repeats }
            EdgeKind.EXCEPTION -> own ?: NodeText.EXCEPTION
            EdgeKind.TIMEOUT -> when {
                own == null -> NodeText.TIMEOUT
                own.contains(NodeText.TIMEOUT, ignoreCase = true) -> own
                else -> "${NodeText.TIMEOUT}: $own"
            }
            EdgeKind.WAKES -> own ?: sharedState(link)
            EdgeKind.CANCELS -> own ?: NodeText.CANCELS
        }
    }

    /** The properties a handler writes and the woken node waits on. */
    private fun sharedState(link: Link): String? {
        val handler = entryPoints[link.edge.from] ?: return null
        val shared = handler.writes.intersect(nodes.getValue(link.to).reads.toSet()).sorted()
        return shared.joinToString(", ").singleLine(options.maxLabelLength).takeIf { it.isNotEmpty() }
    }

    /** Puts edges in drawing order and joins those that would be drawn the same. */
    private fun merge(edges: List<LaidEdge>): List<LaidEdge> {
        val drawingOrder = compareBy<LaidEdge>(
            { order.getValue(it.from.nodeId) },
            { order.getValue(it.to.nodeId) },
            { it.from.port },
            { it.to.port },
            { it.kind },
        ).thenBy(nullsFirst()) { it.label }
        return edges
            .groupBy { listOf(it.from, it.to, it.kind, it.label) }
            .values
            .map { same ->
                val first = same.first()
                LaidEdge(first.from, first.to, first.kind, first.label, same.flatMap { it.sources })
            }
            .sortedWith(drawingOrder)
    }

    private companion object {
        const val MAIN = 0

        /** Containers whose body leads back to them: a loop repeats, a parallel section joins. */
        val REJOINED = setOf(NodeKind.LOOP, NodeKind.PARALLEL)
    }
}
