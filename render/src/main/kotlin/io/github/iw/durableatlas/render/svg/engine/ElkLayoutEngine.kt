package io.github.iw.durableatlas.render.svg.engine

import io.github.iw.durableatlas.render.limit.Deadline
import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Point
import org.eclipse.elk.alg.layered.options.EdgeLabelSideSelection
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.alg.layered.options.OrderingStrategy
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeLabelPlacement
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.options.PortSide
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkBendPoint
import org.eclipse.elk.graph.ElkConnectableShape
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkLabel
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.ElkPort
import org.eclipse.elk.graph.util.ElkGraphUtil
import kotlin.math.abs

/**
 * Lays graphs out with the layered algorithm of the Eclipse Layout Kernel, with orthogonal edges.
 *
 * **Groups.** Every group is laid out on its own, innermost first, and then placed in the graph
 * around it as a box of fixed size. That is what lets a group flow in another direction than
 * its surroundings; the algorithm's mode that lays all levels out in one pass forces one
 * direction on all of them. An edge that crosses the border of a group is split there: the group
 * gets a port on its border, the piece inside is routed by the layout of the group, which also
 * decides where on the border the port sits, and the piece outside by the layout around it.
 *
 * **Ends.** Where an edge meets a node is decided here and not left to the algorithm, which
 * would spread the edges of a node evenly over its side and so push the main line of a flow off
 * the middle of every node that has a second edge. Edges leave a node in the middle of one
 * side and arrive in the middle of the opposite one, or as far from there as they ask to, so
 * the main line is straight, a fork parts after a common start, and what joins runs in
 * together. A point where edges meet is a node without extent.
 *
 * **Forks.** The algorithm keeps one edge of a fork straight and puts the others to one side.
 * Where the graph asks for a node to sit in the middle of its edges instead, and nothing else
 * connects the two sides, the part of the graph on the node's side is moved as one block
 * afterwards.
 *
 * **Spacing.** The algorithm keeps one distance between any two neighbouring layers, also when
 * one of them only holds the labels of edges, which would put a label a full node distance away
 * from the nodes on either side. So the algorithm is told that every node is [PAD] longer at
 * both ends, along the direction of its graph, and that layers are that much closer. Two nodes
 * then end up as far apart as asked, a label closer to its neighbours. Afterwards the nodes get
 * their real size back and the ends of the edges are drawn out to them.
 *
 * **Stability.** The result depends only on the graph: nodes and edges are handed over in the
 * order given, that order decides ties, and the algorithm's random seed is fixed.
 *
 * **Groups that come again.** A flow is laid out many times while its rows are looked for, with
 * other steps each time, and a group in it is handed to the algorithm again with every one of
 * them, usually exactly as before. What the algorithm made of a group is kept, by everything it
 * was handed, and taken from there when the same comes again. An engine is therefore made for
 * one diagram and dropped with it.
 *
 * **Stopping.** The algorithm asks between two of its phases whether to go on, and inside a
 * phase it does not: one that has begun runs to its end. So the [deadline] is asked before and
 * after every run as well, and a run that was cut short, which leaves its graph half done, is
 * never read.
 *
 * @param deadline asked whether to go on, here and by the algorithm
 */
internal class ElkLayoutEngine(private val deadline: Deadline = Deadline.none()) : LayoutEngine {
    /** What the algorithm made of each group, by what it was handed. */
    private val known = HashMap<String, Snapshot>()

    /** How many times the algorithm has run for this engine. */
    internal var runs: Int = 0
        private set

    override fun layout(graph: EngineGraph): EngineLayout = Run(graph).layout()

    /** Lets the algorithm see when it is to stop. It asks the monitor of each of its tasks, so every one of them answers. */
    private class Monitor(private val deadline: Deadline) : BasicProgressMonitor() {
        override fun isCanceled(): Boolean = deadline.passed

        override fun doSubTask(work: Float, maxHierarchyLevels: Int): BasicProgressMonitor = Monitor(deadline)
    }

