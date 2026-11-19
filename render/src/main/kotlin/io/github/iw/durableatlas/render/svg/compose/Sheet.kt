package io.github.iw.durableatlas.render.svg.compose

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Point
import io.github.iw.durableatlas.render.svg.PositionedEdge
import io.github.iw.durableatlas.render.svg.PositionedLabel
import io.github.iw.durableatlas.render.svg.rounded
import io.github.iw.durableatlas.render.svg.scene.Scene
import io.github.iw.durableatlas.render.svg.scene.SceneCard
import io.github.iw.durableatlas.render.svg.scene.SceneEdge
import io.github.iw.durableatlas.render.svg.scene.SceneItem
import io.github.iw.durableatlas.render.svg.scene.SceneRegion
import kotlin.math.max
import kotlin.math.min

/**
 * The diagram while it is being put together: where everything has been placed so far, and the
 * edges that have been drawn. Positions are final but for one move of the whole at the end.
 *
 * @param topOf the item of the main flow that each item is, or is nested in
 */
internal class Sheet(private val scene: Scene, val topOf: Map<String, String>) {
    val boxes = LinkedHashMap<String, Box>()
    val edges = ArrayList<PositionedEdge>()

    /** The edge of the scene that each of [edges] was drawn for, in the same order. */
    val drawn = ArrayList<SceneEdge>()

    /** The extent of each row of the main flow, top to bottom. */
    val rows = ArrayList<Box>()
    var band: Box? = null

    /**
     * What the ways into the end of a handler take up beside its steps, where that end is drawn,
     * by the id of the handler's card: lines that pass its steps keep clear of this as well.
     */
    val detours = HashMap<String, Box>()

    private val rowOf = HashMap<String, Int>()

    /** How far lines and labels reach beyond the rows and the band. */
    private var extent: Box? = null

    fun assign(item: SceneItem, row: Int) {
        rowOf[item.id] = row
    }

    /** The row the node [id] lies in; null for what is not part of the main flow. */
    fun rowAt(id: String): Int? = topOf[id]?.let(rowOf::get)

    fun add(edge: SceneEdge, path: String, label: Box?, text: String? = edge.label) {
        drawn += edge
        edges += PositionedEdge(
            from = edge.from,
            to = edge.to,
            kind = when (edge.kind) {
                EdgeKind.FLOW -> "flow"
                EdgeKind.EXCEPTION -> "exception"
                EdgeKind.TIMEOUT -> "timeout"
                EdgeKind.WAKES -> "wakes"
                EdgeKind.CANCELS -> "cancels"
            },
            path = path,
            // One label can speak for several edges, in other words than those of this one.
            label = if (label != null && text != null) {
                PositionedLabel(text, label.rounded(), lines = edge.labelLines.takeIf { it.size > 1 && text == edge.label }.orEmpty())
            } else {
                null
            },
            sources = edge.sources,
        )
        label?.let(::reach)
    }

    /** Notes that something is drawn in [box], which may lie outside the rows and the band. */
    fun reach(box: Box) {
        extent = extent?.union(box) ?: box
    }

    fun reach(points: List<Point>) {
        Box.around(points.map { Box(it.x, it.y, 0.0, 0.0) })?.let(::reach)
    }

    /** Everything that is drawn lies in this box. */
    fun bounds(): Box = Box.around(rows + listOfNotNull(band, extent) + boxes.values) ?: Box(0.0, 0.0, 0.0, 0.0)

    /** Where lines run that go around the rows, on either side. */
    val leftMargin: Double
        get() = min(0.0, band?.x ?: 0.0) - Geometry.DETOUR_MARGIN

    val rightMargin: Double
        get() = max(rows.maxOfOrNull { it.right } ?: 0.0, band?.right ?: 0.0) + Geometry.DETOUR_MARGIN

    /** The boxes of the cards of the main flow, which lines should not run over. */
    fun mainCards(): Map<String, Box> {
        val cards = LinkedHashMap<String, Box>()
        fun visit(item: SceneItem) {
            when (item) {
                is SceneCard -> boxes[item.id]?.let { cards[item.id] = it }
                is SceneRegion -> item.children.forEach(::visit)
            }
        }
        scene.main.forEach(::visit)
        return cards
    }

    /** What the icon and the title of each region of the main flow take up, which lines should not run over either. */
    fun mainHeaders(): List<Box> {
        val headers = ArrayList<Box>()
        fun visit(item: SceneItem) {
            if (item !is SceneRegion) return
            boxes[item.id]?.let { headers += Geometry.header(it, item.minWidth, item.title.size) }
            item.children.forEach(::visit)
        }
        scene.main.forEach(::visit)
        return headers
    }

    /** The boxes of the top level of a row, without the one called [except]. */
    fun rowItems(row: Int, except: String): List<Box> =
        scene.main.filter { rowOf[it.id] == row && it.id != except }.map { boxes.getValue(it.id) }
}
