package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Point
import io.github.iw.durableatlas.render.svg.scene.CardTexts
import io.github.iw.durableatlas.render.svg.scene.Scene
import io.github.iw.durableatlas.render.svg.scene.SceneHandler
import io.github.iw.durableatlas.render.svg.scene.SceneItem
import io.github.iw.durableatlas.render.svg.scene.SceneRegion
import kotlin.math.abs
import kotlin.math.min

/**
 * Draws the edges to what is woken. Most of them start at handlers. A target in the first row is
 * reached by a curve straight down from the band; a target further down by a line around the
 * rows above it. Either way the edge arrives from above if nothing is in the way there, and
 * else from the side; where steps lie on both sides as well, it comes in between the target and
 * what lies above it. The title of a region counts as in the way: an edge passes beside its
 * words, not through them.
 * From a handler that runs steps of its own, the edge leaves the card sideways and passes those
 * steps before it leaves the band; beside such a handler, the edges of the others run straight
 * down to where the band ends. All the edges to one target share one label.
 *
 * What a handler wakes may also be a step of a handler: a wait for a lock that another run of
 * the same handler lets go of, or that another handler does. Such an edge stays inside the band.
 * It runs down a channel at the right of the steps of the handler that waits and enters the
 * step from that side; from the card of that handler it comes sideways, from another handler
 * along the space between the cards and the steps below them.
 *
 * What ends a wait may also be the workflow's own code. Such an edge starts at the start of the
 * workflow method, which lies in the flow and not in the band. It leaves that card upwards and
 * runs along above its row: to a wait in the flow it comes down like the others, from above or
 * from the side; to a step of a handler it goes up the channel at the right of that handler's
 * steps, where the edges from the handlers come down.
 *
 * @param labelWidth the width of that label
 */
internal class WakeRouter(private val scene: Scene, private val sheet: Sheet, private val labelWidth: Double) {
    /**
     * @param labelAt the point beside which the label of the line goes
     * @param onLine whether the label sits on the line there rather than next to it
     */
    private class Line(val path: String, val labelAt: Point, val onLine: Boolean = false)

    fun draw() {
        val boxes = sheet.boxes
        // What each handler that runs something takes up in the band: its card and its steps.
        val bodies = scene.handlers.filter { it.body.isNotEmpty() }.associate { handler ->
            handler.card.id to Box.around((listOf(handler.card) + handler.body).mapNotNull { boxes[it.id] } + listOfNotNull(sheet.detours[handler.card.id]))!!
        }
        val cards = sheet.mainCards()
        val headers = sheet.mainHeaders()
        val byTarget = scene.wakes.filter { it.from in boxes && it.to in boxes }.groupBy { it.to }
        val targets = byTarget.keys.sortedWith(compareBy({ sheet.rowAt(it) ?: -1 }, { boxes.getValue(it).x }, { it }))
        val homes = homes(scene.handlers)
        var lanes = 0

        for (targetId in targets) {
            val target = boxes.getValue(targetId)
            val wakes = byTarget.getValue(targetId).sortedWith(compareBy({ boxes.getValue(it.from).centerX }, { it.id }))
            val row = sheet.rowAt(targetId)
            val home = homes[targetId]?.card?.id
            val lines = if (home != null) {
                val lane = lanes++
                wakes.map { wake ->
                    val card = boxes.getValue(wake.from)
                    val from = sheet.rowAt(wake.from)
                    if (from != null) intoBand(up(card, from), bodies.getValue(home), target) else inBand(card, wake.from == home, bodies.getValue(home), target, lane)
                }
            } else {
                val obstacles = cards.filterKeys { it != targetId }.values
                val approach = Approach.to(sheet, targetId, target, wakes.size, obstacles, headers, row)
                wakes.mapIndexed { index, wake ->
                    val card = boxes.getValue(wake.from)
                    val from = sheet.rowAt(wake.from)
                    val lead = if (from != null) up(card, from) else bodies[wake.from]?.let { around -> past(card, around, target) } ?: below(card)
                    when {
                        from != null && (row == null || row == from) -> along(lead, target, approach, index)
                        from != null -> around(lead, target, approach, index, row!!, under = lead.last().y)
                        row != null && row > 0 -> around(lead, target, approach, index, row)
                        approach.channel != null -> beside(lead, target, approach, index, row)
                        else -> direct(lead, Point(approach.arrivals[index], target.y - Geometry.EDGE_END_GAP), obstacles + headers, row)
                    }
                }
            }
            val anchors = lines.map { it.labelAt }.distinct()
            val label = if (anchors.size == 1) {
                val left = if (lines.first().onLine) anchors[0].x - labelWidth / 2 else anchors[0].x + Geometry.LABEL_OFFSET
                Box(left, anchors[0].y - Geometry.LABEL_HEIGHT / 2, labelWidth, Geometry.LABEL_HEIGHT)
            } else {
                Box(anchors.map { it.x }.average() - labelWidth / 2, anchors.map { it.y }.average() - Geometry.LABEL_HEIGHT / 2, labelWidth, Geometry.LABEL_HEIGHT)
            }
            wakes.forEachIndexed { index, wake -> sheet.add(wake, lines[index].path, label.takeIf { index == 0 }, CardTexts.WAKES) }
        }
    }

