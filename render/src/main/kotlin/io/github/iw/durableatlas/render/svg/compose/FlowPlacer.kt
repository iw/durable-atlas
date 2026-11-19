package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Point
import io.github.iw.durableatlas.render.svg.engine.Anchor
import io.github.iw.durableatlas.render.svg.engine.EngineEdge
import io.github.iw.durableatlas.render.svg.engine.EngineGraph
import io.github.iw.durableatlas.render.svg.engine.EngineLayout
import io.github.iw.durableatlas.render.svg.engine.EngineNode
import io.github.iw.durableatlas.render.svg.engine.EngineRoute
import io.github.iw.durableatlas.render.svg.engine.Flow
import io.github.iw.durableatlas.render.svg.engine.Insets
import io.github.iw.durableatlas.render.svg.engine.LayoutEngine
import io.github.iw.durableatlas.render.svg.engine.Side
import io.github.iw.durableatlas.render.svg.scene.Scene
import io.github.iw.durableatlas.render.svg.scene.SceneCard
import io.github.iw.durableatlas.render.svg.scene.SceneEdge
import io.github.iw.durableatlas.render.svg.scene.SceneItem
import io.github.iw.durableatlas.render.svg.scene.SceneRegion
import kotlin.math.abs
import kotlin.math.max

/** Where the items of one flow went, relative to the top left corner of that flow. */
internal class Placed(
    val width: Double,
    val height: Double,
    val boxes: Map<String, Box>,
    val routes: Map<String, EngineRoute>,
)

/**
 * A step that is only sometimes taken, drawn below the step that decides: `top` leads to
 * `bottom` and to `next`, and `bottom` leads only to `next`. Drawn side by side the pair would
 * take two columns; stacked it takes one, with the skipping edge passing straight on. Two steps
 * whose `next` is the end of a region further out than their own are not stacked.
 *
 * The label of the edge between the two cards is set to the right of its arrow, between the
 * cards. Nothing is kept free for it there: a label that is longer than what the cards leave of
 * that place lies where the badge of the lower card goes, or reaches out of the stack, and
 * [LabelTidier] moves it to wherever else there is room beside the arrow. Where it finds none,
 * the stack is placed again as one that is [roomy].
 *
 * @param roomy whether the stack keeps a place for that label itself: to the left of the arrow,
 *   with as much [room] to the left of the cards as the label takes there beyond them
 */
internal class Stack(val top: SceneCard, val bottom: SceneCard, val inner: SceneEdge, val next: String, roomy: Boolean = false) {
    val id: String = "\u0000stack:${top.id}"
    val width: Double = max(top.width, bottom.width)

    /** Room between the two cards for an arrowhead and, beside it, the label of the edge between them. */
    val gap: Double = GAP + max(0.0, inner.labelHeight - Geometry.LABEL_HEIGHT)
    val height: Double = top.height + gap + bottom.height

    val roomy: Boolean = roomy && inner.label != null

    /** How far the stack reaches to the left of its cards, for the label of the edge between them. */
    val room: Double = if (this.roomy) max(0.0, inner.labelWidth + LABEL_SIDE - width / 2) else 0.0

    init {
        top.width = width
        bottom.width = width
    }

    /**
     * Where the label of the edge between the two cards goes: level with the middle of the room
     * between them, beside the arrow.
     *
     * @param top the place of the upper card
     */
    fun label(top: Box): Box? {
        if (inner.label == null) return null
        val x = if (roomy) top.centerX - LABEL_SIDE - inner.labelWidth else top.centerX + LABEL_SIDE
        return Box(x, top.bottom + (gap - inner.labelHeight) / 2, inner.labelWidth, inner.labelHeight)
    }

    companion object {
        /** The room between the two cards, with a label of one line. */
        const val GAP = 26.0

        /** Distance between the arrow from the upper card to the lower and the label beside it. */
        const val LABEL_SIDE = Geometry.LABEL_OFFSET + 2
        const val IN = "in"
        const val OUT = "out"
        const val NEXT = "next"
    }
}