    /** Where the algorithm put everything in a graph of one level: all that it leaves there and that is read afterwards. */
    private class Snapshot(
        private val width: Double,
        private val height: Double,
        /** Place and size of every node, four numbers each. */
        private val nodes: DoubleArray,
        /** Place of every port on the border of the graph, two numbers each. */
        private val ports: DoubleArray,
        /** For every edge its sections, each as the points it runs through. */
        private val sections: List<List<DoubleArray>>,
        /** Place and size of every label, four numbers each, in the order of the edges. */
        private val labels: DoubleArray,
    ) {
        /** Puts everything into [root], a graph like the one this was taken from, where the algorithm would have put it. */
        fun restore(root: ElkNode) {
            root.setDimensions(width, height)
            root.children.forEachIndexed { index, node ->
                node.setLocation(nodes[4 * index], nodes[4 * index + 1])
                node.setDimensions(nodes[4 * index + 2], nodes[4 * index + 3])
            }
            root.ports.forEachIndexed { index, port -> port.setLocation(ports[2 * index], ports[2 * index + 1]) }
            var label = 0
            root.containedEdges.forEachIndexed { index, edge ->
                for (points in sections[index]) {
                    val section = ElkGraphUtil.createEdgeSection(edge)
                    section.setStartLocation(points[0], points[1])
                    for (bend in 2 until points.size - 2 step 2) ElkGraphUtil.createBendPoint(section, points[bend], points[bend + 1])
                    section.setEndLocation(points[points.size - 2], points[points.size - 1])
                }
                for (text in edge.labels) {
                    text.setLocation(labels[4 * label], labels[4 * label + 1])
                    text.setDimensions(labels[4 * label + 2], labels[4 * label + 3])
                    label++
                }
            }
        }

        companion object {
            fun of(root: ElkNode): Snapshot {
                val texts = root.containedEdges.flatMap { it.labels }
                return Snapshot(
                    width = root.width,
                    height = root.height,
                    nodes = root.children.flatMap { listOf(it.x, it.y, it.width, it.height) }.toDoubleArray(),
                    ports = root.ports.flatMap { listOf(it.x, it.y) }.toDoubleArray(),
                    sections = root.containedEdges.map { edge ->
                        edge.sections.map { section ->
                            (listOf(section.startX, section.startY) + section.bendPoints.flatMap { bend: ElkBendPoint -> listOf(bend.x, bend.y) } +
                                listOf(section.endX, section.endY)).toDoubleArray()
                        }
                    },
                    labels = texts.flatMap { listOf(it.x, it.y, it.width, it.height) }.toDoubleArray(),
                )
            }

            /**
             * Everything in [root] that the algorithm goes by, as a text: two graphs with the same
             * text are laid out the same. The settings that are the same for every graph of one
             * direction are not in it.
             */
            fun signature(root: ElkNode): String {
                val text = StringBuilder()
                val ports = HashMap<ElkPort, String>()
                fun side(port: ElkPort) = port.getProperty(CoreOptions.PORT_SIDE).ordinal
                val padding = root.getProperty(CoreOptions.PADDING)
                text.append(root.getProperty(CoreOptions.DIRECTION).ordinal).append(' ')
                    .append(padding.top).append(' ').append(padding.right).append(' ').append(padding.bottom).append(' ').append(padding.left).append(' ')
                    .append(root.getProperty(CoreOptions.PORT_CONSTRAINTS).ordinal)
                root.ports.forEachIndexed { index, port ->
                    ports[port] = "r$index"
                    text.append(" r").append(side(port))
                }
                root.children.forEachIndexed { index, node ->
                    text.append("\n").append(node.identifier.length).append(':').append(node.identifier).append(' ').append(node.width).append(' ').append(node.height)
                    node.ports.forEachIndexed { number, port ->
                        ports[port] = "$index.$number"
                        text.append(' ').append(port.x).append(' ').append(port.y).append(' ').append(side(port))
                    }
                }
                for (edge in root.containedEdges) {
                    text.append("\n").append(ports.getValue(edge.sources.single() as ElkPort)).append('>').append(ports.getValue(edge.targets.single() as ElkPort))
                        .append(' ').append(edge.getProperty(LayeredOptions.PRIORITY_STRAIGHTNESS)).append(' ').append(edge.getProperty(LayeredOptions.PRIORITY_SHORTNESS))
                    for (label in edge.labels) text.append(' ').append(label.width).append(' ').append(label.height)
                }
                return text.toString()
            }
        }
    }

    /** One end of a piece of an edge inside a group: a place on a node of the group, or the group's own border. */
    private class End(val node: String?, val anchor: String? = null, val shift: Double = 0.0) {
        val onBorder: Boolean
            get() = node == null
    }

    /** The piece of an edge that lies in one group. */
    private class Hop(val edge: EngineEdge, val from: End, val to: End, val labelled: Boolean)

    /** Where a group's layout put things, relative to the corner of the group as the algorithm sees it. */
    private class Arrangement(
        val width: Double,
        val height: Double,
        val boxes: Map<String, Box>,
        /** Where each edge that crosses the group's border does so, by edge id. */
        val crossings: Map<String, Point>,
        val sections: Map<Hop, List<Point>>,
        val labels: Map<Hop, Box>,
    )

