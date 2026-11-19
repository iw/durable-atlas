package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.render.limit.Deadline
import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Point
import io.github.iw.durableatlas.render.svg.PositionedEdge
import io.github.iw.durableatlas.render.svg.rounded
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Moves the labels of edges off whatever they have come to lie on. The parts of a diagram are
 * arranged one after the other: each region on its own, then the row around it, then the edges
 * between rows, then the edges from the handlers. So a label that was clear of everything where
 * it was set can end up under a line that was drawn later, on the border of the box it was set
 * in, or where an overlay will draw a badge.
 *
 * A label is in a good place if it lies on nothing: no card, no other label, no line, its own
 * included, no border or header of a region or of the band of the handlers, and no corner where
 * a badge goes. It must also sit beside its edge, and in the region that the stretch of the edge
 * beside it runs in. A label that speaks for all the edges that wake one step, or for all that
 * one cancelled scope reaches, may sit between two of them instead.
 *
 * A label in any other place is slid along its own edge, or put on the other side of it, to the
 * nearest good place. If there is none close by, it is set beside whichever straight stretch of
 * its edge has one, as near to where it was as can be. If there is none at all, it stays where
 * it is, and if it lies on something there, it is reported as [Tidy.cramped]: nothing that
 * moves labels alone can help it, only an arrangement that leaves it room.
 */
internal object LabelTidier {
    /** How near a label may come to a card, a line that is not its own, another label, a header, or a border from inside. */
    private const val CLEARANCE = 2.0

    /** How much of that a place may lack, having been rounded. */
    private const val ROUNDING = 0.1

    /** How far a label is slid at most, and in what steps. */
    private const val REACH = 96.0
    private const val STEP = 8.0

    /** Distance between the points a line is checked at. */
    private const val GRAIN = 4.0

    /** How far from its edge a label may sit and still be read as belonging to it. */
    private const val FAR = 2 * Geometry.LABEL_OFFSET + 2

    /** How much further from a label than its nearest point a point of its edge may be and still count as beside it. */
    private const val NEAR = 1.0

    /** How much further than a distance asks for points are looked at, so that none is missed for a rounded sum. */
    private const val MARGIN = 1.0

    /**
     * A box with a border and a header that labels keep off: a region, or the band of the handlers.
     *
     * @param header what the icon and the title in its top left corner take up
     */
    class Frame(val box: Box, val header: Box)

    /**
     * What became of the labels of a diagram.
     *
     * @param edges the edges, with labels moved where that helped
     * @param cramped the places in [edges] of those whose label has no good place and lies on
     *   something where it is: a card, another label, the border or the header of a region or
     *   of the band, or a corner where a badge goes
     */
    class Tidy(val edges: List<PositionedEdge>, val cramped: Set<Int>)