/**
 * Has the layout engine place the items of a flow, whatever they are nested in, and decides
 * where on a step each of its edges attaches:
 *
 * - Edges usually leave a step in the middle of one side and arrive in the middle of the
 *   opposite one, so the main line of a flow is straight.
 * - Edges between the same two places are spread evenly around that middle, where they arrive
 *   and, if nothing else leaves the step, where they leave. Their labels sit on the outer sides
 *   of the outermost of them. Edges from one step to the end of a region around it start the
 *   same way, but end apart, and each is labelled beside its own line.
 * - Edges that say the same and arrive at the same place meet at a point before it and run on
 *   as one line, labelled once: a trunk.
 * - A region is entered in a place of its own by each trunk and by each other set of edges of
 *   one kind with one label, so edges that say different things do not run together there.
 * - A step whose edges all lead to steps of their own sits in the middle of them, and likewise a
 *   step at which such arms meet again.
 *
 * @param roomy the ids of the edges between the two cards of a [Stack] whose stacks keep room
 *   for the label of that edge
 */
internal class FlowPlacer(private val scene: Scene, private val engine: LayoutEngine, private val roomy: Set<String> = emptySet()) {
    private val stacksByTop = HashMap<String, Stack>()
    private val stacksByBottom = HashMap<String, Stack>()

    /** The region each item lies in. */
    private val regionOf = HashMap<String, String>()

    /** The ids of the regions that are drawn as boxes around their steps. */
    private val regions = HashSet<String>()

    init {
        fun note(item: SceneItem) {
            if (item is SceneRegion) {
                regions += item.id
                item.children.forEach { regionOf[it.id] = item.id; note(it) }
            }
        }
        scene.main.forEach(::note)
        scene.handlers.forEach { it.body.forEach(::note) }
        findStacks()
    }

    /** The regions around an item, innermost first. */
    private fun around(id: String): List<String> = generateSequence(regionOf[id]) { regionOf[it] }.toList()

    /**
     * @param items what to place, in the order that decides ties: earlier items go above or
     *   before later ones
     * @param edges edges to route; those that do not join two of the items are left out
     * @param straight ids of the edges to keep straight in preference to others
     */
    fun place(items: List<SceneItem>, edges: List<SceneEdge>, flow: Flow, straight: Set<String> = emptySet()): Placed {
        val ids = HashSet<String>()
        fun collect(item: SceneItem) {
            ids += item.id
            if (item is SceneRegion) item.children.forEach(::collect)
        }
        items.forEach(::collect)

        val wires = edges.filter { it.from in ids && it.to in ids }.mapNotNull { wire(it, it.id in straight) }
        val trunks = trunks(wires)
        val bundles = bundles(wires, flow)
        val gates = gates(wires, flow)
        var laid = engine.layout(graph(items, wires, trunks, flow))
        // Where the edges into a region come from is only known now. A second run lets them in where they arrive.
        if (gates.count { it.settle(laid) } > 0) laid = engine.layout(graph(items, wires, trunks, flow))

        val boxes = LinkedHashMap<String, Box>()
        val routes = LinkedHashMap<String, EngineRoute>()
        val stacks = stacksByTop.values.associateBy { it.id }
        for ((id, box) in laid.boxes) {
            val stack = stacks[id]
            if (stack == null) {
                if (!id.startsWith(JOIN)) boxes[id] = box
                continue
            }
            // The cards lie at the right of what the engine placed; a stack that keeps room has it to their left.
            val top = Box(box.x + stack.room, box.y, stack.width, stack.top.height)
            val bottom = Box(top.x, top.bottom + stack.gap, stack.width, stack.bottom.height)
            boxes[stack.top.id] = top
            boxes[stack.bottom.id] = bottom
            routes[stack.inner.id] = EngineRoute(listOf(Point(top.centerX, top.bottom), Point(top.centerX, bottom.y)), stack.label(top))
        }

        /** The line of an edge to [to] as the engine drew it, and on across the room a stack keeps to the left of its cards. */
        fun reaching(points: List<Point>, to: String): List<Point> {
            val room = stacks[to]?.room ?: 0.0
            return if (room == 0.0 || points.isEmpty()) points else points + points.last().moved(room, 0.0)
        }
        for (trunk in trunks) {
            // Every edge of a trunk is drawn all the way; from the meeting point on they lie on top of each other.
            val shared = laid.routes.getValue(trunk.id)
            for (wire in trunk.members) {
                val points = Paths.simplified(laid.routes.getValue(wire.id).points + reaching(shared.points, trunk.to))
                routes[wire.id] = EngineRoute(points, shared.label.takeIf { wire === trunk.members.last() })
            }
        }
        for (bundle in bundles) {
            val apart = toEnd(bundle.first())
            val along = flowBetween(bundle.first(), flow)
            val holder = holder(bundle, along)
            val reserved = laid.routes.getValue(bundle[holder].id).label
            bundle.forEachIndexed { slot, wire ->
                val route = laid.routes.getValue(wire.id)
                val points = Paths.simplified(reaching(route.points, wire.to))
                val label = when {
                    wire.scene.label == null -> null
                    // Each of them was given room beside its own line.
                    apart -> route.label
                    reserved == null -> null
                    else -> label(reserved, wire.scene.labelWidth, wire.scene.labelHeight, points, slot - holder, along)
                }
                routes[wire.id] = EngineRoute(points, label)
            }
        }
        val smooth = Kinks.removed(routes, boxes, flow)
        return Placed(laid.width, laid.height, boxes, if (flow == Flow.RIGHT) clearOfTurns(smooth) else smooth)
    }

