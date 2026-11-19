package io.github.iw.durableatlas.render.svg

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

@Serializable
internal data class Point(val x: Double, val y: Double) {
    fun moved(dx: Double, dy: Double): Point = Point(x + dx, y + dy)
}

@Serializable
internal data class Box(val x: Double, val y: Double, val width: Double, val height: Double) {
    val right: Double
        get() = x + width
    val bottom: Double
        get() = y + height
    val centerX: Double
        get() = x + width / 2
    val centerY: Double
        get() = y + height / 2

    fun moved(dx: Double, dy: Double): Box = Box(x + dx, y + dy, width, height)

    fun grown(by: Double): Box = Box(x - by, y - by, width + 2 * by, height + 2 * by)

    fun contains(other: Box): Boolean =
        other.x >= x && other.y >= y && other.right <= right && other.bottom <= bottom

    fun contains(point: Point): Boolean = point.x in x..right && point.y in y..bottom

    fun intersects(other: Box): Boolean =
        other.x < right && x < other.right && other.y < bottom && y < other.bottom

    fun union(other: Box): Box {
        val left = min(x, other.x)
        val top = min(y, other.y)
        return Box(left, top, max(right, other.right) - left, max(bottom, other.bottom) - top)
    }

    companion object {
        fun around(boxes: Collection<Box>): Box? = boxes.reduceOrNull(Box::union)
    }
}
