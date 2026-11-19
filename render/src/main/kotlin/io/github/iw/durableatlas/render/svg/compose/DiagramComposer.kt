package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.render.limit.Deadline
import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Point
import io.github.iw.durableatlas.render.svg.PositionedGraph
import io.github.iw.durableatlas.render.svg.PositionedNode
import io.github.iw.durableatlas.render.svg.PositionedRegion
import io.github.iw.durableatlas.render.svg.engine.Flow
import io.github.iw.durableatlas.render.svg.engine.LayoutEngine
import io.github.iw.durableatlas.render.svg.rounded
import io.github.iw.durableatlas.render.svg.scene.CardTexts
import io.github.iw.durableatlas.render.svg.scene.Scene
import io.github.iw.durableatlas.render.svg.scene.SceneBuilder
import io.github.iw.durableatlas.render.svg.scene.SceneCard
import io.github.iw.durableatlas.render.svg.scene.SceneEdge
import io.github.iw.durableatlas.render.svg.scene.SceneHandler
import io.github.iw.durableatlas.render.svg.scene.SceneItem
import io.github.iw.durableatlas.render.svg.scene.SceneRegion
import io.github.iw.durableatlas.render.svg.text.TextFitter
import kotlin.math.max

/**
 * Arranges a whole diagram: the main flow in rows, the band of handlers above it, and the edges
 * the layout engine does not route, which are the edges between rows, the `wakes` edges from
 * the band and from the start of the workflow method, and the `cancels` edges back to what a
 * cancelled scope holds.
 *
 * Labels are given their final places last, when everything else lies where it stays (see
 * [LabelTidier]). The label of the edge between the two cards of a [Stack] has no room of its
 * own, and now and then none is left for it: it then lies on what is next to the stack, or
 * where the badge of the lower card goes. Such a stack is given room for its label and the
 * diagram is arranged again. That is the only case in which a diagram is arranged more than
 * once, and no diagram without such a label is drawn differently for it. With room in one stack
 * the rows can break elsewhere and leave another stack without, which then gets room as well;
 * after [MOST] arrangements the diagram is taken as it is.
 *
 * @param wrapWidth the width above which the main flow is broken into rows; zero for never
 * @param rows how the breaks between the rows are found; see [RowWrapper]
 * @param deadline asked between the steps of the work whether to go on
 */
internal class DiagramComposer(
    private val scene: Scene,
    private val engine: LayoutEngine,
    private val fitter: TextFitter,
    private val wrapWidth: Double,
    private val rows: RowSearch = RowSearch.NEAR,
    private val deadline: Deadline = Deadline.none(),
) {
    /** How many times the diagram has been arranged. */
    var arrangements: Int = 0
        private set

    fun compose(): PositionedGraph {
        val roomy = LinkedHashSet<String>()
        while (true) {
            val draft = Draft(scene, engine, fitter, wrapWidth, rows, deadline, roomy.toSet())
            val graph = draft.compose()
            arrangements++
            // A stack that has room keeps it, so every round but the last gives room to one more at least.
            if (!roomy.addAll(draft.cramped) || arrangements == MOST) return graph
        }
    }

    private companion object {
        /** How many times a diagram is arranged at most. Two is the rule where a label lacks room, three is seldom. */
        const val MOST = 4
    }
}

/**
 * One arrangement of a diagram.
 *
 * @param roomy the ids of the edges between the two cards of the stacks that keep room for the label of that edge
 */