    /** An edge as the engine is asked to route it. */
    private class Wire(val scene: SceneEdge, val from: String, val to: String, val fromAnchor: String?, val toAnchor: String?, val straight: Boolean) {
        val id: String
            get() = scene.id
        var fromShift = 0.0
        var toShift = 0.0

        /** The width and height of the label the engine is asked to make room for. */
        var room = 0.0
        var roomHeight = 0.0
        var trunk: Trunk? = null
    }

    /**
     * Edges that say the same and arrive at the same place. They meet at a point before it and
     * run on from there as one line, which carries their label once.
     */
    private class Trunk(val id: String, val to: String, val toAnchor: String?, val members: List<Wire>) {
        var toShift = 0.0
    }

    /** The edges that arrive at one region in one place: a trunk, or the edges of one kind with one label. */
    private class Entry(val wires: List<Wire>, val trunk: Trunk?) {
        /** The edge whose route tells where the entry comes from, if it is a single one. */
        val lead: String? = trunk?.id ?: wires.singleOrNull()?.id

        var shift: Double = 0.0
            set(value) {
                field = value
                trunk?.toShift = value
                wires.forEach { it.toShift = value }
            }
    }

    /**
     * The places where edges enter a region: each [Entry] has its own, so that edges that say
     * different things stay apart. The first entry is the one the flow continues through; it
     * enters in the middle of the side, level with where the region is left again.
     *
     * @param flow the direction of the flow the region is a step of
     */
    private class Gate(val region: String, val entries: List<Entry>, val flow: Flow) {
        init {
            // Until it is known where they come from, the others take turns below and above the first.
            val spacing = if (entries.size <= 3) ENTRY_SPACING else ENTRY_GAP
            entries.forEachIndexed { index, entry -> entry.shift = (index + 1) / 2 * spacing * if (index % 2 == 1) 1 else -1 }
        }

