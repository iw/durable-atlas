package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Point
import io.github.iw.durableatlas.render.svg.engine.EngineRoute
import io.github.iw.durableatlas.render.svg.engine.Flow
import kotlin.math.abs

/**
 * Takes needless turns out of routed edges.
 *
 * - **Small steps.** The layout engine gives the label of an edge a place in a column of its
 *   own, between the columns of the steps, and that place can end up a little higher or lower
 *   than the step the edge leads to. The edge then reaches its target, or leaves its source,
 *   over a short step sideways. The stretch that carries the label is moved level with that end
 *   of the edge, and the label with it, if nothing is in the way.
 * - **Stairs.** An edge that passes a column of steps is given a place in that column too,
 *   and can come out as two turns in the same direction where one would do. The two are made
 *   one, at the later of the two places if the way there is free, else at the earlier.
 */
internal object Kinks {
    /** The tallest step that is taken out. */
    private const val MAX_STEP = 20.0

    /** How near to its stretch of the edge a label must be to move with it. */
    private const val LABEL_REACH = 26.0

    /** How close a line that is moved may come to something placed, and to a line that runs the same way. */
    private const val CLEARANCE = 10.0
    private const val ALONG = 6.0

    /**
     * @param boxes everything that is placed, by id
     * @param flow the direction the routed edges follow
     */
    fun removed(routes: Map<String, EngineRoute>, boxes: Map<String, Box>, flow: Flow): Map<String, EngineRoute> {
        if (flow == Flow.DOWN) {
            val turned = removed(routes.mapValues { it.value.turned() }, boxes.mapValues { it.value.turned() }, Flow.RIGHT)
            return turned.mapValues { it.value.turned() }
        }
        val result = LinkedHashMap(routes)
        for ((id, route) in routes) {
            // The other routes as they stand now. Few routes have a turn to take out, and only those look at the others.
            val others = lazy(LazyThreadSafetyMode.NONE) { result.filterKeys { it != id }.values.mapNotNull { it.label } }
            var smooth = levelled(route, boxes.values, others) ?: route
            smooth = levelled(smooth.reversed(), boxes.values, others)?.reversed() ?: smooth
            val lines = lazy(LazyThreadSafetyMode.NONE) { result.filterKeys { it != id }.values.map { it.points } }
            result[id] = unstepped(smooth, boxes.values, lines) ?: smooth
        }
        return result
    }

    /**
     * [route] without a small step just before its end, or null if it has none that can be taken out.
     *
     * @param labels the labels of the other routes
     */
    private fun levelled(route: EngineRoute, boxes: Collection<Box>, labels: Lazy<List<Box>>): EngineRoute? {
        val points = route.points
        if (points.size < 5) return null
        val (before, a, b, c, d) = points.takeLast(5)
        val shaped = before.x == a.x && a.y == b.y && b.x == c.x && c.y == d.y
        val step = d.y - a.y
        if (!shaped || step == 0.0 || abs(step) > MAX_STEP) return null

        val run = Box(minOf(a.x, b.x), d.y, abs(b.x - a.x), 0.0)
        if (boxes.any { it.intersects(run) && !it.contains(run) }) return null
        val label = route.label?.let { label ->
            val beside = label.centerX in minOf(a.x, d.x)..maxOf(a.x, d.x) && abs(label.centerY - a.y) <= LABEL_REACH
            if (!beside) return@let label
            val moved = label.moved(0.0, step)
            if (boxes.any { it.intersects(moved) && !it.contains(moved) } || labels.value.any { it.intersects(moved) }) return null
            moved
        }
        return EngineRoute(Paths.simplified(points.dropLast(4) + Point(a.x, d.y) + d), label)
    }

    /**
     * [route] with one turn where it had two in the same direction, or null if it has no such pair that can be made one.
     *
     * @param lines the lines of the other routes
     */
    private fun unstepped(route: EngineRoute, boxes: Collection<Box>, lines: Lazy<List<List<Point>>>): EngineRoute? {
        val points = route.points
        for (index in 0..points.size - 6) {
            val (a, b, c, d, e) = points.subList(index, index + 5)
            val f = points[index + 5]
            val stairs = a.y == b.y && b.x == c.x && c.y == d.y && d.x == e.x && e.y == f.y && (c.y - b.y) * (e.y - d.y) > 0
            if (!stairs) continue
            // The label may sit on the stretch between the two turns; then that stretch stays.
            val between = Box.around(listOf(point(c), point(d)))!!.grown(LABEL_REACH)
            if (route.label?.let(between::intersects) == true) continue
            val late = Point(d.x, a.y)
            val early = Point(b.x, e.y)
            val turned = when {
                free(a, late, boxes, lines) && free(late, e, boxes, lines) -> listOf(a, late, e, f)
                free(b, early, boxes, lines) && free(early, f, boxes, lines) -> listOf(a, b, early, f)
                else -> continue
            }
            return EngineRoute(Paths.simplified(points.take(index) + turned + points.drop(index + 6)), route.label)
        }
        return null
    }

    /** Whether a line from [from] to [to] passes nothing that is placed and does not run along another line. */
    private fun free(from: Point, to: Point, boxes: Collection<Box>, lines: Lazy<List<List<Point>>>): Boolean {
        val line = Box.around(listOf(point(from), point(to)))!!
        // Room is needed beside the line, not beyond its ends, where it meets what it joins.
        val wide = if (line.height == 0.0) Box(line.x, line.y - CLEARANCE, line.width, 2 * CLEARANCE) else Box(line.x - CLEARANCE, line.y, 2 * CLEARANCE, line.height)
        if (boxes.any { it.intersects(wide) && !it.contains(wide) }) return false
        return lines.value.none { other ->
            other.zipWithNext().any { (p, q) ->
                val stretch = Box.around(listOf(point(p), point(q)))!!
                // Along each other: both lie flat, or both stand upright, and they overlap.
                (line.height == 0.0) == (stretch.height == 0.0) && (line.width == 0.0) == (stretch.width == 0.0) && stretch.grown(ALONG).intersects(line)
            }
        }
    }

    private fun point(point: Point) = Box(point.x, point.y, 0.0, 0.0)

    private fun EngineRoute.reversed() = EngineRoute(points.asReversed(), label)

    private fun EngineRoute.turned() = EngineRoute(points.map { Point(it.y, it.x) }, label?.turned())

    private fun Box.turned() = Box(y, x, height, width)
}