private class Draft(
    private val scene: Scene,
    engine: LayoutEngine,
    private val fitter: TextFitter,
    wrapWidth: Double,
    rows: RowSearch,
    private val deadline: Deadline,
    roomy: Set<String>,
) {
    private val placer = FlowPlacer(scene, engine, roomy)
    private val wrapper = RowWrapper(scene, placer, wrapWidth, rows, deadline)
    private val sheet = Sheet(scene, wrapper.topOf)

    /**
     * The ids of the edges between the two cards of a stack whose labels were left lying on
     * something, for want of a place beside their arrows. Known once the diagram is composed.
     */
    var cramped: Set<String> = emptySet()
        private set

    fun compose(): PositionedGraph {
        val rows = wrapper.rows()
        rows.forEachIndexed { index, row -> row.items.forEach { sheet.assign(it, index) } }
        val wakeRows = scene.wakes.mapNotNull { sheet.rowAt(it.to) }.filter { it > 0 }.toSet()
        val links = RowLinks(sheet, rows.mapNotNull { it.entry }, scene.mainEdges, rows.size, wakeRows)
        val cancels = CancelRouter(scene, sheet, SceneBuilder.labelWidth(fitter, CardTexts.CANCELS))

        val columns = scene.handlers.map(::column)
        val bandHeight = if (columns.isEmpty()) 0.0 else Geometry.BAND_TOP + columns.maxOf { it.placed.height } + Geometry.BAND_BOTTOM
        var top = if (columns.isEmpty()) 0.0 else bandHeight + Geometry.BAND_GAP
        rows.forEachIndexed { index, row ->
            // What a cancelled scope reaches is drawn below the row both lie in, which takes room there.
            if (index > 0) top += links.gap(index) + cancels.room(index - 1)
            place(row.placed, 0.0, top, scene.mainEdges)
            sheet.rows += Box(0.0, top, row.placed.width, row.placed.height)
            top += row.placed.height
        }
        if (columns.isNotEmpty()) placeBand(columns, bandHeight)
        deadline.check()
        val back = links.draw()
        WakeRouter(scene, sheet, SceneBuilder.labelWidth(fitter, CardTexts.WAKES)).draw()
        cancels.draw()
        // The edges with which one place cancels several steps say so once.
        val said = HashSet<String>()
        (back + scene.loose).forEach { drawLoose(it, labelled = it.kind != EdgeKind.CANCELS || said.add(it.from)) }
        val cards = cards()
        val regions = regions()
        // An overlay may put a badge on any card and on any region.
        val corners = (cards + regions.map { it.box }).map { Geometry.badge(it, Geometry.BADGE_ROOM) }
        val tidy = LabelTidier.tidy(sheet.edges, cards, listOfNotNull(band()) + regions, corners, deadline)
        cramped = tidy.cramped.map { sheet.drawn[it] }.filter(placer::inStack).mapTo(LinkedHashSet()) { it.id }
        sheet.edges.clear()
        sheet.edges += tidy.edges
        // A label that was moved may lie further out than anything did before.
        tidy.edges.forEach { edge -> edge.label?.let { sheet.reach(it.box) } }
        return positioned()
    }

    /** Records where the items and edges of [placed] are, once it is moved to its place in the diagram. */
    private fun place(placed: Placed, x: Double, y: Double, routed: List<SceneEdge>) {
        placed.boxes.forEach { (id, box) -> sheet.boxes[id] = box.moved(x, y) }
        for (edge in routed) {
            val route = placed.routes[edge.id] ?: continue
            val points = Paths.shortened(route.points.map { it.moved(x, y) }, Geometry.EDGE_END_GAP)
            sheet.add(edge, Paths.polyline(points), route.label?.takeIf { edge.label != null }?.moved(x, y))
        }
    }

    // --- handlers ---

    /** A handler's card with what it runs below it, laid out on its own. */
    private class Column(val handler: SceneHandler, val placed: Placed) {
        private val labels: List<Box> = handler.edges.filter { it.label != null }.mapNotNull { placed.routes[it.id]?.label }

        /** How far the labels of its edges reach beyond its cards, on the left and on the right. */
        val before: Double = max(0.0, -(labels.minOfOrNull { it.x } ?: 0.0))
        val after: Double = max(0.0, (labels.maxOfOrNull { it.right } ?: 0.0) - placed.width)

        /**
         * What the ways into the end of the handler take up, where that end is drawn: they pass
         * the steps in between at the side, with their labels. Null for a handler whose end is not drawn.
         */
        val detour: Box? = run {
            val ends = handler.body.filterIsInstance<SceneCard>().filter { it.kind == CardTexts.END }.mapTo(HashSet()) { it.id }
            val routes = handler.edges.filter { it.to in ends }.mapNotNull { placed.routes[it.id] }
            Box.around(routes.flatMap { route -> route.points.map { Box(it.x, it.y, 0.0, 0.0) } + listOfNotNull(route.label) })
        }
    }

    private fun column(handler: SceneHandler): Column =
        Column(handler, placer.place(listOf<SceneItem>(handler.card) + handler.body, handler.edges, Flow.DOWN, placer.firstInside(handler.edges)))

    /**
     * Puts the handlers side by side into the band above the main flow, below its header as the
     * steps of a region are, so that the band is no larger than what it holds. Those that wake
     * something go where they are nearest to it, in the order of what they wake; the others
     * take the side that leaves them there.
     */
    private fun placeBand(columns: List<Column>, height: Double) {
        val wakers = scene.wakes.groupBy { it.from }
        val content = sheet.rows.maxOfOrNull { it.right } ?: 0.0

        /** Where a handler would like to be: above what it wakes. */
        fun wish(column: Column): Double? =
            wakers[column.handler.card.id]?.mapNotNull { sheet.boxes[it.to]?.centerX }?.takeIf { it.isNotEmpty() }?.average()

        val waking = columns.filter { wish(it) != null }.sortedBy { wish(it) }
        val quiet = columns.filter { wish(it) == null }
        val target = waking.mapNotNull(::wish).takeIf { it.isNotEmpty() }?.average()
        val ordered = if (target == null || target >= content / 2) quiet + waking else waking + quiet

        val offsets = HashMap<Column, Double>()
        var cursor = Geometry.BAND_SIDE
        for (column in ordered) {
            // A handler that runs something and wakes something has the edge to what it wakes run down beside its steps.
            val channel = if (column.handler.body.isNotEmpty() && column.handler.card.id in wakers) Geometry.BAND_CHANNEL else 0.0
            // A handler one of whose steps is woken has the edges to that step, and their label, at the right of its steps.
            val woken = if (WakeTargets.holdsWoken(scene, column.handler)) {
                Geometry.BAND_WAKE_CHANNEL + Geometry.LABEL_OFFSET + SceneBuilder.labelWidth(fitter, CardTexts.WAKES) - channel
            } else {
                0.0
            }
            offsets[column] = cursor + channel + column.before
            cursor += column.before + column.placed.width + column.after + 2 * channel + max(0.0, woken) + Geometry.BAND_CARD_GAP
        }
        // The band is as wide as its handlers, and never narrower than its header.
        val width = max(cursor - Geometry.BAND_CARD_GAP + Geometry.BAND_SIDE, SceneBuilder.headerWidth(fitter, CardTexts.HANDLERS))

        val x = if (target == null) {
            max(0.0, (content - width) / 2)
        } else {
            val middle = waking.map { offsets.getValue(it) + it.placed.boxes.getValue(it.handler.card.id).centerX }.average()
            (target - middle).coerceIn(0.0, max(0.0, content - width))
        }
        sheet.band = Box(x, 0.0, width, height)
        for (column in ordered) {
            place(column.placed, x + offsets.getValue(column), Geometry.BAND_TOP, column.handler.edges)
            column.detour?.let { sheet.detours[column.handler.card.id] = it.moved(x + offsets.getValue(column), Geometry.BAND_TOP) }
        }
    }

    /**
     * Draws an edge that no layout routes, such as one between what a handler runs and the main
     * flow: as a curve from the one to the other.
     */
    private fun drawLoose(edge: SceneEdge, labelled: Boolean = true) {
        val from = sheet.boxes[edge.from] ?: return
        val to = sheet.boxes[edge.to] ?: return
        val start = Point(from.centerX, from.bottom)
        val end = if (to.y >= from.bottom) Point(to.centerX, to.y - Geometry.EDGE_END_GAP) else Point(to.centerX, to.bottom + Geometry.EDGE_END_GAP)
        val samples = Paths.dropSamples(start, end)
        val middle = samples[samples.size / 2]
        val label = edge.label?.takeIf { labelled }?.let {
            Box(middle.x + Geometry.LABEL_OFFSET, middle.y - edge.labelHeight / 2, edge.labelWidth, edge.labelHeight)
        }
        sheet.add(edge, Paths.drop(start, end).toString(), label)
    }

    /** The boxes of all cards and pills, in the main flow and in the band. */
    private fun cards(): List<Box> {
        val cards = ArrayList<Box>()
        fun visit(item: SceneItem) {
            when (item) {
                is SceneCard -> sheet.boxes[item.id]?.let(cards::add)
                is SceneRegion -> item.children.forEach(::visit)
            }
        }
        scene.main.forEach(::visit)
        scene.handlers.forEach { handler ->
            visit(handler.card)
            handler.body.forEach(::visit)
        }
        return cards
    }

    /** The regions, in the main flow and in what the handlers run, each after those it lies in. */
    private fun regions(): List<LabelTidier.Frame> {
        val regions = ArrayList<LabelTidier.Frame>()
        fun visit(item: SceneItem) {
            if (item !is SceneRegion) return
            sheet.boxes[item.id]?.let { regions += frame(it, item.minWidth, item.title.size) }
            item.children.forEach(::visit)
        }
        scene.main.forEach(::visit)
        scene.handlers.forEach { it.body.forEach(::visit) }
        return regions
    }

    private fun band(): LabelTidier.Frame? = sheet.band?.let { frame(it, SceneBuilder.headerWidth(fitter, CardTexts.HANDLERS)) }

    /**
     * @param headerWidth the width that the icon and the title of the box take, with the room on both sides of them
     * @param lines the number of lines the title takes
     */
    private fun frame(box: Box, headerWidth: Double, lines: Int = 1): LabelTidier.Frame =
        LabelTidier.Frame(box, Geometry.header(box, headerWidth, lines))

    // --- result ---

    private fun positioned(): PositionedGraph {
        val regions = ArrayList<PositionedRegion>()
        val nodes = ArrayList<PositionedNode>()
        fun visit(item: SceneItem, parent: String?, handler: Boolean) {
            val box = sheet.boxes[item.id] ?: return
            when (item) {
                is SceneRegion -> {
                    regions += PositionedRegion(item.id, item.kind, item.title.joinToString(" "), box, parent, item.title.takeIf { it.size > 1 }.orEmpty())
                    item.children.forEach { visit(it, item.id, false) }
                }
                is SceneCard -> nodes += PositionedNode(
                    id = item.id,
                    kind = item.kind,
                    shape = item.shape,
                    box = box,
                    kindLabel = item.kindLabel,
                    name = item.name,
                    parent = parent,
                    handler = handler,
                    nodes = item.nodes,
                    entryPoints = item.entryPoints,
                )
            }
        }
        scene.main.forEach { visit(it, null, false) }
        scene.handlers.forEach { handler ->
            visit(handler.card, null, true)
            handler.body.forEach { visit(it, null, false) }
        }

        // Everything is moved so that the top left corner of what is drawn is the origin.
        val all = sheet.bounds()
        val dx = -all.x
        val dy = -all.y
        val placedRegions = regions.map { it.copy(box = it.box.moved(dx, dy).rounded()) }
        return PositionedGraph(
            width = all.width.rounded(),
            height = all.height.rounded(),
            band = sheet.band?.moved(dx, dy)?.rounded(),
            regions = placedRegions,
            nodes = nodes.map { it.copy(box = it.box.moved(dx, dy).rounded()) },
            edges = sheet.edges.map { edge ->
                edge.copy(
                    path = PathData.moved(edge.path, dx, dy),
                    label = edge.label?.let { label ->
                        val box = label.box.moved(dx, dy).rounded()
                        // The innermost region the middle of the label lies in; regions are listed outermost first.
                        val middle = Point(box.centerX, box.centerY)
                        label.copy(box = box, inside = placedRegions.lastOrNull { it.box.contains(middle) }?.id)
                    },
                )
            },
            rows = sheet.rows.map { it.moved(dx, dy).rounded() },
        )
    }
}