        /**
         * Moves each entry to where its edge arrives at the region, as far as the side of the
         * region and the other entries leave room, so that the edge can run in straight.
         *
         * @return whether anything moved
         */
        fun settle(laid: EngineLayout): Boolean {
            val box = laid.boxes[region] ?: return false
            val reach = (if (flow == Flow.RIGHT) box.height else box.width) / 2 - ENTRY_INSET
            if (reach < ENTRY_GAP) return false
            fun along(point: Point) = if (flow == Flow.RIGHT) point.y else point.x
            val wanted = entries.drop(1).map { entry ->
                val points = entry.lead?.let { laid.routes[it]?.points }?.let(Paths::simplified)
                // The stretch before the last turn is the line the edge comes in on.
                val offset = if (points != null && points.size >= 4) along(points[points.size - 3]) - along(points.last()) else 0.0
                entry to entry.shift + offset
            }
            val placed = HashMap<Entry, Double>()
            for (below in listOf(true, false)) {
                val side = wanted.filter { (it.second >= 0) == below }.sortedBy { abs(it.second) }
                var last = 0.0
                val distances = side.map { (_, wish) -> max(abs(wish), last + ENTRY_GAP).also { last = it } }.toMutableList()
                // Pushed back from the end of the side, if they reach past it.
                var limit = reach
                for (index in distances.indices.reversed()) {
                    distances[index] = minOf(distances[index], limit)
                    limit = distances[index] - ENTRY_GAP
                }
                if (distances.isNotEmpty() && distances.first() < ENTRY_GAP - 0.01) return false
                side.forEachIndexed { index, (entry, _) -> placed[entry] = if (below) distances[index] else -distances[index] }
            }
            val moved = placed.any { (entry, shift) -> abs(entry.shift - shift) > 0.5 }
            if (moved) entries.drop(1).forEach { it.shift = placed.getValue(it) }
            return moved
        }
    }

    private fun wire(edge: SceneEdge, straight: Boolean): Wire? {
        if (stacksByBottom[edge.to]?.inner === edge) return null
        val fromTop = stacksByTop[edge.from]
        val fromBottom = stacksByBottom[edge.from]
        val toTop = stacksByTop[edge.to]
        // Of the two ways past a stack, the one that skips the lower card is the one to keep
        // straight: it lies on the line the flow came in on.
        val stack = fromTop?.takeIf { edge.to == it.next } ?: fromBottom
        return Wire(
            scene = edge,
            from = fromTop?.id ?: fromBottom?.id ?: edge.from,
            to = toTop?.id ?: edge.to,
            fromAnchor = if (fromTop != null) Stack.OUT else if (fromBottom != null) Stack.NEXT else null,
            toAnchor = if (toTop != null) Stack.IN else null,
            straight = if (stack != null) fromTop != null else straight,
        )
    }

    /** Whether an edge leads from a step to the end of a region around it. */
    private fun toEnd(wire: Wire): Boolean = wire.scene.to in around(wire.scene.from)

    /**
     * The direction of the flow in which the two ends of an edge follow each other: that of the
     * innermost region both lie in, or [outer] if there is none.
     */
    private fun flowBetween(wire: Wire, outer: Flow): Flow {
        val target = around(wire.scene.to).toSet()
        return around(wire.scene.from).firstOrNull { it in target }?.let(flows::getValue) ?: outer
    }

    /**
     * The place in [bundle] of the edge that asks the engine for room for a label: the one with
     * nothing of the bundle on the side the engine sets labels on. That is the first where the
     * edges run from left to right, with the others below it, and the last where they run
     * downwards, with the others to its left.
     */
    private fun holder(bundle: List<Wire>, along: Flow): Int = if (along == Flow.RIGHT) 0 else bundle.lastIndex

