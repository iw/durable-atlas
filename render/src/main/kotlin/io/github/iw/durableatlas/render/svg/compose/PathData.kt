package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.render.svg.Point
import io.github.iw.durableatlas.render.svg.text

/** Reads the path data that [PathBuilder] writes: absolute commands only, `M L H V C Q`. */
internal object PathData {
    private val token = Regex("[MLHVCQ]|-?\\d+(?:\\.\\d+)?")

    /**
     * Points along [path], close enough together to tell what the line runs over: the ends of
     * its straight stretches, and curves as a row of short ones.
     */
    fun points(path: String): List<Point> {
        val points = ArrayList<Point>()
        var command = ' '
        val numbers = ArrayList<Double>()
        fun flush() {
            val last = points.lastOrNull()
            when (command) {
                'M', 'L' -> numbers.chunked(2).forEach { (x, y) -> points += Point(x, y) }
                'H' -> numbers.forEach { points += Point(it, points.last().y) }
                'V' -> numbers.forEach { points += Point(points.last().x, it) }
                'C' -> numbers.chunked(6).forEach { c -> curve(points, listOf(points.last(), Point(c[0], c[1]), Point(c[2], c[3]), Point(c[4], c[5]))) }
                'Q' -> numbers.chunked(4).forEach { c ->
                    // The same curve with two control points.
                    val from = points.last()
                    val control = Point(c[0], c[1])
                    val to = Point(c[2], c[3])
                    val first = Point(from.x + (control.x - from.x) * 2 / 3, from.y + (control.y - from.y) * 2 / 3)
                    val second = Point(to.x + (control.x - to.x) * 2 / 3, to.y + (control.y - to.y) * 2 / 3)
                    curve(points, listOf(from, first, second, to))
                }
                else -> check(last == null)
            }
            numbers.clear()
        }
        for (match in token.findAll(path)) {
            val text = match.value
            if (text.length == 1 && text[0].isLetter()) {
                flush()
                command = text[0]
            } else {
                numbers += text.toDouble()
            }
        }
        flush()
        return points
    }

    private fun curve(points: MutableList<Point>, control: List<Point>) {
        val (a, b, c, d) = control
        for (step in 1..CURVE_STEPS) {
            val t = step.toDouble() / CURVE_STEPS
            val u = 1 - t
            points += Point(
                u * u * u * a.x + 3 * u * u * t * b.x + 3 * u * t * t * c.x + t * t * t * d.x,
                u * u * u * a.y + 3 * u * u * t * b.y + 3 * u * t * t * c.y + t * t * t * d.y,
            )
        }
    }

    private const val CURVE_STEPS = 16

    /** [path] moved by [dx] and [dy]. */
    fun moved(path: String, dx: Double, dy: Double): String {
        if (dx == 0.0 && dy == 0.0) return path
        val out = StringBuilder()
        var command = ' '
        var index = 0
        for (match in token.findAll(path)) {
            val text = match.value
            if (text.length == 1 && text[0].isLetter()) {
                command = text[0]
                index = 0
                out.append(command)
                continue
            }
            val value = text.toDouble()
            val horizontal = when (command) {
                'H' -> true
                'V' -> false
                else -> index % 2 == 0
            }
            if (index > 0) out.append(' ')
            out.append((value + if (horizontal) dx else dy).text())
            index++
        }
        return out.toString()
    }
}
