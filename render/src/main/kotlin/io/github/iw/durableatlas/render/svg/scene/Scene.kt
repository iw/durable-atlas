package io.github.iw.durableatlas.render.svg.scene

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Shape
import io.github.iw.durableatlas.render.svg.engine.Flow

/**
 * What a diagram shows, with the size of every piece but no positions yet: the cards and regions
 * of the main flow, the handlers with what they run, and the edges between all of them.
 */
internal class Scene(
    val main: List<SceneItem>,
    /** Edges between items of the main flow. */
    val mainEdges: List<SceneEdge>,
    val handlers: List<SceneHandler>,
    /** Edges to a node that is woken: from the card of a handler, or from the start of the workflow method. */
    val wakes: List<SceneEdge>,
    /** Edges between a handler's steps and the main flow, or between two handlers. */
    val loose: List<SceneEdge>,
    /** Edges inside the main flow from where a cancellation scope is cancelled to what that cancels. */
    val cancels: List<SceneEdge> = emptyList(),
)

internal sealed interface SceneItem {
    /** Id of the node of the document the item stands for. */
    val id: String
}

/**
 * A card or pill.
 *
 * @param kind the construct, as named in the style data
 * @param nodes further nodes of the document this card stands for and that are not drawn
 * @param entryPoints the entry points of the document this card stands for
 */
internal class SceneCard(
    override val id: String,
    val kind: String,
    val shape: Shape,
    var width: Double,
    val height: Double,
    val kindLabel: String,
    val name: List<String>,
    val nodes: List<String> = emptyList(),
    val entryPoints: List<String> = emptyList(),
) : SceneItem

/**
 * A box around other items.
 *
 * @param title the lines of its title, which is too long for one line if there are several
 * @param minWidth the width its header needs
 * @param flow the direction its children follow each other in
 */
internal class SceneRegion(
    override val id: String,
    val kind: String,
    val title: List<String>,
    val minWidth: Double,
    val flow: Flow,
    val children: List<SceneItem>,
) : SceneItem

/**
 * @param label what the edge says, on one line; null if it says nothing
 * @param labelLines the same as it is drawn: on several lines if it is too long for one
 * @param labelWidth the room the label takes; zero without a label
 * @param sources positions, in the document's list of edges, of the edges drawn as this one
 */
internal class SceneEdge(
    val id: String,
    val from: String,
    val to: String,
    val kind: EdgeKind,
    val label: String?,
    val labelLines: List<String>,
    val labelWidth: Double,
    val sources: List<Int>,
) {
    val labelHeight: Double = Geometry.labelHeight(labelLines.size)
}

/** A handler: its card and, if it runs anything, the steps it runs. */
internal class SceneHandler(
    val card: SceneCard,
    val body: List<SceneItem>,
    /** Edges between the card and the body, and inside the body. */
    val edges: List<SceneEdge>,
)