    /**
     * Finds the edges that say the same and arrive at the same place, two or more of them. An
     * edge that runs beside another between the same two places is not among them: the two are
     * told apart by running side by side all the way, which a shared last stretch would undo.
     */
    private fun trunks(wires: List<Wire>): List<Trunk> {
        val beside = wires.groupBy { listOf(it.from, it.fromAnchor, it.to, it.toAnchor) }.filterValues { it.size > 1 }.values.flatten().toSet()
        return wires.filter { it.scene.label != null && !toEnd(it) && it !in beside }
            .groupBy { listOf(it.to, it.toAnchor, it.scene.kind, it.scene.label) }
            .values.filter { it.size > 1 }
            .map { members ->
                Trunk(JOIN + members.first().id, members.first().to, members.first().toAnchor, members).also { trunk -> members.forEach { it.trunk = trunk } }
            }
    }

    /**
     * Groups the edges that run between the same two places. They would lie on top of each
     * other, so they are spread evenly to both sides of the place where a single edge would
     * attach: normal flow first.
     *
     * The engine sets every label on the same side of its edge: above it where steps follow
     * each other from left to right, to its right where they follow each other downwards.
     * Between two edges of such a group there is no room for that, so only the edge that has
     * that side to itself asks for room, for the widest label of the group, and the labels of
     * the others are set on the far side of their own edges afterwards.
     *
     * Edges from one step to the end of a region around it are such a group only where they
     * start. They part on the way and end each in a place of its own, so each asks for the room
     * its own label needs and is labelled beside its own line.
     *
     * @param flow the direction of the flow the edges are placed in, outside every region
     */
    private fun bundles(wires: List<Wire>, flow: Flow): List<List<Wire>> {
        val bundles = wires.filter { it.trunk == null }
            .groupBy { listOf(it.from, it.fromAnchor, it.to, it.toAnchor) }.values
            .map { same -> same.sortedBy { it.scene.kind } }
        val leaving = wires.groupBy { listOf(it.from, it.fromAnchor) }
        for (bundle in bundles) {
            val spacing = if (bundle.size == 2) PAIR_SPACING else BUNDLE_SPACING
            val widest = bundle.maxOf { if (it.scene.label == null) 0.0 else it.scene.labelWidth }
            val highest = bundle.maxOf { if (it.scene.label == null) 0.0 else it.scene.labelHeight }
            // Where other edges leave the step as well, all of them start together and part on the way;
            // spread out, the edges of the bundle would have to cross the others.
            val alone = leaving.getValue(listOf(bundle.first().from, bundle.first().fromAnchor)).size == bundle.size
            val apart = toEnd(bundle.first())
            val holder = holder(bundle, flowBetween(bundle.first(), flow))
            bundle.forEachIndexed { slot, wire ->
                wire.toShift = (slot - (bundle.size - 1) / 2.0) * spacing
                wire.fromShift = if (alone) wire.toShift else 0.0
                wire.room = when {
                    apart -> if (wire.scene.label == null) 0.0 else wire.scene.labelWidth
                    slot == holder -> widest
                    else -> 0.0
                }
                wire.roomHeight = if (apart) wire.scene.labelHeight else highest
            }
        }
        return bundles
    }

    /** Finds the regions that edges enter in more than one place. */
    private fun gates(wires: List<Wire>, flow: Flow): List<Gate> {
        val entering = wires.filter { it.to in regions && it.toAnchor == null && !toEnd(it) }
        return entering.groupBy { it.to }.mapNotNull { (region, arriving) ->
            val entries = arriving.groupBy { it.trunk ?: listOf(it.scene.kind, it.scene.label) }.values.map { Entry(it, it.first().trunk) }
            // Edges that all come from one place are spread already, and evenly.
            val spread = entries.all { it.trunk == null && it.wires.size == 1 } && arriving.map { listOf(it.from, it.fromAnchor) }.distinct().size == 1
            if (entries.size < 2 || spread) return@mapNotNull null
            val first = entries.firstOrNull { entry -> entry.wires.any { it.straight } } ?: entries.first()
            Gate(region, listOf(first) + entries.filter { it !== first }, regionOf[region]?.let(flows::getValue) ?: flow)
        }
    }

