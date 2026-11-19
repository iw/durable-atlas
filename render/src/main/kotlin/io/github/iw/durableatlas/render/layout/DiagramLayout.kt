package io.github.iw.durableatlas.render.layout

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.render.NodeLimit

/**
 * What a renderer draws for one document, free of any diagram syntax: which nodes belong to the
 * main flow and to each handler, how regions nest, which edges connect them, and all of it in the
 * order it should be written.
 *
 * A layout does not depend on decorations. A renderer can therefore position a layout once and
 * draw it any number of times with different [io.github.iw.durableatlas.render.DiagramDecorator]s; see
 * [LayoutDecorations].
 */
internal class DiagramLayout(
    /** The document in canonical order. */
    val document: IrDocument,
    val main: List<Item>,
    val handlers: List<HandlerGroup>,
    val edges: List<LaidEdge>,
    val limit: NodeLimit,
)

/**
 * The graphs of the entry points that are drawn as one handler: a single signal, query or update,
 * or an update together with the validator of the same name.
 */
internal class HandlerGroup(
    /** In the order they run: a validator before its update. */
    val entryPoints: List<EntryPoint>,
    val items: List<Item>,
)

internal sealed interface Item {
    /** The node of the document this item stands for. */
    val node: IrNode
}

/**
 * A node drawn on its own.
 *
 * @param caption an extra line for its label, where the node alone does not say what it is: the
 *   start nodes of an update and its validator inside their shared group
 */
internal class Step(override val node: IrNode, val caption: String? = null) : Item

/** A loop with its body. Edges of the loop node attach to a head node inside the region. */
internal class LoopRegion(override val node: IrNode, val body: List<Item>) : Item

/**
 * Any other container that has children, such as a compensation with the steps it runs: its own
 * node, to which its edges attach, followed by what it contains.
 */
internal class ScopeRegion(override val node: IrNode, val body: List<Item>) : Item

/**
 * A parallel section with one lane per branch. The parallel node is drawn twice: edges that
 * enter the section attach to its fork, edges that leave it attach to its join.
 */
internal class ParallelRegion(override val node: IrNode, val lanes: List<List<Item>>) : Item

/** A region drawn as a single node because the document is over the limit. */
internal class CollapsedRegion(override val node: IrNode, val hiddenNodes: Int, val hiddenUnknown: Int) : Item

internal enum class Port {
    /** The node itself; for an expanded parallel section, its fork. */
    NODE,

    /** The join of an expanded parallel section. */
    JOIN,
}

/** The place on a drawn node where an edge starts or ends. */
internal data class Anchor(val nodeId: String, val port: Port = Port.NODE)

internal class LaidEdge(
    val from: Anchor,
    val to: Anchor,
    val kind: EdgeKind,
    /** Caption to show, already reduced to a single line. */
    val label: String?,
    /**
     * The edges of the document drawn as this one edge: usually one, several where a collapsed
     * region joins them. An edge the layout added between a container and its child stands for
     * an edge from one to the other that the document does not contain.
     */
    val sources: List<IrEdge>,
)
