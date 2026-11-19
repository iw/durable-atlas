package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.render.svg.Point
import io.github.iw.durableatlas.render.svg.rounded
import io.github.iw.durableatlas.render.svg.text
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Writes SVG path data with the precision of the diagram's coordinates. */
internal class PathBuilder {
    private val text = StringBuilder()
    private var at: Point? = null

    fun moveTo(point: Point): PathBuilder {
        val to = point.rounded()
        text.append('M').append(to.x.text()).append(' ').append(to.y.text())
        at = to
        return this
    }

    /** A straight line; horizontal and vertical ones are written in their short form. */
    fun lineTo(point: Point): PathBuilder {
        val to = point.rounded()
        val from = checkNotNull(at)
        when {
            to == from -> return this
            to.y == from.y -> text.append('H').append(to.x.text())
            to.x == from.x -> text.append('V').append(to.y.text())
            else -> text.append('L').append(to.x.text()).append(' ').append(to.y.text())
        }
        at = to
        return this
    }

    fun curveTo(first: Point, second: Point, end: Point): PathBuilder {
        text.append('C')
        listOf(first, second, end).joinTo(text, " ") { "${it.x.text()} ${it.y.text()}" }
        at = end.rounded()
        return this
    }

    fun quadTo(control: Point, end: Point): PathBuilder {
        text.append('Q')
        listOf(control, end).joinTo(text, " ") { "${it.x.text()} ${it.y.text()}" }
        at = end.rounded()
        return this
    }

    override fun toString(): String = text.toString()
}

internal object Paths {
    /** A line through [points] with sharp corners. */
    fun polyline(points: List<Point>): String {
        val path = PathBuilder().moveTo(points.first())
        points.drop(1).forEach(path::lineTo)
        return path.toString()
    }

    /** A line through [points] whose corners are rounded off with [radius]. */
    fun rounded(points: List<Point>, radius: Double): String = rounded(PathBuilder().moveTo(points.first()), points, radius).toString()

    /** Continues [path], which has arrived at the first of [points], through the others, rounding the corners off. */
    fun rounded(path: PathBuilder, points: List<Point>, radius: Double): PathBuilder {
        for (index in 1 until points.size - 1) {
            val corner = points[index]
            val before = points[index - 1]
            val after = points[index + 1]
            val cut = min(radius, min(distance(before, corner), distance(corner, after)) / 2)
            path.lineTo(towards(corner, before, cut))
            path.quadTo(corner, towards(corner, after, cut))
        }
        return path.lineTo(points.last())
    }

    /** An S-shaped curve that leaves [from] and arrives at [to] vertically. */
    fun drop(from: Point, to: Point, bend: Double = 0.55): PathBuilder = drop(PathBuilder().moveTo(from), from, to, bend)

    /** Continues [path], which has arrived at [from], with an S-shaped curve that arrives at [to] vertically. */
    fun drop(path: PathBuilder, from: Point, to: Point, bend: Double = 0.55): PathBuilder {
        val middle = from.y + (to.y - from.y) * bend
        return path.curveTo(Point(from.x, middle), Point(to.x, middle), to)
    }

    /** The start of a line through [points] with rounded corners, which can be continued from the last of them. */
    fun lead(points: List<Point>, radius: Double): PathBuilder {
        val path = PathBuilder().moveTo(points.first())
        return if (points.size == 1) path else rounded(path, points, radius)
    }

    /** Points along the curve [drop] draws, for checking what it runs over. */
    fun dropSamples(from: Point, to: Point, bend: Double = 0.55, count: Int = 24): List<Point> {
        val middle = from.y + (to.y - from.y) * bend
        val first = Point(from.x, middle)
        val second = Point(to.x, middle)
        return (0..count).map { step ->
            val t = step.toDouble() / count
            val u = 1 - t
            Point(
                u * u * u * from.x + 3 * u * u * t * first.x + 3 * u * t * t * second.x + t * t * t * to.x,
                u * u * u * from.y + 3 * u * u * t * first.y + 3 * u * t * t * second.y + t * t * t * to.y,
            )
        }
    }

    /** [points] without repeated points and without points in the middle of a straight run. */
    fun simplified(points: List<Point>): List<Point> {
        val kept = ArrayList<Point>()
        for (point in points) {
            if (kept.isNotEmpty() && near(kept.last(), point)) continue
            if (kept.size >= 2 && straight(kept[kept.size - 2], kept.last(), point)) kept.removeAt(kept.size - 1)
            kept += point
        }
        return kept
    }

    /** [points] with the last one pulled back by [gap] along the final stretch. */
    fun shortened(points: List<Point>, gap: Double): List<Point> {
        if (points.size < 2) return points
        val last = points.last()
        val before = points[points.size - 2]
        val length = distance(before, last)
        if (length <= gap) return points
        return points.dropLast(1) + towards(last, before, gap)
    }

    private fun towards(from: Point, to: Point, by: Double): Point {
        val length = distance(from, to)
        if (length == 0.0) return from
        return Point(from.x + (to.x - from.x) * by / length, from.y + (to.y - from.y) * by / length)
    }

    private fun distance(a: Point, b: Point): Double = hypot(b.x - a.x, b.y - a.y)

    private fun near(a: Point, b: Point): Boolean = abs(a.x - b.x) < EPSILON && abs(a.y - b.y) < EPSILON

    private fun straight(a: Point, b: Point, c: Point): Boolean =
        (abs(a.x - b.x) < EPSILON && abs(b.x - c.x) < EPSILON) || (abs(a.y - b.y) < EPSILON && abs(b.y - c.y) < EPSILON)

    private const val EPSILON = 0.05
}