    /** The direction in which the steps of each region follow each other. */
    private val flows: Map<String, Flow> = HashMap<String, Flow>().also { map ->
        fun note(item: SceneItem) {
            if (item is SceneRegion) {
                map[item.id] = item.flow
                item.children.forEach(::note)
            }
        }
        scene.main.forEach(::note)
        scene.handlers.forEach { it.body.forEach(::note) }
    }

    private fun graph(items: List<SceneItem>, wires: List<Wire>, trunks: List<Trunk>, flow: Flow): EngineGraph {
        val edges = ArrayList<EngineEdge>()
        val drawn = HashSet<Trunk>()
        for (wire in wires) {
            val trunk = wire.trunk
            edges += EngineEdge(
                id = wire.id,
                from = wire.from,
                to = trunk?.id ?: wire.to,
                fromAnchor = wire.fromAnchor,
                toAnchor = if (trunk == null) wire.toAnchor else null,
                fromShift = wire.fromShift,
                toShift = if (trunk == null) wire.toShift else 0.0,
                labelWidth = wire.room,
                labelHeight = if (wire.room > 0) wire.roomHeight else 0.0,
                straight = wire.straight,
            )
            if (trunk != null && drawn.add(trunk)) {
                edges += EngineEdge(
                    id = trunk.id,
                    from = trunk.id,
                    to = trunk.to,
                    toAnchor = trunk.toAnchor,
                    toShift = trunk.toShift,
                    labelWidth = wire.scene.labelWidth,
                    labelHeight = wire.scene.labelHeight,
                    short = true,
                )
            }
        }

        // Only steps of the outermost level can be moved to the middle of their edges.
        val top = nodes(items, emptyMap()).map { it.id }.toSet()
        val leaving = edges.groupBy { it.from }
        val arriving = edges.groupBy { it.to }
        val kinds = wires.associate { it.id to it.scene.kind }
        fun level(edge: EngineEdge) = edge.from in top && edge.to in top && edge.fromShift == 0.0 && edge.toShift == 0.0
        fun parting(edge: EngineEdge) = kinds[edge.id].let { it == EdgeKind.FLOW || it == EdgeKind.TIMEOUT }
        val fans = top.filter { id ->
            val out = leaving[id].orEmpty()
            out.size in 2..MAX_FAN && out.all { level(it) && parting(it) && arriving.getValue(it.to).size == 1 } &&
                out.map { it.fromAnchor }.distinct().size == 1
        }
        val joins = top.filter { id ->
            val into = arriving[id].orEmpty()
            into.size in 2..MAX_FAN && into.all { level(it) && parting(it) && leaving.getValue(it.from).size == 1 } &&
                into.map { it.toAnchor }.distinct().size == 1
        }
        return EngineGraph(flow, nodes(items, trunks.groupBy { it.to }), edges, fans, joins)
    }

    /**
     * The place of the label of an edge in a bundle for which the engine [reserved] room. The
     * edge that asked for the room has its label there, on the side of the edge where the engine
     * sets labels. The others have theirs level with it, each on the far side of its own edge.
     *
     * @param slot how many places further along the bundle than the edge that asked for the
     *   room the edge runs: below it where the edges run from left to right, and, counted
     *   below zero, to its left where they run downwards
     * @param along the direction the edges of the bundle run in
     */
    private fun label(reserved: Box, width: Double, height: Double, points: List<Point>, slot: Int, along: Flow): Box {
        if (along == Flow.RIGHT) {
            val x = reserved.centerX - width / 2
            // The room lies above the first edge and may be higher than its label, which sits right above the line.
            if (slot == 0) return Box(x, reserved.bottom - height, width, height)
            val line = points.zipWithNext().firstOrNull { (a, b) -> a.y == b.y && reserved.centerX in minOf(a.x, b.x)..maxOf(a.x, b.x) }?.first?.y
                ?: (reserved.bottom + Geometry.LABEL_OFFSET + slot * PAIR_SPACING)
            return Box(x, line + Geometry.LABEL_OFFSET, width, height)
        }
        val y = reserved.centerY - height / 2
        if (slot == 0) return Box(reserved.x, y, width, height)
        val line = points.zipWithNext().firstOrNull { (a, b) -> a.x == b.x && reserved.centerY in minOf(a.y, b.y)..maxOf(a.y, b.y) }?.first?.x
            ?: (reserved.x - Geometry.LABEL_OFFSET + slot * PAIR_SPACING)
        return Box(line - Geometry.LABEL_OFFSET - width, y, width, height)
    }

