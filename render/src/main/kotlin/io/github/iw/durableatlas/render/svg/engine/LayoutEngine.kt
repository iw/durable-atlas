package io.github.iw.durableatlas.render.svg.engine

import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Point

/** The direction in which the steps of a graph follow each other. */
internal enum class Flow { RIGHT, DOWN }

internal enum class Side { NORTH, EAST, SOUTH, WEST }

internal data class Insets(val top: Double, val left: Double, val bottom: Double, val right: Double) {
    companion object {
        val NONE = Insets(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * A place on the border of a node where edges attach, for nodes that need other places than the
 * usual two.
 *
 * @param offset distance from the top of a west or east side, or from the left of a north or south side
 */
internal class Anchor(val id: String, val side: Side, val offset: Double)

/**
 * A box to place. With [children] it is a group that is sized around them: [width] and [height]
 * are then the least it may measure, [flow] is the direction its children follow each other in,
 * and [padding] is kept free around them.
 *
 * Edges arrive in the middle of the side a node turns towards where the flow comes from, and
 * leave in the middle of the opposite side, unless they name one of the [anchors].
 *
 * @param point whether the node is only a place where edges meet: it has no extent, and edges
 *   that arrive there run on as the one edge that leaves it
 */
internal class EngineNode(
    val id: String,
    val width: Double,
    val height: Double,
    val children: List<EngineNode> = emptyList(),
    val flow: Flow = Flow.RIGHT,
    val padding: Insets = Insets.NONE,
    val anchors: List<Anchor> = emptyList(),
    val point: Boolean = false,
)

/**
 * A connection to route. Its ends may be any two nodes of the graph, also in different groups.
 * An edge from a node to a group around it runs to the end of that group: to its border, on the
 * side its steps flow towards. The group may be the node's own or one further out, and the edge
 * then leaves the groups in between on that side. No edge leads from a group to a node inside it.
 *
 * All edges that leave a node at the same place run together at first and part on the way, and
 * likewise those that arrive at one place. An edge that must be told apart from the others at a
 * node is shifted along the side it attaches to.
 *
 * @param fromAnchor the anchor of the source to leave at; null for the usual place
 * @param fromShift how far along the side from its anchor the edge leaves: downwards on a west
 *   or east side, to the right on a north or south side
 * @param labelWidth size of the label to make room for; zero for none
 * @param straight whether to keep this edge straight rather than others
 * @param short whether to keep this edge short rather than others
 */
internal class EngineEdge(
    val id: String,
    val from: String,
    val to: String,
    val fromAnchor: String? = null,
    val toAnchor: String? = null,
    val fromShift: Double = 0.0,
    val toShift: Double = 0.0,
    val labelWidth: Double = 0.0,
    val labelHeight: Double = 0.0,
    val straight: Boolean = false,
    val short: Boolean = false,
)

/**
 * A graph to lay out. The order of nodes and edges matters: what comes first is placed first.
 *
 * @param fans ids of nodes of the outermost level whose edges should part evenly to both sides
 *   of the node, where the rest of the graph allows it
 * @param joins ids of nodes of the outermost level that should sit in the middle of the edges
 *   that arrive at them, where the rest of the graph allows it
 */
internal class EngineGraph(
    val flow: Flow,
    val nodes: List<EngineNode>,
    val edges: List<EngineEdge>,
    val fans: List<String> = emptyList(),
    val joins: List<String> = emptyList(),
)

/** Where everything went. All positions are relative to the top left corner of the graph. */
internal class EngineLayout(
    val width: Double,
    val height: Double,
    val boxes: Map<String, Box>,
    val routes: Map<String, EngineRoute>,
)

/** The corners an edge runs through, first to last, and the place of its label. */
internal class EngineRoute(val points: List<Point>, val label: Box?)

/**
 * Places the nodes of a graph and routes its edges. This is the only door to the layout library:
 * nothing outside the implementation sees its types, so it can be replaced.
 */
internal fun interface LayoutEngine {
    fun layout(graph: EngineGraph): EngineLayout
}