    /**
     * @param cards the boxes of all cards and pills
     * @param frames the regions and the band of the handlers, each after those it lies in
     * @param corners the places where an overlay may draw a badge
     * @param deadline asked before each label whether to go on
     * @return [edges] with labels moved where that helps, and which of them are left lying on something
     */
    fun tidy(
        edges: List<PositionedEdge>,
        cards: Collection<Box>,
        frames: List<Frame>,
        corners: Collection<Box>,
        deadline: Deadline = Deadline.none(),
    ): Tidy {
        val lines = edges.map { dense(PathData.points(it.path)) }
        val points = Points(lines)
        val labels = edges.map { it.label?.box }.toMutableList()
        // The labels that should move and have nowhere to go.
        val kept = ArrayList<Int>()
        for (index in edges.indices) {
            val box = labels[index] ?: continue
            deadline.check()
            val own = lines[index]
            if (own.isEmpty()) continue
            val others = labels.filterIndexed { other, _ -> other != index }.filterNotNull()
            // The edges the label speaks for: its own, and those that wake the same step and have none.
            val speaking = edges.indices.filter { it == index || (labels[it] == null && wakesSame(edges[index], edges[it])) }
            val spoken = speaking.map { lines[it] }
            val shared = spoken.size > 1
            val all = spoken.flatten()
            val voices = speaking.toSet()

            /**
             * How far the label at [at] is from the nearest point of the lines [of] says yes to, if
             * that is no further than [within]; else some distance greater than [within]. Only
             * the points near the label are looked at: no other is as near as that.
             */
            fun distance(at: Box, within: Double, of: (Int) -> Boolean): Double =
                points.least(at.grown(within + MARGIN), of) { gap(at, it) }

            fun clear(at: Box): Boolean {
                // Places are rounded, so a gap can measure a little less than it was laid out with.
                val grown = at.grown(CLEARANCE - ROUNDING)
                return cards.none { it.intersects(grown) } && others.none { it.intersects(grown) } && corners.none { it.intersects(at) } &&
                    // Inside a box it keeps off the border, outside it only has to be clear of the box.
                    frames.none { it.header.intersects(grown) || (it.box.intersects(at) && !it.box.contains(grown)) } &&
                    !points.any(at) { line, _ -> line == index } &&
                    // Lines that run where its own does are not in its way: edges that meet share their last stretch.
                    !points.any(grown) { line, point -> line != index && own.none { near(it, point) } }
            }

            /** Whether a label at [at] lies in a region that the edges it speaks for run in beside it. */
            fun home(at: Box): Boolean {
                val around = frames.lastOrNull { it.box.contains(at) }
                return beside(at, all).any { point -> frames.lastOrNull { it.box.contains(point) } === around }
            }

            /** Whether a label at [at] sits beside an edge it speaks for, or between two of them. */
            fun placed(at: Box): Boolean = distance(at, FAR) { it in voices } <= FAR || (shared && between(at, spoken))

            if (placed(box) && clear(box) && home(box)) continue

            // As near to its own edge as it was, if it was beside it at all.
            val reach = max(if (placed(box)) own.minOf { gap(box, it) } else 0.0, Geometry.LABEL_OFFSET) + 2
            fun good(at: Box): Boolean {
                val nearest = distance(at, reach) { it == index }
                return nearest > 0.5 && nearest <= reach && placed(at) && clear(at) && home(at)
            }
            val steps = generateSequence(STEP) { it + STEP }.takeWhile { it <= REACH }.flatMap { sequenceOf(it, -it) }.toList()
            val flips = listOf(box.height + 2 * Geometry.LABEL_OFFSET, box.width + 2 * Geometry.LABEL_OFFSET + 4).flatMap { listOf(it, -it) }
            val moves = steps.map { Point(it, 0.0) } + steps.map { Point(0.0, it) } +
                listOf(Point(0.0, flips[0]), Point(0.0, flips[1]), Point(flips[2], 0.0), Point(flips[3], 0.0))
            val best = moves.sortedBy { hypot(it.x, it.y) }.map { box.moved(it.x, it.y) }.firstOrNull(::good)
                ?: alongside(PathData.points(edges[index].path), box).firstOrNull(::good)
            if (best != null) labels[index] = best.rounded() else kept += index
        }
        // Judged where every label has come to rest: one that lay on another may be free of it now.
        val cramped = kept.filterTo(LinkedHashSet()) { index ->
            val inner = labels[index]!!.grown(-ROUNDING)
            covers(inner, cards, frames, corners) || labels.withIndex().any { (other, box) -> other != index && box != null && box.intersects(inner) }
        }
        val tidy = edges.mapIndexed { index, edge ->
            val box = labels[index]
            if (box == null || box == edge.label?.box) edge else edge.copy(label = edge.label?.copy(box = box))
        }
        return Tidy(tidy, cramped)
    }

    /**
     * Whether a label lies on a card, in a corner where a badge goes, or on the border or the
     * header of a region or of the band. Lines do not count, nor does what the label only
     * touches: places are rounded when they are written, and what is said of a label here must
     * hold for the diagram as it is read back.
     *
     * @param inner the box of the label, less what rounding can add to it on every side
     */
    private fun covers(inner: Box, cards: Collection<Box>, frames: List<Frame>, corners: Collection<Box>): Boolean =
        cards.any { it.intersects(inner) } || corners.any { it.intersects(inner) } ||
            frames.any { it.header.intersects(inner) || (it.box.intersects(inner) && !it.box.grown(-Geometry.REGION_BORDER).contains(inner)) }

    /**
     * The stretch of [line] that a label at [box] sits beside: its points that are level with
     * the label or straight above or below it, and not far from it. If there are none, as
     * beside the end of a line, the points nearest to it.
     */
    private fun beside(box: Box, line: List<Point>): List<Point> {
        val nearest = line.minOf { gap(box, it) }
        val reach = max(nearest + NEAR, FAR)
        return line.filter { (it.y in box.y..box.bottom || it.x in box.x..box.right) && gap(box, it) <= reach }
            .ifEmpty { line.filter { gap(box, it) <= nearest + NEAR } }
    }

    /** Whether two edges wake the same step, or cancel what one scope holds. Such edges share one label. */
    private fun wakesSame(one: PositionedEdge, other: PositionedEdge): Boolean =
        (one.kind == WAKES && other.kind == WAKES && one.to == other.to) || (one.kind == CANCELS && other.kind == CANCELS && one.from == other.from)

    /**
     * Whether a label lies between two of the [lines] it speaks for: level with it, one of them
     * passes on its left and another on its right.
     */
    private fun between(box: Box, lines: List<List<Point>>): Boolean {
        fun level(point: Point) = point.y in box.y..box.bottom
        val left = lines.indices.filter { index -> lines[index].any { level(it) && it.x < box.x } }
        val right = lines.indices.filter { index -> lines[index].any { level(it) && it.x > box.right } }
        return left.any { one -> right.any { it != one } }
    }