    /** Whether [edge] is the edge between the two cards of a stack, whose label the stack can keep room for. */
    fun inStack(edge: SceneEdge): Boolean = stacksByBottom[edge.to]?.inner === edge

    /** Whether the steps that [holds] says yes to include both cards of every stack they include one of. A stack cannot be taken apart. */
    fun keepsStacks(holds: (String) -> Boolean): Boolean = stacksByTop.values.all { holds(it.top.id) == holds(it.bottom.id) }

    /** What takes the column that the step [id] lies in: the upper card of its stack, or the step itself. */
    fun columnOf(id: String): String = stacksByBottom[id]?.top?.id ?: id

    /** [ids], and the other card of every stack that one card of is among them. */
    fun withWholeStacks(ids: Set<String>): Set<String> =
        ids + ids.mapNotNull { stacksByTop[it]?.bottom?.id ?: stacksByBottom[it]?.top?.id }

    /**
     * Moves labels off the upright stretches of edges. A label is given a little less room than
     * it takes, so it can come to lie on the place where an edge next to it turns; it is then
     * moved along its own edge, as far as it takes to clear the turn.
     */
    private fun clearOfTurns(routes: Map<String, EngineRoute>): Map<String, EngineRoute> {
        val uprights = routes.values.flatMap { route ->
            route.points.zipWithNext().filter { (a, b) -> a.x == b.x && a.y != b.y }.map { (a, b) -> Triple(a.x, minOf(a.y, b.y), maxOf(a.y, b.y)) }
        }
        return routes.mapValues { (_, route) ->
            val label = route.label ?: return@mapValues route
            val crossing = uprights.filter { (x, top, bottom) ->
                x > label.x - LABEL_CLEARANCE && x < label.right + LABEL_CLEARANCE && top < label.bottom && bottom > label.y
            }
            if (crossing.isEmpty()) return@mapValues route
            val right = crossing.maxOf { it.first } + LABEL_CLEARANCE - label.x
            val left = label.right - (crossing.minOf { it.first } - LABEL_CLEARANCE)
            val shift = if (right <= left) right else -left
            if (abs(shift) > MAX_LABEL_SHIFT) route else EngineRoute(route.points, label.moved(shift, 0.0))
        }
    }

    /**
     * @param joints the trunks that end at each node; the point where the edges of a trunk meet
     *   is a node placed with the one they lead to
     */
    private fun nodes(items: List<SceneItem>, joints: Map<String, List<Trunk>>): List<EngineNode> = items.flatMap { item ->
        val stack = stacksByTop[item.id]
        val node = when {
            item.id in stacksByBottom -> null
            stack != null -> EngineNode(
                id = stack.id,
                width = stack.room + stack.width,
                height = stack.height,
                anchors = listOf(
                    Anchor(Stack.IN, Side.WEST, stack.top.height / 2),
                    Anchor(Stack.OUT, Side.EAST, stack.top.height / 2),
                    Anchor(Stack.NEXT, Side.EAST, stack.top.height + stack.gap + stack.bottom.height / 2),
                ),
            )
            // A title of several lines makes the header that much higher.
            item is SceneRegion && item.children.isNotEmpty() -> EngineNode(
                id = item.id,
                width = item.minWidth,
                height = EMPTY_REGION_HEIGHT + Geometry.titleRoom(item.title.size),
                children = nodes(item.children, joints),
                flow = item.flow,
                padding = REGION_PADDING.copy(top = Geometry.REGION_TOP + Geometry.titleRoom(item.title.size)),
            )
            item is SceneRegion -> EngineNode(item.id, item.minWidth, EMPTY_REGION_HEIGHT + Geometry.titleRoom(item.title.size))
            else -> (item as SceneCard).let { EngineNode(it.id, it.width, it.height) }
        }
        if (node == null) emptyList() else joints[node.id].orEmpty().map { EngineNode(it.id, 0.0, 0.0, point = true) } + node
    }