    private inner class Run(private val graph: EngineGraph) {
        private val byId = HashMap<String, EngineNode>()

        /** The group each node is a child of; nodes of the outermost graph are not in here. */
        private val groupOf = HashMap<String, EngineNode>()
        private val hops = HashMap<String?, MutableList<Hop>>()
        private val hopsOf = LinkedHashMap<String, List<Pair<String?, Hop>>>()

        /** The side on which each edge crosses the borders of the groups it leaves or enters. */
        private val crossingSide = HashMap<String, Side>()
        private val arrangements = HashMap<String?, Arrangement>()

        /** The edges that end at the border of a group around their source, each with that group. */
        private val ending = HashMap<String, String>()

        /** How far from the middle of its sides the usual places of each group lie, by group and by whether the sides are upright. */
        private val leans = HashMap<Pair<String, Boolean>, Double>()

        fun layout(): EngineLayout {
            fun index(node: EngineNode, group: EngineNode?) {
                byId[node.id] = node
                if (group != null) groupOf[node.id] = group
                node.children.forEach { index(it, node) }
            }
            graph.nodes.forEach { index(it, null) }
            graph.edges.forEach(::split)
            arrange(null)
            if (graph.flow == Flow.RIGHT) {
                graph.fans.forEach { centre(it, leaving = true) }
                graph.joins.forEach { centre(it, leaving = false) }
            }
            return assemble()
        }

        /**
         * Moves the node [id] of the outermost level to the middle of the edges that leave it,
         * or of those that arrive at it, so that they part or meet evenly on both sides.
         *
         * The algorithm that places nodes keeps one of those edges straight and puts the others
         * to one side. Here everything on the node's side of the upright stretch the edges share
         * is moved up or down as one block, and the edges are drawn again from the node to that
         * stretch. That is only possible if nothing else leads from the one block to the other.
         */
        private fun centre(id: String, leaving: Boolean) {
            val arrangement = arrangements.getValue(null)
            val all = hops[null].orEmpty()
            val own = all.filter { (if (leaving) it.from.node else it.to.node) == id }
            if (own.size < 2) return
            // Every edge as it runs from the node: straight, or to the shared stretch, along it, and on.
            val lines = own.map { hop ->
                val points = corners(arrangement.sections[hop] ?: return)
                if (leaving) points else points.asReversed()
            }
            val simple = lines.all { line ->
                (line.size == 2 || line.size == 4 && same(line[1].x, line[2].x) && same(line[2].y, line[3].y)) && same(line[0].y, line[1].y)
            }
            val bars = lines.filter { it.size == 4 }.map { it[1].x }
            val port = lines.first()[0]
            if (!simple || bars.isEmpty() || bars.any { !same(it, bars[0]) } || lines.any { !same(it[0].y, port.y) }) return
            val bar = bars[0]
            val ends = lines.map { it.last().y }
            val shift = (ends.min() + ends.max()) / 2 - port.y
            if (same(shift, 0.0)) return

            fun moves(x: Double): Boolean = if (port.x < bar) x < bar - EPSILON else x > bar + EPSILON
            val others = all.filter { it !in own }.mapNotNull { arrangement.sections[it] }
            val torn = others.any { points -> points.any { moves(it.x) } && points.any { !moves(it.x) } } ||
                arrangement.boxes.values.any { moves(it.x) != moves(it.right) }
            if (torn) return

            val sections = arrangement.sections.mapValues { (hop, points) ->
                val at = own.indexOf(hop)
                if (at < 0) {
                    points.map { if (moves(it.x)) it.moved(0.0, shift) else it }
                } else {
                    val line = lines[at]
                    val start = line[0].moved(0.0, shift)
                    val end = line.last()
                    val drawn = if (same(start.y, end.y)) listOf(start, end) else listOf(start, Point(bar, start.y), Point(bar, end.y), end)
                    if (leaving) drawn else drawn.asReversed()
                }
            }
            val labels = arrangement.labels.mapValues { (hop, box) ->
                val at = own.indexOf(hop)
                val end = if (at < 0) null else lines[at].last().y
                when {
                    moves(box.centerX) -> box.moved(0.0, shift)
                    // The label of an edge that turns downwards goes below it, away from the other edges.
                    end != null && end > port.y + shift + EPSILON && box.bottom <= end && end - box.bottom < 2 * Geometry.LABEL_OFFSET ->
                        box.moved(0.0, end + Geometry.LABEL_OFFSET - box.y)
                    else -> box
                }
            }
            arrangements[null] = Arrangement(
                width = arrangement.width,
                height = arrangement.height,
                boxes = arrangement.boxes.mapValues { (_, box) -> if (moves(box.x)) box.moved(0.0, shift) else box },
                crossings = arrangement.crossings,
                sections = sections,
                labels = labels,
            )
        }

        /** [points] without those in the middle of a straight run. */
        private fun corners(points: List<Point>): List<Point> {
            val kept = ArrayList<Point>()
            for (point in points) {
                if (kept.isNotEmpty() && same(kept.last().x, point.x) && same(kept.last().y, point.y)) continue
                if (kept.size >= 2) {
                    val a = kept[kept.size - 2]
                    val b = kept.last()
                    if (same(a.x, b.x) && same(b.x, point.x) || same(a.y, b.y) && same(b.y, point.y)) kept.removeAt(kept.size - 1)
                }
                kept += point
            }
            return kept
        }

        private fun same(a: Double, b: Double): Boolean = abs(a - b) < EPSILON

        private fun flowOf(group: String?): Flow = if (group == null) graph.flow else byId.getValue(group).flow

        /** The groups around a node, innermost first, ending with null for the graph itself. */
        private fun enclosing(id: String): List<String?> =
            generateSequence(groupOf[id]) { groupOf[it.id] }.map { it.id }.toList<String?>() + null

        /** Cuts an edge into the pieces that lie in each group it runs through. */
        private fun split(edge: EngineEdge) {
            val around = enclosing(edge.to)
            require(edge.from !in around) { "Edge ${edge.id} leads from a group to something inside it" }
            if (edge.to in enclosing(edge.from)) {
                splitToEnd(edge)
                return
            }
            val shared = enclosing(edge.from).first { it in around }
            val leaves = enclosing(edge.from).takeWhile { it != shared }.filterNotNull()
            val enters = around.takeWhile { it != shared }.filterNotNull().asReversed()
            if (leaves.isNotEmpty() || enters.isNotEmpty()) {
                // The edge keeps to the side it has in the graph where its two ends meet.
                crossingSide[edge.id] = when (flowOf(shared)) {
                    Flow.RIGHT -> if (leaves.isNotEmpty()) Side.EAST else Side.WEST
                    Flow.DOWN -> if (leaves.isNotEmpty()) Side.SOUTH else Side.NORTH
                }
            }
            val pieces = ArrayList<Pair<String?, Hop>>()
            var from = End(edge.from, edge.fromAnchor, edge.fromShift)
            val last = End(edge.to, edge.toAnchor, edge.toShift)
            for (group in leaves) {
                pieces += group to Hop(edge, from, End(null), labelled = false)
                from = End(group)
            }
            pieces += shared to Hop(edge, from, if (enters.isEmpty()) last else End(enters.first()), labelled = true)
            enters.forEachIndexed { index, group ->
                pieces += group to Hop(edge, End(null), if (index == enters.lastIndex) last else End(enters[index + 1]), labelled = false)
            }
            pieces.forEach { (group, hop) -> hops.getOrPut(group) { ArrayList() } += hop }
            hopsOf[edge.id] = pieces
        }

        /**
         * An edge from a node to a group it lies in runs to the end of that group: to the side
         * of its border that the group's steps flow towards. It has no piece outside the group.
         * On its way it leaves every group between the node and that one, each on the same side.
         *
         * Its label goes with the first piece that the algorithm routes, which is the nearest to
         * the node the edge starts at: a piece that leaves a group sideways is drawn afterwards
         * and has no room for one, and the piece in the group the edge ends in is never one of
         * those.
         */
        private fun splitToEnd(edge: EngineEdge) {
            ending[edge.id] = edge.to
            val side = if (flowOf(edge.to) == Flow.RIGHT) Side.EAST else Side.SOUTH
            crossingSide[edge.id] = side
            val groups = enclosing(edge.from).takeWhile { it != edge.to }.filterNotNull() + edge.to
            val labelled = groups.first { !sideways(flowOf(it), side) }
            var from = End(edge.from, edge.fromAnchor, edge.fromShift)
            val pieces = groups.map { group ->
                (group to Hop(edge, from, End(null), labelled = group == labelled)).also { from = End(group) }
            }
            pieces.forEach { (group, hop) -> hops.getOrPut(group) { ArrayList() } += hop }
            hopsOf[edge.id] = pieces
        }

        /** Whether [side] is a side that the steps of a group with the direction [flow] do not flow towards or from. */
        private fun sideways(flow: Flow, side: Side): Boolean = (flow == Flow.DOWN) == (side == Side.EAST || side == Side.WEST)

        /** Lays out the group with id [group], or the whole graph for null, after the groups inside it. */
        private fun arrange(group: String?) {
            val children = if (group == null) graph.nodes else byId.getValue(group).children
            children.filter { it.children.isNotEmpty() }.forEach { arrange(it.id) }
            var arrangement = GroupLayout(group, children, 0.0).run()
            if (group != null) {
                // A group is at least as wide as its header. The room it lacks goes on both sides of its content.
                val node = byId.getValue(group)
                val least = node.width + if (groupOf[group]?.flow ?: graph.flow == Flow.RIGHT) 2 * PAD else 0.0
                if (arrangement.width < least - EPSILON) arrangement = GroupLayout(group, children, (least - arrangement.width) / 2).run()
            }
            arrangements[group] = arrangement
        }

        /** One run of the algorithm for the children of one group. */
        private inner class GroupLayout(private val group: String?, private val children: List<EngineNode>, private val widen: Double) {
            private val flow = flowOf(group)
            // The algorithm reads the settings of the graph around a node that has ports, so the
            // node whose content is laid out here is given an empty graph to sit in.
            private val root: ElkNode = ElkGraphUtil.createNode(ElkGraphUtil.createGraph())
            private val nodes = HashMap<String, ElkNode>()
            private val crossings = HashMap<String, ElkPort>()

            /** The places where edges attach to nodes: by node, anchor and shift. */
            private val places = LinkedHashMap<Triple<String, String, Double>, ElkPort>()
            private val pieces = LinkedHashMap<Hop, ElkEdge>()
            private val labels = HashMap<Hop, ElkLabel>()

            fun run(): Arrangement {
                val node = group?.let(byId::getValue)
                configure(node?.padding ?: Insets.NONE, if (group == null) null else groupOf[group]?.flow ?: graph.flow)
                children.forEach(::add)
                val (sideways, routed) = hops[group].orEmpty().partition(::isSideways)
                routed.forEach(::connect)
                arrange()

                val stubs = stubs(sideways)
                return Arrangement(
                    width = root.width,
                    height = root.height,
                    boxes = nodes.mapValues { (_, elk) -> Box(elk.x, elk.y, elk.width, elk.height) },
                    crossings = crossings.mapValues { (_, port) -> Point(port.x, port.y) } +
                        stubs.entries.associate { (hop, points) -> hop.edge.id to if (hop.to.onBorder) points.last() else points.first() },
                    sections = stubs + pieces.mapValues { (_, edge) ->
                        edge.sections.flatMap { section ->
                            listOf(Point(section.startX, section.startY)) + section.bendPoints.map { Point(it.x, it.y) } +
                                Point(section.endX, section.endY)
                        }
                    },
                    labels = labels.mapValues { (hop, label) ->
                        // The label was announced smaller than it is, and is centred on the place it was given.
                        val width = hop.edge.labelWidth
                        val height = hop.edge.labelHeight
                        Box(label.x - (width - label.width) / 2, label.y - (height - label.height) / 2, width, height)
                    },
                )
            }

            /** Has the algorithm lay the graph out, or takes what it made of the same graph before. */
            private fun arrange() {
                // A row holds other steps every time it is tried, so only what is inside a group comes again.
                val signature = if (group == null) null else Snapshot.signature(root)
                val before = signature?.let(known::get)
                if (before != null) {
                    before.restore(root)
                    return
                }
                deadline.check()
                runs++
                RecursiveGraphLayoutEngine().layout(root, Monitor(deadline))
                deadline.check()
                if (signature != null) known[signature] = Snapshot.of(root)
            }

            /**
             * Whether a piece of an edge runs from a node to a side of the group that the group's
             * steps do not flow towards, such as out of the right side of a group that flows down.
             * The algorithm would lead such a piece around the nodes to the far end of that side.
             * It is drawn straight across instead, from the side of the node that faces the border,
             * and only turns aside for a node that lies in between.
             */
            private fun isSideways(hop: Hop): Boolean {
                if (!hop.from.onBorder && !hop.to.onBorder) return false
                return sideways(flow, crossingSide.getValue(hop.edge.id))
            }

            /** The lines of the pieces that leave or enter the group sideways. Call after the layout. */
            private fun stubs(sideways: List<Hop>): Map<Hop, List<Point>> {
                val stubs = LinkedHashMap<Hop, List<Point>>()
                val perNodeSide = sideways.groupBy { (if (it.to.onBorder) it.from.node else it.to.node) to crossingSide.getValue(it.edge.id) }
                // Where the pieces that went around something meet the border, by side: each in a place of its own.
                val around = HashMap<Side, MutableList<Double>>()
                for ((key, group) in perNodeSide) {
                    val (name, side) = key
                    val elk = nodes.getValue(name!!)
                    val inner = arrangements[name]
                    val horizontal = side == Side.EAST || side == Side.WEST
                    val middle = if (horizontal) elk.y + elk.height / 2 else elk.x + elk.width / 2
                    group.forEachIndexed { index, hop ->
                        val crossing = inner?.crossings?.get(hop.edge.id)
                        val at = when {
                            crossing != null -> if (horizontal) elk.y + crossing.y else elk.x + crossing.x
                            else -> middle + (index - (group.size - 1) / 2.0) * STUB_SPACING
                        }
                        val (near, far) = when (side) {
                            Side.EAST -> Point(elk.x + elk.width, at) to Point(root.width, at)
                            Side.WEST -> Point(elk.x, at) to Point(0.0, at)
                            Side.SOUTH -> Point(at, elk.y + elk.height) to Point(at, root.height)
                            Side.NORTH -> Point(at, elk.y) to Point(at, 0.0)
                        }
                        val line = clear(near, far, elk, around.getOrPut(side) { ArrayList() })
                        stubs[hop] = if (hop.to.onBorder) line else line.asReversed()
                    }
                }
                return stubs
            }

            /**
             * The line of a piece that runs sideways from [near], on the node [own], to [far], on the
             * border of the group: straight across where nothing lies between the two. Where another
             * node does, the line would run under it and seem to start there. It then turns
             * halfway to that node, runs along to the nearest place from which the way to the
             * border is free, and goes on from there. If there is no such place, it stays straight.
             *
             * @param taken where lines that turned before this one meet the same side of the border
             */
            private fun clear(near: Point, far: Point, own: ElkNode, taken: MutableList<Double>): List<Point> {
                val others = nodes.values.filter { it !== own }.map { Box(it.x, it.y, it.width, it.height) }
                fun free(a: Point, b: Point): Boolean {
                    val line = Box(minOf(a.x, b.x), minOf(a.y, b.y), abs(a.x - b.x), abs(a.y - b.y)).grown(STUB_CLEARANCE)
                    return others.none { it.intersects(line) }
                }
                if (free(near, far)) return listOf(near, far)

                // Seen along the line: how far it is to the first node in the way, and where it may run instead.
                val upright = same(near.x, far.x)
                val forward = if (upright) far.y > near.y else far.x > near.x
                fun along(point: Point) = if (upright) point.y else point.x
                fun start(box: Box) = if (upright) box.y else box.x
                fun end(box: Box) = if (upright) box.bottom else box.right
                fun low(box: Box) = if (upright) box.x else box.y
                fun high(box: Box) = if (upright) box.right else box.bottom
                val at = if (upright) near.x else near.y
                val straight = Box(minOf(near.x, far.x), minOf(near.y, far.y), abs(near.x - far.x), abs(near.y - far.y)).grown(STUB_CLEARANCE)
                val gap = others.filter { it.intersects(straight) }.minOf { if (forward) start(it) - along(near) else along(near) - end(it) }
                if (gap < 2 * STUB_CLEARANCE) return listOf(near, far)
                val level = along(near) + (if (forward) 1 else -1) * gap / 2
                fun point(across: Double, along: Double) = if (upright) Point(across, along) else Point(along, across)

                val extent = if (upright) root.width else root.height
                val candidates = others.flatMap { box ->
                    (0..MAX_STUB_SHIFT).flatMap { step -> listOf(low(box) - EDGE_NODE - step * STUB_SPACING, high(box) + EDGE_NODE + step * STUB_SPACING) }
                }.filter { it in EDGE_NODE..(extent - EDGE_NODE) && taken.none { other -> abs(other - it) < STUB_SPACING - EPSILON } }
                    .sortedWith(compareBy<Double> { abs(it - at) }.thenBy { it })
                val across = candidates.firstOrNull { free(point(at, level), point(it, level)) && free(point(it, level), point(it, along(far))) }
                    ?: return listOf(near, far)
                taken += across
                return listOf(near, point(at, level), point(across, level), point(across, along(far)))
            }

            /**
             * @param padding the room to keep around the content of the group
             * @param around the direction of the graph the group is a node of; null for the outermost
             */
            private fun configure(padding: Insets, around: Flow?) {
                root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
                root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.SEPARATE_CHILDREN)
                root.setProperty(CoreOptions.DIRECTION, if (flow == Flow.RIGHT) Direction.RIGHT else Direction.DOWN)
                root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
                // Along its own direction a group needs less padding, because its nodes are padded
                // themselves; as a node of the graph around it, it is padded like any other node.
                fun side(own: Double, along: Flow): Double =
                    maxOf(0.0, own - (if (flow == along) PAD else 0.0) + (if (around == along) PAD else 0.0))
                root.setProperty(
                    CoreOptions.PADDING,
                    ElkPadding(
                        side(padding.top, Flow.DOWN),
                        side(padding.right, Flow.RIGHT) + widen,
                        side(padding.bottom, Flow.DOWN),
                        side(padding.left, Flow.RIGHT) + widen,
                    ),
                )
                // Nodes without a connection share the first layer instead of being packed elsewhere.
                root.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, false)
                root.setProperty(LayeredOptions.RANDOM_SEED, 1)
                root.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.NODES_AND_EDGES)
                root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.NETWORK_SIMPLEX)
                root.setProperty(LayeredOptions.NODE_PLACEMENT_FAVOR_STRAIGHT_EDGES, true)

                val between = if (flow == Flow.RIGHT) Geometry.ROW_SPACING else Geometry.STACK_SPACING
                val beside = if (flow == Flow.RIGHT) Geometry.STACK_SPACING else Geometry.BRANCH_SPACING
                root.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, between - 2 * PAD)
                root.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, (between - 2 * PAD) / 2)
                root.setProperty(LayeredOptions.SPACING_EDGE_EDGE_BETWEEN_LAYERS, EDGE_EDGE)
                root.setProperty(CoreOptions.SPACING_NODE_NODE, beside)
                root.setProperty(CoreOptions.SPACING_EDGE_NODE, EDGE_NODE)
                root.setProperty(CoreOptions.SPACING_EDGE_EDGE, EDGE_EDGE)
                root.setProperty(CoreOptions.SPACING_EDGE_LABEL, Geometry.LABEL_OFFSET)
                // A label always sits on the same side of its edge. The side the algorithm would pick
                // per edge is picked after the nodes are placed, and then leaves a kink in the edge.
                root.setProperty(LayeredOptions.EDGE_LABELS_SIDE_SELECTION, EdgeLabelSideSelection.ALWAYS_UP)
            }

            private fun add(node: EngineNode) {
                val elk = ElkGraphUtil.createNode(root)
                elk.identifier = node.id
                nodes[node.id] = elk
                // Every edge attaches at a place that is decided here, not by the algorithm.
                elk.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_POS)
                val inner = arrangements[node.id]
                if (inner != null) {
                    // A group that was laid out before: a box of that size.
                    elk.setDimensions(inner.width, inner.height)
                } else if (node.point) {
                    elk.setDimensions(0.0, 0.0)
                } else {
                    elk.setDimensions(
                        node.width + if (flow == Flow.RIGHT) 2 * PAD else 0.0,
                        node.height + if (flow == Flow.DOWN) 2 * PAD else 0.0,
                    )
                }
            }

            /**
             * The place on [elk] where an edge attaches: at the anchor called [anchor], or, for
             * null, in the middle of the side that faces where the flow comes from or goes to;
             * and [shift] further along that side.
             */
            private fun place(elk: ElkNode, anchor: String?, shift: Double, leaving: Boolean): ElkPort {
                val name = elk.identifier
                val usual = if (leaving) "out" else "in"
                return places.getOrPut(Triple(name, anchor ?: usual, shift)) {
                    val given = anchor?.let { id -> byId.getValue(name).anchors.first { it.id == id } }
                    val side = given?.side ?: if (flow == Flow.RIGHT) (if (leaving) Side.EAST else Side.WEST) else (if (leaving) Side.SOUTH else Side.NORTH)
                    val vertical = side == Side.WEST || side == Side.EAST
                    // Anchors are given for the real size of a node; along the flow the algorithm sees it padded.
                    val padded = arrangements[name] == null && !byId.getValue(name).point && (flow == Flow.DOWN) == vertical
                    val middle = (if (vertical) elk.height else elk.width) / 2 + lean(name, vertical)
                    val at = (given?.let { it.offset + if (padded) PAD else 0.0 } ?: middle) + shift
                    when (side) {
                        Side.WEST -> port(elk, side, 0.0, at)
                        Side.EAST -> port(elk, side, elk.width, at)
                        Side.NORTH -> port(elk, side, at, 0.0)
                        Side.SOUTH -> port(elk, side, at, elk.height)
                    }
                }
            }

            /**
             * How far the usual places of the group [name] are moved off the middle of its
             * sides. An edge that leads out of the group from a step inside it crosses the
             * border where the group's own layout put it. If that is where the edges of the
             * group itself attach, the two would run on as one line, so those are moved aside:
             * on both sides alike, so that the line through the group stays straight. An edge
             * that ends at the border of this group runs on nowhere, and does not count.
             */
            private fun lean(name: String, vertical: Boolean): Double = leans.getOrPut(name to vertical) {
                val inner = arrangements[name] ?: return@getOrPut 0.0
                val taken = inner.crossings.filterKeys { ending[it] != name }.mapNotNull { (edge, point) ->
                    val side = crossingSide.getValue(edge)
                    if ((side == Side.WEST || side == Side.EAST) == vertical) (if (vertical) point.y else point.x) else null
                }
                val size = if (vertical) inner.height else inner.width
                (0..MAX_LEAN).flatMap { listOf(it * PORT_GAP, -it * PORT_GAP) }.firstOrNull { lean ->
                    val at = size / 2 + lean
                    at in PORT_GAP..(size - PORT_GAP) && taken.none { abs(it - at) < PORT_GAP - EPSILON }
                } ?: 0.0
            }

            private fun port(node: ElkNode, side: Side, x: Double = 0.0, y: Double = 0.0): ElkPort {
                val port = ElkGraphUtil.createPort(node)
                port.setDimensions(0.0, 0.0)
                port.setLocation(x, y)
                port.setProperty(
                    CoreOptions.PORT_SIDE,
                    when (side) {
                        Side.NORTH -> PortSide.NORTH
                        Side.EAST -> PortSide.EAST
                        Side.SOUTH -> PortSide.SOUTH
                        Side.WEST -> PortSide.WEST
                    },
                )
                return port
            }

            private fun connect(hop: Hop) {
                val piece = ElkGraphUtil.createSimpleEdge(shape(hop, hop.from, leaving = true), shape(hop, hop.to, leaving = false))
                if (hop.edge.straight) piece.setProperty(LayeredOptions.PRIORITY_STRAIGHTNESS, STRAIGHT)
                if (hop.edge.short) piece.setProperty(LayeredOptions.PRIORITY_SHORTNESS, SHORT)
                pieces[hop] = piece
                if (hop.labelled && hop.edge.labelWidth > 0) {
                    labels[hop] = ElkGraphUtil.createLabel(hop.edge.id, piece).also {
                        // Where steps follow each other from left to right, a label may reach into
                        // the room its layer keeps to the layers beside it. Beside the border of the
                        // group there is no such room: the label would lie on the border.
                        val reach = if (flow == Flow.RIGHT && !hop.to.onBorder) 2 * LABEL_REACH else 0.0
                        it.setDimensions(maxOf(1.0, hop.edge.labelWidth - reach), hop.edge.labelHeight)
                        it.setProperty(CoreOptions.EDGE_LABELS_PLACEMENT, EdgeLabelPlacement.CENTER)
                    }
                }
            }

            /** What a piece of an edge attaches to at one of its ends. */
            private fun shape(hop: Hop, end: End, leaving: Boolean): ElkConnectableShape {
                val id = hop.edge.id
                if (end.onBorder) {
                    root.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)
                    return crossings.getOrPut(id) { port(root, crossingSide.getValue(id)) }
                }
                val elk = nodes.getValue(end.node!!)
                // An edge that runs on into a group passes where the group's own layout put its crossing.
                val crossing = arrangements[end.node]?.crossings?.get(id)
                return if (crossing != null) port(elk, crossingSide.getValue(id), crossing.x, crossing.y) else place(elk, end.anchor, end.shift, leaving)
            }
        }

        /** Puts the arrangements of all groups together, in the coordinates of the whole graph. */
        private fun assemble(): EngineLayout {
            // Where each group's arrangement starts, and every node as the algorithm sees it and as it is.
            val origins = HashMap<String?, Point>()
            val padded = HashMap<String, Box>()
            val boxes = LinkedHashMap<String, Box>()
            fun place(group: String?, origin: Point) {
                origins[group] = origin
                val arrangement = arrangements.getValue(group)
                val children = if (group == null) graph.nodes else byId.getValue(group).children
                for (child in children) {
                    val box = arrangement.boxes.getValue(child.id).moved(origin.x, origin.y)
                    padded[child.id] = box
                    boxes[child.id] = when {
                        child.point -> box
                        flowOf(group) == Flow.RIGHT -> Box(box.x + PAD, box.y, box.width - 2 * PAD, box.height)
                        else -> Box(box.x, box.y + PAD, box.width, box.height - 2 * PAD)
                    }
                    if (child.children.isNotEmpty()) place(child.id, Point(box.x, box.y))
                }
            }
            place(null, Point(0.0, 0.0))

            val routes = LinkedHashMap<String, EngineRoute>()
            for ((id, pieces) in hopsOf) {
                val points = ArrayList<Point>()
                var label: Box? = null
                for ((group, hop) in pieces) {
                    val origin = origins.getValue(group)
                    val arrangement = arrangements.getValue(group)
                    arrangement.sections[hop].orEmpty().forEach { points += it.moved(origin.x, origin.y) }
                    arrangement.labels[hop]?.let { label = it.moved(origin.x, origin.y) }
                }
                if (points.size >= 2) {
                    val edge = pieces.first().second.edge
                    points[0] = drawnOut(points[0], edge.from, padded.getValue(edge.from))
                    points[points.size - 1] = drawnOut(points[points.size - 1], edge.to, padded.getValue(edge.to))
                }
                routes[id] = EngineRoute(points, label)
            }

            // The padding leaves empty room at both ends of the graph; what is returned starts at the origin.
            val all = Box.around(
                boxes.values + routes.values.mapNotNull { it.label } +
                    routes.values.flatMap { route -> route.points.map { Box(it.x, it.y, 0.0, 0.0) } },
            ) ?: Box(0.0, 0.0, 0.0, 0.0)
            return EngineLayout(
                width = all.width,
                height = all.height,
                boxes = boxes.mapValues { it.value.moved(-all.x, -all.y) },
                routes = routes.mapValues { (_, route) ->
                    EngineRoute(route.points.map { it.moved(-all.x, -all.y) }, route.label?.moved(-all.x, -all.y))
                },
            )
        }

        /** The end of an edge, moved from the padded border of the node [id] to its real one. */
        private fun drawnOut(point: Point, id: String, box: Box): Point = when (if (byId.getValue(id).point) null else flowOf(groupOf[id]?.id)) {
            null -> point
            Flow.RIGHT -> when {
                abs(point.x - box.x) < EPSILON -> Point(point.x + PAD, point.y)
                abs(point.x - box.right) < EPSILON -> Point(point.x - PAD, point.y)
                else -> point
            }
            Flow.DOWN -> when {
                abs(point.y - box.y) < EPSILON -> Point(point.x, point.y + PAD)
                abs(point.y - box.bottom) < EPSILON -> Point(point.x, point.y - PAD)
                else -> point
            }
        }
    }

    private companion object {
        /**
         * How much longer than it is the algorithm takes every node to be, at each end. It must
         * not exceed the padding of a region along its direction, or half the distance of two
         * nodes that follow each other.
         */
        const val PAD = 14.0

        /** How far a label may reach out of its layer, at each end. */
        const val LABEL_REACH = 10.0
        const val EDGE_NODE = 14.0
        const val EDGE_EDGE = 12.0
        const val STUB_SPACING = 14.0

        /** How close to a node a line that leaves a group sideways may pass, and how many places further out it looks for a way. */
        const val STUB_CLEARANCE = 4.0
        const val MAX_STUB_SHIFT = 3

        /** The least distance between two places on the border of a group where different edges attach, and how many such steps the usual place is moved at most. */
        const val PORT_GAP = 14.0
        const val MAX_LEAN = 4
        const val STRAIGHT = 40
        const val SHORT = 20
        const val EPSILON = 0.01
    }
}
