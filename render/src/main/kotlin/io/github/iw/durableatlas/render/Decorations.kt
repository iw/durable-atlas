package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode

/** How strongly something is drawn, compared with the plain notation. */
enum class Emphasis {
    /** Greyed out, for example a node that no execution reached. */
    DIMMED,

    /** Drawn as the notation defines it. */
    NORMAL,

    /** Made to stand out, for example the path one execution took. */
    HIGHLIGHTED,
}

/**
 * A change to how one node, edge or handler is drawn.
 *
 * @param note short text added to the label, for example a hit count
 */
data class Decoration(
    val emphasis: Emphasis = Emphasis.NORMAL,
    val note: String? = null,
)

/**
 * Lets a caller change how single elements of a diagram are drawn without touching the document:
 * a renderer asks it for every node, edge and handler it draws. Returning null leaves the element
 * as it is. Decorations say what is meant, not how it looks, so every format can honour them, and
 * they never move anything: a decorated diagram has the arrangement of the plain one.
 *
 * A region (a loop, a parallel section, a compensation with its steps) asks with its container
 * node. The edges a renderer adds between a container and its children, where the document
 * leaves them implicit, ask with an edge from the container to the child or from the child to
 * the container. Where several elements are drawn as
 * one (edges into a collapsed region, an update with its validator), the strongest emphasis wins
 * and the notes are listed.
 */
interface DiagramDecorator {
    fun node(node: IrNode): Decoration? = null

    fun edge(edge: IrEdge): Decoration? = null

    /** Applies to the handler group of [entryPoint]; not asked for the main workflow method. */
    fun entryPoint(entryPoint: EntryPoint): Decoration? = null

    companion object {
        /** Changes nothing. */
        val NONE: DiagramDecorator = object : DiagramDecorator {}
    }
}