    /** Looks for stacks among the items that follow each other from left to right. */
    private fun findStacks() {
        val leaving = scene.mainEdges.groupBy { it.from }
        val entering = scene.mainEdges.groupBy { it.to }
        val elsewhere = (scene.loose + scene.wakes + scene.cancels).flatMap { listOf(it.from, it.to) }.toSet()

        fun scan(siblings: List<SceneItem>) {
            val cards = siblings.filterIsInstance<SceneCard>().associateBy { it.id }
            for (bottom in cards.values) {
                val incoming = entering[bottom.id].orEmpty().singleOrNull() ?: continue
                val outgoing = leaving[bottom.id].orEmpty().singleOrNull() ?: continue
                val top = cards[incoming.from] ?: continue
                val skips = leaving[top.id].orEmpty().any { it !== incoming && it.to == outgoing.to }
                val taken = top.id in stacksByTop || top.id in stacksByBottom || bottom.id in stacksByTop
                // Edges to the end of a region further out leave the region of the two cards at once, each
                // from its own card: as one node, the two could not be told apart there.
                val leaves = outgoing.to in around(top.id).drop(1)
                if (!skips || taken || leaves || outgoing.to == top.id || bottom.id in elsewhere) continue
                val stack = Stack(top, bottom, incoming, outgoing.to, roomy = incoming.id in roomy)
                stacksByTop[top.id] = stack
                stacksByBottom[bottom.id] = stack
            }
        }

        fun descend(items: List<SceneItem>) {
            for (item in items) {
                if (item !is SceneRegion) continue
                if (item.flow == Flow.RIGHT) scan(item.children)
                descend(item.children)
            }
        }
        scan(scene.main)
        descend(scene.main)
    }

    /**
     * Of the edges that leave each node, the one to keep straight: the first that is normal flow
     * and leads to a step rather than to the end of the region around it. A node that normal
     * flow does not leave has none; the edges of a failure may bend.
     */
    fun firstInside(edges: List<SceneEdge>): Set<String> =
        edges.groupBy { it.from }.values.mapNotNull { leaving ->
            val flow = leaving.filter { it.kind == EdgeKind.FLOW }
            (flow.firstOrNull { it.to !in around(it.from) } ?: flow.firstOrNull())?.id
        }.toSet()

    companion object {

        private val REGION_PADDING = Insets(Geometry.REGION_TOP, Geometry.REGION_SIDE, Geometry.REGION_BOTTOM, Geometry.REGION_SIDE)
        private const val EMPTY_REGION_HEIGHT = Geometry.REGION_TOP + Geometry.REGION_BOTTOM

        /** Prefix of the ids of the points where the edges of a trunk meet. */
        private const val JOIN = "\u0000join:"

        /** Distance between two edges that run between the same two places, and between more than two. */
        private const val PAIR_SPACING = 28.0
        private const val BUNDLE_SPACING = 18.0

        /** Distance between the places where edges enter a region, the least it may be, and how far they stay from its corners. */
        private const val ENTRY_SPACING = 28.0
        private const val ENTRY_GAP = 14.0
        private const val ENTRY_INSET = 18.0

        /** With more edges than this, a step is not moved to the middle of them. */
        private const val MAX_FAN = 3

        /** The least distance between a label and the upright stretch of an edge, and the most a label is moved to keep it. */
        private const val LABEL_CLEARANCE = 4.0
        private const val MAX_LABEL_SHIFT = 28.0
    }
}
