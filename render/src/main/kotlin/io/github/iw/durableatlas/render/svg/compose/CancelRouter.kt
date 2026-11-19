package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Point
import io.github.iw.durableatlas.render.svg.scene.CardTexts
import io.github.iw.durableatlas.render.svg.scene.Scene
import io.github.iw.durableatlas.render.svg.scene.SceneEdge
import io.github.iw.durableatlas.render.svg.scene.SceneItem
import io.github.iw.durableatlas.render.svg.scene.SceneRegion
import kotlin.math.abs
import kotlin.math.max

/**
 * Draws the edges from the place where a cancellation scope is cancelled to what that cancels,
 * where both lie in the main flow. Such an edge is no way the flow takes, and as a rule it leads
 * back along it. So it keeps out of the way of the flow: it leaves the bottom of the one, runs
 * below the row, and comes up into the bottom of the other. An end that lies in a region gets
 * out of it through the strip its steps leave free at its left side. To a step in another row
 * the edge goes around the rows at their left, and in below the row of that step.
 *
 * The edges of one place fan out: the one that goes furthest runs lowest and leaves last, so
 * that none of them crosses another. They share one label.
 *
 * @param labelWidth the width of that label
 */
internal class CancelRouter(private val scene: Scene, private val sheet: Sheet, private val labelWidth: Double) {
    /** The region each item of the main flow lies in. */
    private val regionOf = HashMap<String, String>()

    init {
        fun note(item: SceneItem) {
            if (item is SceneRegion) item.children.forEach { regionOf[it.id] = item.id; note(it) }
        }
        scene.main.forEach(::note)
    }

    /** The edges by the place they start at, each with the nearest of what it cancels first: those of its own row, then the others. */
    private fun fans(): Map<String, List<SceneEdge>> =
        scene.cancels.filter { sheet.rowAt(it.from) != null && sheet.rowAt(it.to) != null }.groupBy { it.from }

    /** How many lines run below [row]: the most that leave one place of it, and those that arrive from other rows. */
    private fun lines(row: Int): Pair<Int, Int> {
        val fans = fans()
        val leaving = fans.filterKeys { sheet.rowAt(it) == row }.values.maxOfOrNull { it.size } ?: 0
        val arriving = fans.values.flatten().count { sheet.rowAt(it.to) == row && sheet.rowAt(it.from) != row }
        return leaving to arriving
    }

    /** How much room the edges below [row] take, beyond what is between two rows anyway. */
    fun room(row: Int): Double {
        val (leaving, arriving) = lines(row)
        if (leaving + arriving == 0) return 0.0
        return (leaving + arriving) * SPREAD + Geometry.LABEL_OFFSET + Geometry.LABEL_HEIGHT
    }

    /**
     * How far below [row] the first of its edges runs. Below a row that another follows, the
     * line that leads on to that one runs first, and these keep clear of it.
     */
    private fun under(row: Int): Double = if (row < sheet.rows.lastIndex) Geometry.ROW_CONNECTOR_DROP + SPREAD else UNDER

    /** Draws the edges, once the rows are in place. */
    fun draw() {
        val boxes = sheet.boxes
        val arrived = HashMap<Int, Int>()
        var detours = 0
        for ((from, edges) in fans()) {
            val source = boxes[from] ?: continue
            val row = sheet.rowAt(from) ?: continue
            val ordered = edges.filter { it.to in boxes }
                .sortedWith(compareBy({ sheet.rowAt(it.to) != row }, { abs(boxes.getValue(it.to).centerX - source.centerX) }, { it.id }))
            ordered.forEachIndexed { index, edge ->
                val target = boxes.getValue(edge.to)
                val targetRow = sheet.rowAt(edge.to)!!
                val back = targetRow != row || target.centerX <= source.centerX
                val down = way(from, source, (index - (ordered.size - 1) / 2.0) * SPREAD * if (back) 1 else -1)
                val up = way(edge.to, target, 0.0)
                val level = max(sheet.rows[row].bottom, down.last().y) + under(row) + index * SPREAD
                val run = if (targetRow == row) {
                    listOf(Point(down.last().x, max(level, up.last().y + UNDER)), Point(up.last().x, max(level, up.last().y + UNDER)))
                } else {
                    // Around the rows, on a line of its own beside them, and in below the row of what is cancelled.
                    val side = sheet.leftMargin - detours++ * SPREAD
                    val lane = lines(targetRow).first + arrived.merge(targetRow, 1, Int::plus)!! - 1
                    val other = max(sheet.rows[targetRow].bottom, up.last().y) + under(targetRow) + lane * SPREAD
                    listOf(Point(down.last().x, level), Point(side, level), Point(side, other), Point(up.last().x, other))
                }
                val end = up.first().let { Point(it.x, it.y + Geometry.EDGE_END_GAP) }
                val line = Paths.simplified(down + run + up.reversed().dropLast(1) + end)
                // One label for all of them, below the line of the first where it leaves.
                val middle = if (targetRow == row) (run.first().x + run.last().x) / 2 else run.first().x - LABEL_AHEAD - labelWidth / 2
                val label = Box(middle - labelWidth / 2, run.first().y + Geometry.LABEL_OFFSET, labelWidth, Geometry.LABEL_HEIGHT)
                sheet.reach(line)
                sheet.add(edge, Paths.rounded(line, Geometry.CORNER_RADIUS), label.takeIf { index == 0 }, CardTexts.CANCELS)
            }
        }
    }

    /**
     * The way from the bottom of the item [id] to below the outermost region it lies in: out of
     * each region through the gap below what it has come out of so far, and down the strip at
     * the region's left side.
     *
     * @param shift how far from the middle of the item the way begins
     */
    private fun way(id: String, box: Box, shift: Double): List<Point> {
        val reach = max(0.0, box.width / 2 - EDGE_INSET)
        var x = box.centerX + shift.coerceIn(-reach, reach)
        var y = box.bottom
        val points = arrayListOf(Point(x, y))
        for (region in generateSequence(regionOf[id]) { regionOf[it] }) {
            val around = sheet.boxes[region] ?: break
            val strip = around.x + Geometry.REGION_SIDE / 2
            points += Point(x, y + GAP)
            points += Point(strip, y + GAP)
            points += Point(strip, around.bottom)
            x = strip
            y = around.bottom
        }
        return points
    }

    private companion object {
        /** How far below a row the first edge of a place runs, where no other row follows. */
        const val UNDER = 14.0

        /** How far apart the edges of one place run, and leave it. */
        const val SPREAD = 10.0

        /** How far below an item its edge turns towards the side of the region around it. */
        const val GAP = 9.0

        /** How near the corner of a card an edge may leave it. */
        const val EDGE_INSET = 16.0

        /** How far from where an edge that goes around the rows turns towards their side its label sits. */
        const val LABEL_AHEAD = 24.0
    }
}
