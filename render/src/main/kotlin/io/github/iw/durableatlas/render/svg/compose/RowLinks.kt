package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Point
import io.github.iw.durableatlas.render.svg.scene.SceneEdge
import kotlin.math.min

/**
 * Draws the edges of the main flow that lead from one row to a later one: the connector of two
 * rows, and whatever else crosses them, such as the edges of earlier steps to a failure handler
 * further down. Such edges run in the space between rows, so that space is as tall as the lines
 * in it need.
 *
 * Edges that say the same and lead to the same step are one bundle: they leave a step in one
 * place, run on one line and arrive in one place, with one label. Edges that say different
 * things never share a line, whatever they lead to.
 *
 * @param entries the connectors, one for every row but the first
 * @param wakeRows the rows that edges from the handlers lead to, around the rows above them
 */
internal class RowLinks(
    private val sheet: Sheet,
    private val entries: List<SceneEdge>,
    edges: List<SceneEdge>,
    rows: Int,
    private val wakeRows: Set<Int>,
) {
    /** The edges that cross rows without being a connector. */
    private val across: List<SceneEdge> =
        edges.filter { edge -> entries.none { it === edge } && sheet.rowAt(edge.from) != sheet.rowAt(edge.to) }

    /** The steps that connectors lead to. */
    private val entryTargets: Set<String> = entries.map { sheet.topOf.getValue(it.to) }.toSet()

    /** For the space above each row, the lines set aside in it for edges that cross rows, by what they are for. */
    private val gutters: List<MutableMap<String, Int>> = List(rows) { LinkedHashMap() }

    /** The bundles that reach their step by going around rows, numbered, so that each gets a line of its own at the side. */
    private val detours = LinkedHashMap<String, Int>()

    /** How many places each step has been left at so far by edges to later rows, and the place each bundle leaves it at. */
    private val exits = HashMap<String, Int>()
    private val exitOf = HashMap<Pair<String, String>, List<Point>>()

    /** The bundles that arrive at each step, in the order they were met. Each arrives in a place of its own. */
    private val arrivals = LinkedHashMap<String, MutableList<String>>()

    /** The bundles whose label is written. */
    private val said = HashSet<String>()

    init {
        across.forEach(::reserve)
    }

    /** What the edges of one bundle have in common: where they lead and what they say. */
    private fun bundle(edge: SceneEdge): String = "${edge.to}\u0000${edge.kind}\u0000${edge.label.orEmpty()}"

    /**
     * How much room there has to be above [row]: for the lines between the rows and, where an
     * edge from a handler passes there as well, for a line of its own below their labels. Labels
     * of more than one line take that much more: the connector's above the lines, where it drops
     * out of the row before, and those of the other edges below the lines, where they arrive.
     */
    fun gap(row: Int): Double =
        Geometry.ROW_GAP + connectorRoom(row) + arrivalRoom(row) + gutters[row].size * LANE_STEP +
            if (gutters[row].isNotEmpty() && row in wakeRows) WAKE_ROOM else 0.0

    /** How much higher than one line the label of [edge] is. */
    private fun extra(edge: SceneEdge): Double = if (edge.label == null) 0.0 else edge.labelHeight - Geometry.LABEL_HEIGHT

    /** The room the label of the connector into [row] takes beyond one line. */
    private fun connectorRoom(row: Int): Double = entries.getOrNull(row - 1)?.let(::extra) ?: 0.0

    /** The room the labels of the other edges that arrive in [row] take beyond one line. */
    private fun arrivalRoom(row: Int): Double = across.filter { sheet.rowAt(it.to) == row && sheet.rowAt(it.from)!! < row }.maxOfOrNull(::extra) ?: 0.0

    /** Draws everything, once the rows are in place. Edges that lead back up are returned, for the caller to draw. */
    fun draw(): List<SceneEdge> {
        entries.forEachIndexed { index, edge -> connect(edge, index) }
        val (down, up) = across.partition { sheet.rowAt(it.to)!! > sheet.rowAt(it.from)!! }
        down.forEach(::cross)
        return up
    }

    /**
     * Sets aside room for an edge that crosses rows without being a connector: a line of its own,
     * shared with the other edges to the same step, in the space above the row it leads to, and
     * one below the row it leaves if it has to go around rows in between.
     */
    private fun reserve(edge: SceneEdge) {
        val from = sheet.rowAt(edge.from)!!
        val to = sheet.rowAt(edge.to)!!
        if (to <= from) return
        val bundle = bundle(edge)
        gutters[to].getOrPut("in $bundle") { gutters[to].size }
        arrivals.getOrPut(edge.to) { ArrayList() }.let { if (bundle !in it) it += bundle }
        if (to > from + 1) {
            gutters[from + 1].getOrPut("out $bundle") { gutters[from + 1].size }
            detours.getOrPut(bundle) { detours.size }
        }
    }

    private fun lane(row: Int, key: String): Double =
        sheet.rows[row - 1].bottom + Geometry.ROW_CONNECTOR_DROP + connectorRoom(row) + (gutters[row].getValue(key) + 1) * LANE_STEP

    /**
     * Draws the edge that leads from row [above] into the next one: down from its source, along
     * the space between the rows, and down again into its target.
     */
    private fun connect(edge: SceneEdge, above: Int) {
        val target = sheet.boxes.getValue(sheet.topOf.getValue(edge.to))
        val lane = sheet.rows[above].bottom + Geometry.ROW_CONNECTOR_DROP + connectorRoom(above + 1)
        val start = exit(sheet.topOf.getValue(edge.from), above, edge.id)
        val points = start + listOf(
            Point(start.last().x, lane),
            Point(target.centerX, lane),
            Point(target.centerX, target.y - Geometry.EDGE_END_GAP),
        )
        sheet.reach(points)
        val label = edge.label?.let {
            // Beside the stretch that drops out of the row.
            val from = start.last().y
            val middle = from + min(LABEL_DROP + extra(edge) / 2, (lane - from) / 2)
            Box(start.last().x + Geometry.LABEL_OFFSET + 2, middle - edge.labelHeight / 2, edge.labelWidth, edge.labelHeight)
        }
        sheet.add(edge, Paths.polyline(Paths.simplified(points)), label)
    }

    /**
     * Draws an edge that leads to a later row and is not the connector of the two: down from its
     * source into the space below its row, along the line set aside for its target, and into the
     * target. If rows lie in between, it goes around them at the side.
     */
    private fun cross(edge: SceneEdge) {
        val fromRow = sheet.rowAt(edge.from)!!
        val toRow = sheet.rowAt(edge.to)!!
        val target = sheet.boxes.getValue(edge.to)
        val obstacles = sheet.mainCards().filter { (id, box) -> id != edge.to && !target.contains(box) }.values
        val bundle = bundle(edge)
        val together = arrivals.getValue(edge.to)
        val approach = Approach.to(sheet, edge.to, target, together.size, obstacles, sheet.mainHeaders(), toRow)
        // Next to the connector, if that arrives here too.
        val aside = if (edge.to in entryTargets) min(ARRIVAL_ASIDE, target.width / 4) else 0.0
        val goal = approach.channel ?: (approach.arrivals[together.indexOf(bundle)] + aside)

        val start = exit(sheet.topOf.getValue(edge.from), fromRow, bundle)
        val drop = start.last().x
        val arrival = lane(toRow, "in $bundle")
        val points = ArrayList(start)
        if (toRow == fromRow + 1) {
            points += Point(drop, arrival)
        } else {
            // Every edge of the bundle goes around on the side its step is nearer to, so they run together.
            val departure = lane(fromRow + 1, "out $bundle")
            val step = (detours.getValue(bundle) + 1) * LANE_STEP
            val left = sheet.leftMargin - step
            val right = sheet.rightMargin + step
            val side = if (goal - left <= right - goal) left else right
            points += listOf(Point(drop, departure), Point(side, departure), Point(side, arrival))
        }
        points += if (approach.channel == null) {
            listOf(Point(goal, arrival), Point(goal, target.y - Geometry.EDGE_END_GAP))
        } else {
            listOf(Point(goal, arrival), Point(goal, target.centerY), approach.sideEnd(target))
        }
        sheet.reach(points)
        // The edges of a bundle are labelled once, where they arrive.
        val label = edge.label?.takeIf { said.add(bundle) }?.let {
            Box(goal + Geometry.LABEL_OFFSET + 2, arrival + ARRIVAL_LABEL_DROP, edge.labelWidth, edge.labelHeight)
        }
        sheet.add(edge, Paths.polyline(Paths.simplified(points)), label)
    }

    /**
     * Where the edges called [what] leave the step [sourceId] for a later row: at the bottom, in
     * a place of their own. If something lies below, they leave at the side instead and go down
     * where the way is free.
     */
    private fun exit(sourceId: String, row: Int, what: String): List<Point> = exitOf.getOrPut(sourceId to what) {
        val source = sheet.boxes.getValue(sourceId)
        val slot = exits.merge(sourceId, 1, Int::plus)!! - 1
        val shift = (slot + 1) / 2 * EXIT_STEP * if (slot % 2 == 1) 1 else -1
        val wanted = (source.centerX + shift).coerceIn(source.x + EXIT_INSET, source.right - EXIT_INSET)
        val others = sheet.rowItems(row, sourceId)
        // The labels of edges that hang below the step are in the way as well.
        val below = Box(source.x, source.bottom, source.width, sheet.rows[row].bottom - source.bottom)
        val labels = sheet.edges.mapNotNull { it.label?.box }.filter { it.intersects(below) }

        fun clear(x: Double) = others.none { it.y >= source.y && x in (it.x - CLEARANCE)..(it.right + CLEARANCE) } &&
            labels.none { x in (it.x - LABEL_CLEARANCE)..(it.right + LABEL_CLEARANCE) }
        val places = listOf(wanted) + EXIT_PLACES.map { (source.x + source.width * it).coerceIn(source.x + EXIT_INSET, source.right - EXIT_INSET) }
        places.firstOrNull(::clear)?.let {
            return@getOrPut listOf(Point(it, source.bottom))
        }
        // Past whatever is in the way: the nearest free line to the right that can be reached without crossing a step.
        val beside = (listOf(source.right + Geometry.ROW_SPACING / 2) + others.filter { it.y >= source.y }.map { it.right + CLEARANCE + 4 })
            .filter { it > source.right && clear(it) }
            .filter { x -> others.none { it.y < source.centerY && it.bottom > source.centerY && it.x < x && it.right > source.right } }
            .minOrNull()
        if (beside != null) {
            listOf(Point(source.right, source.centerY), Point(beside, source.centerY))
        } else {
            listOf(Point(wanted, source.bottom))
        }
    }

    private companion object {
        /** How close a line that passes a step may come to it, and to a label. */
        const val CLEARANCE = 8.0
        const val LABEL_CLEARANCE = 4.0

        /** Where along the bottom of a step an edge leaves it if the usual place is taken: these parts of its width. */
        val EXIT_PLACES = listOf(0.25, 0.75, 0.1, 0.9)

        /** Room above a row for a line from the handlers, where other lines between rows end there too. */
        const val WAKE_ROOM = 18.0

        /** Distance between two lines that run side by side between rows. */
        const val LANE_STEP = 9.0

        /** How far below its source the label of a connector sits. */
        const val LABEL_DROP = 33.0

        /** Distance between the places where two edges to later rows leave one step, and how far from its corners they stay. */
        const val EXIT_STEP = 18.0
        const val EXIT_INSET = 14.0

        /** How far from the connector an edge arrives that crosses rows to the same step. */
        const val ARRIVAL_ASIDE = 24.0

        /** How far below the line it arrives on the label of such an edge sits. */
        const val ARRIVAL_LABEL_DROP = 6.0
    }
}
