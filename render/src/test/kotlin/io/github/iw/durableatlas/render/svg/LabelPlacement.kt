package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.render.svg.compose.PathData
import io.github.iw.durableatlas.render.svg.scene.CardTexts
import io.github.iw.durableatlas.render.svg.scene.SceneBuilder
import io.github.iw.durableatlas.render.svg.text.FontMetricsMeasurer
import io.github.iw.durableatlas.render.svg.text.TextFitter
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max

/**
 * Says what is wrong with the places of the labels of edges in a diagram, going by the positions
 * the diagram records and nothing else.
 *
 * A label is in a good place if it
 * - sits on its own edge or right beside it,
 * - lies in the region that the stretch of the edge beside it runs in, and inside the diagram,
 * - lies on no card or pill, on no other label, on the border of no region and not on the
 *   header of one, and the same for the band of the handlers,
 * - lies on none of the badges an overlay has put on cards and regions.
 *
 * The lines of the edges are looked at as well: none may run through a header.
 */
internal object LabelPlacement {
    /** How far from its edge a label may sit and still be read as belonging to it. */
    const val BESIDE = 14.0

    /** Distance between the points an edge is looked at. */
    private const val GRAIN = 0.5

    /** How much further from the label than its nearest point a point of the edge may be and still count as beside it. */
    private const val NEAR = 1.0

    private val fitter = TextFitter(FontMetricsMeasurer)

    /**
     * @param badges the boxes of the badges that are drawn, in the coordinates of the graph
     * @return one line for everything that is wrong, each starting with the text of the label
     */
    fun problems(graph: PositionedGraph, badges: Collection<Box> = emptyList()): List<String> {
        val problems = ArrayList<String>()
        val areas = areas(graph)
        val labelled = graph.edges.filter { it.label != null }
        val all = Box(0.0, 0.0, graph.width, graph.height)

        for (edge in labelled) {
            val label = edge.label!!
            val box = label.box
            val name = "'${label.text}' of ${edge.from} -> ${edge.to} (${edge.kind}) at ${place(box)}"
            fun problem(text: String) {
                problems += "$name $text"
            }

            if (!all.grown(0.05).contains(box)) problem("lies outside the diagram")
            graph.nodes.filter { it.box.intersects(box) }.forEach { problem("lies on the card '${it.id}'") }
            labelled.filter { it !== edge && it.label!!.box.intersects(box) }
                .forEach { problem("lies on the label '${it.label!!.text}' of ${it.from} -> ${it.to}") }
            for (area in areas) {
                // The border is drawn just inside the box.
                if (area.box.intersects(box) && !area.box.grown(-Geometry.REGION_BORDER).contains(box)) problem("lies on the border of ${area.name}")
                if (area.header.intersects(box)) problem("lies on the header of ${area.name}")
            }
            badges.filter { it.intersects(box) }.forEach { problem("lies on the badge at ${place(it)}") }

            // A label can speak for several edges that lead to one place: it is then set once, for all of them.
            val spoken = listOf(edge) + graph.edges.filter { it.label == null && it.to == edge.to && it.kind == edge.kind && edge.kind == WAKES }
            val lines = spoken.map { dense(PathData.points(it.path)) }
            val line = lines.flatten()
            val gaps = line.map { gap(box, it) }
            val nearest = gaps.min()
            if (nearest > BESIDE && !between(box, lines)) problem("is ${"%.1f".format(Locale.ROOT, nearest)} away from its edge")
            // The stretch of the edge the label sits beside: what is level with it or straight above or below it, and
            // not far away; at the end of an edge, where nothing is, the points nearest to it. And the regions it runs in.
            val reach = max(nearest + NEAR, BESIDE)
            val beside = line.filterIndexed { index, point -> (point.y in box.y..box.bottom || point.x in box.x..box.right) && gaps[index] <= reach }
                .ifEmpty { line.filterIndexed { index, _ -> gaps[index] <= nearest + NEAR } }
            val runsIn = beside.map { point -> areas.lastOrNull { it.box.contains(point) }?.name }.toSet()
            val liesIn = areas.lastOrNull { it.box.contains(Point(box.centerX, box.centerY)) }?.name
            if (liesIn !in runsIn) {
                problem("lies in ${liesIn ?: "no region"}, but its edge runs in ${runsIn.joinToString(" and ") { it ?: "no region" }} there")
            }
        }
        return problems
    }

    /**
     * Says which edges run through the words of a header: the icon and the title in the top left
     * corner of a region, or of the band of the handlers.
     *
     * @return one line for every edge and header that meet
     */
    fun headerProblems(graph: PositionedGraph): List<String> {
        val areas = areas(graph)
        return graph.edges.flatMap { edge ->
            val line = dense(PathData.points(edge.path))
            areas.filter { area -> line.any(area.header::contains) }
                .map { "the edge ${edge.from} -> ${edge.to} (${edge.kind}) runs through the header of ${it.name} at ${place(it.header)}" }
        }
    }

    /** How far the label of [edge], or a label at [box], is from the line of [edge]. */
    fun distance(edge: PositionedEdge, box: Box = edge.label!!.box): Double = dense(PathData.points(edge.path)).minOf { gap(box, it) }

    /**
     * Whether a label that speaks for several edges lies between two of them: level with it,
     * one of them passes on its left and another on its right.
     */
    private fun between(box: Box, lines: List<List<Point>>): Boolean {
        fun level(point: Point) = point.y in box.y..box.bottom
        val left = lines.indices.filter { index -> lines[index].any { level(it) && it.x < box.x } }
        val right = lines.indices.filter { index -> lines[index].any { level(it) && it.x > box.right } }
        return left.any { one -> right.any { it != one } }
    }

    private const val BAND = "the band of the handlers"
    private const val WAKES = "wakes"

    /** The boxes with headers. Regions are listed from the outside in; the band of the handlers is one more such box. */
    private fun areas(graph: PositionedGraph): List<Area> =
        listOfNotNull(graph.band?.let { Area(BAND, it, listOf(CardTexts.HANDLERS)) }) + graph.regions.map { Area("'${it.id}'", it.box, it.heading) }

    /**
     * A box with a header: a region, or the band of the handlers.
     *
     * @param title the lines of its title
     */
    private class Area(val name: String, val box: Box, title: List<String>) {
        /** The icon and the title, as far as the longest line of the title is measured to reach, and as far down as its last. */
        val header: Box = Box(
            box.x + Geometry.REGION_HEADER_X,
            box.y + Geometry.REGION_HEADER_Y,
            title.maxOf { SceneBuilder.headerWidth(fitter, it) } - 2 * Geometry.REGION_HEADER_X,
            Geometry.CHIP_SIZE + (title.size - 1) * Geometry.REGION_TITLE_LINE,
        )
    }

    private fun place(box: Box): String = "(${box.x}, ${box.y}) ${box.width} x ${box.height}"

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

    /** The distance of [point] from [box]; zero inside it. */
    private fun gap(box: Box, point: Point): Double {
        val dx = max(0.0, max(box.x - point.x, point.x - box.right))
        val dy = max(0.0, max(box.y - point.y, point.y - box.bottom))
        return hypot(dx, dy)
    }
}