    /**
     * An edge to a step that a handler runs. It comes down the channel at the right of that
     * handler's steps and enters the step from there. From the card of the same handler it leaves
     * sideways, a little below the middle, where an edge to the main flow does not run. From
     * another handler it leaves the card downwards, on the half that faces the channel, and
     * crosses over between the cards and the steps below them.
     *
     * @param own whether the edge starts at the handler that runs the step
     * @param around the box around the card and the steps of the handler that runs the step
     * @param lane which of the edges that cross the band this is, so that they do not lie on each other
     */
    private fun inBand(card: Box, own: Boolean, around: Box, target: Box, lane: Int): Line {
        val channel = around.right + Geometry.BAND_WAKE_CHANNEL
        val end = Point(target.right + Geometry.EDGE_END_GAP, target.centerY)
        val points = if (own) {
            val leave = card.centerY + OWN_OFFSET
            listOf(Point(card.right, leave), Point(channel, leave), Point(channel, target.centerY), end)
        } else {
            val side = if (channel >= card.centerX) 1 else -1
            val start = Point(card.centerX + side * card.width / 4, card.bottom)
            val across = card.bottom + LANE_FIRST + (lane % LANES) * LANE_STEP
            listOf(start, Point(start.x, across), Point(channel, across), Point(channel, target.centerY), end)
        }
        sheet.reach(points)
        // The words stand right of the channel, above where the edge turns into the step.
        return Line(Paths.rounded(Paths.simplified(points), Geometry.CORNER_RADIUS), Point(channel, target.centerY - Geometry.LABEL_HEIGHT / 2 - LABEL_ABOVE))
    }

    /**
     * The way out of its row for an edge from the start of the workflow method: up from its
     * card to the space above the row, where no step lies.
     *
     * @param row the row the card lies in
     */
    private fun up(card: Box, row: Int): List<Point> =
        listOf(Point(card.centerX, card.y), Point(card.centerX, sheet.rows[row].y - ABOVE_ROW))

    /**
     * An edge from above the row of the workflow's start to a wait in that row: along above the
     * row, then down into the wait, or down the channel beside it and into its side.
     *
     * @param lead the way up out of the row, which the edge goes on from
     */
    private fun along(lead: List<Point>, target: Box, approach: Approach, index: Int): Line {
        val level = lead.last().y
        val channel = approach.channel
        val points: List<Point>
        val labelAt: Point
        var onLine = false
        if (channel == null) {
            val end = Point(approach.arrivals[index], target.y - Geometry.EDGE_END_GAP)
            points = lead + listOf(Point(end.x, level), end)
            labelAt = if (end.y - level >= Geometry.LABEL_HEIGHT + 4) Point(end.x, (level + end.y) / 2) else Point(end.x, level - Geometry.LABEL_HEIGHT / 2 - 2)
        } else {
            points = lead + Point(channel, level) + approach.entry(target, index)
            // Beside the way down there is no room for words; they sit on the line that leads there.
            labelAt = Point(channel + if (lead.last().x < channel) -LABEL_BEFORE_TURN else LABEL_BEFORE_TURN, level)
            onLine = true
        }
        sheet.reach(points)
        return Line(Paths.rounded(Paths.simplified(points), Geometry.CORNER_RADIUS), labelAt, onLine)
    }

