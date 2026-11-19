package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Point
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How edges that are not routed by the layout engine reach a target: from above, at [arrivals]
 * on its top edge, or, with [channel] set, down that vertical line beside it and into its side.
 * Where steps lie above the target and on both sides of it, as around a step in the middle of
 * a lane, the edges leave the channel at the height [gap], between the target and what lies
 * above it, and come down on the target from there.
 *
 * @param outside whether the channel lies beyond the end of the rows rather than between two steps
 */
internal class Approach(
    val arrivals: List<Double>,
    val channel: Double? = null,
    val fromLeft: Boolean = true,
    val outside: Boolean = false,
    val gap: Double? = null,
) {
    /** Where an edge that arrives from the side ends. */
    fun sideEnd(target: Box): Point =
        if (fromLeft) Point(target.x - Geometry.EDGE_END_GAP, target.centerY) else Point(target.right + Geometry.EDGE_END_GAP, target.centerY)

    /**
     * The way from the channel into the target, for the edge that is number [index] of those
     * that arrive there: at the height of the target and into its side, or along the gap above
     * it and down on it.
     */
    fun entry(target: Box, index: Int): List<Point> {
        val line = channel ?: return emptyList()
        val height = gap ?: return listOf(Point(line, target.centerY), sideEnd(target))
        return listOf(Point(line, height), Point(arrivals[index], height), Point(arrivals[index], target.y - Geometry.EDGE_END_GAP))
    }

    companion object {
        /**
         * The way for [count] edges into [target], which lies in [row]: from above if nothing
         * is in the way there, and else from the side, past the region or column it sits in.
         *
         * @param obstacles the boxes that lines should not run over
         * @param headers the words in the corners of regions, which lines should not run through
         */
        fun to(sheet: Sheet, targetId: String, target: Box, count: Int, obstacles: Collection<Box>, headers: Collection<Box>, row: Int?): Approach {
            val spread = if (count > 1) min(ARRIVAL_SPREAD, (target.width - 2 * ARRIVAL_INSET) / (count - 1)) else 0.0
            val arrivals = (0 until count).map { target.centerX + (it - (count - 1) / 2.0) * spread }
            val rowBox = row?.let { sheet.rows[it] } ?: return Approach(arrivals)
            fun above(x: Double) = obstacles.none { it.bottom > rowBox.y && it.y < target.y && x in (it.x - 2)..(it.right + 2) }
            if (arrivals.all(::above)) {
                // The words of a header above the target, such as that of the loop it is the first step of.
                val words = headers.filter { it.bottom > rowBox.y && it.y < target.y }
                    .map { (it.x - HEADER_CLEARANCE)..(it.right + HEADER_CLEARANCE) }
                if (arrivals.none { x -> words.any { x in it } }) return Approach(arrivals)
                // Only words are in the way. Arrive beside them, as near to the middle as they leave room.
                beside(target, count, spread, words)?.takeIf { it.all(::above) }?.let { return Approach(it) }
            }

            // Something sits above the target, as the earlier steps of a loop do. Go past it at the side.
            val around = sheet.boxes.getValue(sheet.topOf.getValue(targetId))
            val sides = listOf(true, false).map { fromLeft ->
                val outside = if (fromLeft) around.x - rowBox.x < 1 else rowBox.right - around.right < 1
                val channel = when {
                    fromLeft -> if (outside) sheet.leftMargin else around.x - SIDE_CHANNEL
                    else -> if (outside) sheet.rightMargin else around.right + SIDE_CHANNEL
                }
                Approach(arrivals, channel, fromLeft, outside)
            }
            fun wayDown(channel: Double, to: Double) = obstacles.none { it.bottom > rowBox.y && it.y < to && channel in (it.x - 2)..(it.right + 2) }
            fun wayAcross(height: Double, from: Double, to: Double) = obstacles.none { it.y < height && it.bottom > height && it.x < to && it.right > from }
            for (side in sides) {
                val channel = side.channel!!
                val (from, to) = if (side.fromLeft) channel to target.x else target.right to channel
                if (wayAcross(target.centerY, from, to) && wayDown(channel, target.centerY)) return side
            }
            // Steps on both sides as well, as around a step in the middle of a lane. Between the target
            // and what lies above it there may be room to come in from the side and down on it.
            val over = obstacles.filter { it.bottom > rowBox.y && it.bottom <= target.y && it.x < target.right && it.right > target.x }.maxOfOrNull { it.bottom }
            if (over != null && target.y - over >= GAP_ROOM) {
                val height = (over + target.y) / 2
                for (side in sides) {
                    val channel = side.channel!!
                    val (from, to) = if (side.fromLeft) channel to arrivals.max() else arrivals.min() to channel
                    if (wayAcross(height, from, to) && wayDown(channel, height)) return Approach(arrivals, channel, side.fromLeft, side.outside, height)
                }
            }
            // No way in there either. Next to the middle the line at least stays off the edges drawn there.
            return Approach(if (count == 1) listOf(target.x + target.width * 0.75) else arrivals)
        }

        /**
         * Where [count] edges arrive on top of [target] without passing through [taken], the
         * stretches that words above it take up: as near to its middle as can be, and no closer
         * together than they must. Null if no stretch of its top edge is free for them.
         */
        private fun beside(target: Box, count: Int, spread: Double, taken: List<ClosedFloatingPointRange<Double>>): List<Double>? {
            var free = listOf((target.x + ARRIVAL_INSET)..(target.right - ARRIVAL_INSET))
            for (words in taken) {
                free = free.flatMap { listOf(it.start..min(it.endInclusive, words.start), max(it.start, words.endInclusive)..it.endInclusive) }
                    .filter { it.start <= it.endInclusive }
            }
            return free.filter { it.endInclusive - it.start >= (count - 1) * ARRIVAL_SPREAD_MIN }
                .map { room ->
                    val apart = if (count > 1) min(spread, (room.endInclusive - room.start) / (count - 1)) else 0.0
                    val half = apart * (count - 1) / 2
                    val middle = target.centerX.coerceIn(room.start + half, room.endInclusive - half)
                    (0 until count).map { middle + (it - (count - 1) / 2.0) * apart }
                }
                .minByOrNull { abs(it.average() - target.centerX) }
        }

        /** How far apart edges arrive on top of one target, and how far from its corners. */
        private const val ARRIVAL_SPREAD = 80.0
        private const val ARRIVAL_INSET = 24.0

        /** How close together edges may arrive where there is little room, and how near to the words of a header. */
        private const val ARRIVAL_SPREAD_MIN = 24.0
        private const val HEADER_CLEARANCE = 10.0

        /** How far beside a region the line runs that leads to a target inside it. */
        private const val SIDE_CHANNEL = 9.0

        /** The least room between a target and what lies above it for a line to come in between the two. */
        private const val GAP_ROOM = 20.0
    }
}