    /**
     * The places beside the straight stretches of the line through [points] where a label the
     * size of [box] can be set: on either side of each stretch, every few units along it, and
     * beside the middle of each stretch that is too short for that or runs at a slant. The
     * nearest to [box] comes first.
     */
    private fun alongside(points: List<Point>, box: Box): List<Box> {
        val offset = Geometry.LABEL_OFFSET
        val places = ArrayList<Box>()
        fun starts(from: Double, to: Double, size: Double): List<Double> {
            val first = min(from, to)
            val last = max(from, to) - size
            return if (last < first) listOf((from + to - size) / 2) else generateSequence(first) { it + STEP }.takeWhile { it <= last }.toList()
        }
        for ((a, b) in points.zipWithNext()) {
            when {
                a.y == b.y -> for (x in starts(a.x, b.x, box.width)) {
                    places += Box(x, a.y - offset - box.height, box.width, box.height)
                    places += Box(x, a.y + offset, box.width, box.height)
                }
                a.x == b.x -> for (y in starts(a.y, b.y, box.height)) {
                    places += Box(a.x + offset, y, box.width, box.height)
                    places += Box(a.x - offset - box.width, y, box.width, box.height)
                }
                else -> {
                    val middle = Point((a.x + b.x) / 2, (a.y + b.y) / 2)
                    places += Box(middle.x + offset, middle.y - box.height / 2, box.width, box.height)
                    places += Box(middle.x - offset - box.width, middle.y - box.height / 2, box.width, box.height)
                }
            }
        }
        return places.sortedBy { hypot(it.centerX - box.centerX, it.centerY - box.centerY) }
    }

    /** [points] with more points between any two that are far apart. */
    private fun dense(points: List<Point>): List<Point> {
        if (points.isEmpty()) return points
        val all = arrayListOf(points.first())
        for ((from, to) in points.zipWithNext()) {
            val pieces = max(1, (hypot(to.x - from.x, to.y - from.y) / GRAIN).toInt())
            for (step in 1..pieces) {
                val t = step.toDouble() / pieces
                all += Point(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
            }
        }
        return all
    }

    private fun near(a: Point, b: Point): Boolean = abs(a.x - b.x) < 1.0 && abs(a.y - b.y) < 1.0

    /** The distance of [point] from [box]; zero inside it. */
    private fun gap(box: Box, point: Point): Double {
        val dx = max(0.0, max(box.x - point.x, point.x - box.right))
        val dy = max(0.0, max(box.y - point.y, point.y - box.bottom))
        // Most points lie level with the box or straight above or below it, where the distance is one of the two.
        return if (dx == 0.0) dy else if (dy == 0.0) dx else hypot(dx, dy)
    }

    /**
     * The points of all lines by where they lie, so that those in a box are found without looking
     * at every point of the diagram each time.
     */
    private class Points(lines: List<List<Point>>) {
        private class Entry(val line: Int, val point: Point)

        private val cells = HashMap<Long, MutableList<Entry>>()

        init {
            lines.forEachIndexed { line, points ->
                for (point in points) cells.getOrPut(key(cell(point.x), cell(point.y))) { ArrayList() } += Entry(line, point)
            }
        }

        /** Whether [box] holds a point that [test] says yes to; it is handed the number of the point's line as well. */
        fun any(box: Box, test: (Int, Point) -> Boolean): Boolean {
            for (column in cell(box.x)..cell(box.right)) {
                for (row in cell(box.y)..cell(box.bottom)) {
                    for (entry in cells[key(column, row)].orEmpty()) {
                        if (box.contains(entry.point) && test(entry.line, entry.point)) return true
                    }
                }
            }
            return false
        }

        /** The least that [measure] gives for a point in [box] of a line that [of] says yes to; infinite if there is none. */
        fun least(box: Box, of: (Int) -> Boolean, measure: (Point) -> Double): Double {
            var least = Double.POSITIVE_INFINITY
            for (column in cell(box.x)..cell(box.right)) {
                for (row in cell(box.y)..cell(box.bottom)) {
                    for (entry in cells[key(column, row)].orEmpty()) {
                        if (of(entry.line) && box.contains(entry.point)) least = min(least, measure(entry.point))
                    }
                }
            }
            return least
        }

        private fun cell(at: Double): Int = floor(at / CELL).toInt()

        private fun key(column: Int, row: Int): Long = (column.toLong() shl Int.SIZE_BITS) or (row.toLong() and 0xFFFFFFFFL)

        private companion object {
            /** The side of a cell: about the height of two labels, so that a label looks at few cells and few points. */
            const val CELL = 32.0
        }
    }

    private const val WAKES = "wakes"
    private const val CANCELS = "cancels"
}