    /**
     * An edge from above the row of the workflow's start to a step that a handler runs: along
     * below the band to the channel at the right of that handler's steps, up it, and into the
     * step from there, as the edges from the handlers enter it.
     *
     * @param lead the way up out of the row, which the edge goes on from
     * @param around the box around the card and the steps of the handler that runs the step
     */
    private fun intoBand(lead: List<Point>, around: Box, target: Box): Line {
        val channel = around.right + Geometry.BAND_WAKE_CHANNEL
        val points = lead + listOf(Point(channel, lead.last().y), Point(channel, target.centerY), Point(target.right + Geometry.EDGE_END_GAP, target.centerY))
        sheet.reach(points)
        return Line(Paths.rounded(Paths.simplified(points), Geometry.CORNER_RADIUS), Point(channel, target.centerY - Geometry.LABEL_HEIGHT / 2 - LABEL_ABOVE))
    }

    /** The handler that runs each step of a handler, by the id of the step. */
    private fun homes(handlers: List<SceneHandler>): Map<String, SceneHandler> {
        val homes = HashMap<String, SceneHandler>()
        for (handler in handlers) for (id in WakeTargets.ids(handler.body)) homes[id] = handler
        return homes
    }

    /**
     * The way out of the band for an edge from a handler that runs nothing: down from its card.
     * Where other handlers run steps, the band reaches further down than the card. The edge then
     * goes straight on to where the band ends and turns towards what it wakes only below it, so
     * that it crosses neither those steps nor the edges that run down beside them.
     */
    private fun below(card: Box): List<Point> {
        val start = Point(card.centerX, card.bottom)
        val bottom = sheet.band?.bottom ?: return listOf(start)
        return if (bottom - card.bottom > Geometry.BAND_BOTTOM + 1) listOf(start, Point(card.centerX, bottom)) else listOf(start)
    }

    /**
     * The way out of the band for an edge from a handler that runs something. Its steps hang
     * below its card, so the edge leaves the card at the side that faces what it wakes and runs
     * down beside the steps, to where the band ends.
     *
     * @param around the box around the card and its steps
     */
    private fun past(card: Box, around: Box, target: Box): List<Point> {
        val right = target.centerX >= card.centerX
        val channel = if (right) around.right + BODY_CHANNEL else around.x - BODY_CHANNEL
        val bottom = sheet.band?.bottom ?: around.bottom
        return listOf(Point(if (right) card.right else card.x, card.centerY), Point(channel, card.centerY), Point(channel, bottom))
    }

    /** A curve straight down from the band, for a target in the first row. */
    private fun direct(lead: List<Point>, end: Point, obstacles: Collection<Box>, row: Int?): Line {
        val start = lead.last()
        val samples = Paths.dropSamples(start, end)
        val hit = samples.any { point -> obstacles.any { it.grown(CLEARANCE).contains(point) } }
        val rowTop = row?.let { sheet.rows[it].y }
        if (!hit || rowTop == null || rowTop - CLEARANCE <= start.y) {
            return Line(Paths.drop(Paths.lead(lead, Geometry.CORNER_RADIUS), start, end).toString(), samples[(samples.size * LABEL_ALONG).toInt()])
        }
        // Move sideways above the row, then come straight down.
        val turn = Point(end.x, rowTop - CLEARANCE)
        val path = Paths.drop(Paths.lead(lead, Geometry.CORNER_RADIUS), start, turn, bend = 0.5).lineTo(end).toString()
        return Line(path, Point(end.x, (turn.y + end.y) / 2))
    }

    /** A curve from the band to the top of the channel beside a target in the first row, then down it and in. */
    private fun beside(lead: List<Point>, target: Box, approach: Approach, index: Int, row: Int?): Line {
        val start = lead.last()
        val channel = approach.channel!!
        val turn = Point(channel, (row?.let { sheet.rows[it].y } ?: target.y) - CLEARANCE)
        val path = Paths.drop(Paths.lead(lead, Geometry.CORNER_RADIUS), start, turn, bend = 0.5)
        Paths.rounded(path, listOf(turn) + approach.entry(target, index), Geometry.CORNER_RADIUS)
        sheet.reach(Box(min(channel, target.x), turn.y, abs(target.x - channel), target.centerY - turn.y))
        return Line(path.toString(), Point(channel, turn.y - Geometry.LABEL_HEIGHT / 2))
    }

    /**
     * A line around the rows above the target: out to the side, down, and in along the space above the target's row.
     *
     * @param under the height at which the line runs out to the side: below the band, unless it starts further down
     */
    private fun around(
        lead: List<Point>,
        target: Box,
        approach: Approach,
        index: Int,
        row: Int,
        under: Double = (sheet.band?.bottom ?: lead.last().y) + UNDER_BAND,
    ): Line {
        val start = lead.last()
        val left = sheet.leftMargin
        val right = sheet.rightMargin
        val channel = approach.channel
        val goal = channel ?: approach.arrivals[index]
        val side = when {
            channel != null && approach.outside -> channel
            (start.x - left) + (goal - left) <= (right - start.x) + (right - goal) -> left
            else -> right
        }
        val lane = sheet.rows[row].y - ABOVE_ROW
        val points = ArrayList(lead + listOf(Point(start.x, under), Point(side, under)))
        val line: (String) -> Line
        if (channel == null) {
            val end = Point(goal, target.y - Geometry.EDGE_END_GAP)
            points += listOf(Point(side, lane), Point(goal, lane), end)
            val labelAt = if (end.y - lane >= Geometry.LABEL_HEIGHT + 4) Point(goal, (lane + end.y) / 2) else Point(goal, lane - Geometry.LABEL_HEIGHT / 2 - 2)
            line = { Line(it, labelAt) }
        } else if (approach.outside) {
            points += approach.entry(target, index)
            line = { Line(it, Point(channel, lane - Geometry.LABEL_HEIGHT - 2)) }
        } else {
            points += listOf(Point(side, lane), Point(channel, lane)) + approach.entry(target, index)
            // Beside the way down there is no room for words; they sit on the line that leads there.
            val along = if (side < channel) -LABEL_BEFORE_TURN else LABEL_BEFORE_TURN
            line = { Line(it, Point(channel + along, lane), onLine = true) }
        }
        sheet.reach(points)
        return line(Paths.rounded(Paths.simplified(points), Geometry.CORNER_RADIUS))
    }

    private companion object {
        /** How close a line that passes a card may come to it. */
        const val CLEARANCE = 8.0

        /** How far beside the steps of a handler the line runs that leaves its card. */
        const val BODY_CHANNEL = 8.0

        /** How far along a curve its label sits. */
        const val LABEL_ALONG = 0.42

        /** How far before the turn into a channel the label of a line sits on it. */
        const val LABEL_BEFORE_TURN = 44.0

        /** How far below the middle of its card an edge leaves that wakes a step of the same handler. */
        const val OWN_OFFSET = 10.0

        /** Where, below the cards of the band, the edges cross that wake a step of another handler: the first, and each further one. */
        const val LANE_FIRST = 8.0
        const val LANE_STEP = 5.0
        const val LANES = 3

        /** How far above the turn into the step the label of such an edge stands. */
        const val LABEL_ABOVE = 4.0

        /** How far below the band, and above a row, a line runs that goes around rows. */
        const val UNDER_BAND = 14.0
        const val ABOVE_ROW = 12.0
    }
}

/** Tells which steps of handlers are woken, for the room their edges need in the band. */
internal object WakeTargets {
    /** The ids of [items] and of everything they hold. */
    fun ids(items: List<SceneItem>): List<String> = items.flatMap { item ->
        when (item) {
            is SceneRegion -> listOf(item.id) + ids(item.children)
            else -> listOf(item.id)
        }
    }

    /** Whether one of the steps [handler] runs is woken, by a handler or by the workflow method. */
    fun holdsWoken(scene: Scene, handler: SceneHandler): Boolean {
        val woken = scene.wakes.mapTo(HashSet()) { it.to }
        return ids(handler.body).any { it in woken }
    }
}
