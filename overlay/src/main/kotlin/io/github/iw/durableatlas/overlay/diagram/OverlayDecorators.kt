package io.github.iw.durableatlas.overlay.diagram

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.overlay.coverage.WorkflowCoverage
import io.github.iw.durableatlas.overlay.mapping.EdgeRef
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.mapping.Hits
import io.github.iw.durableatlas.render.Decoration
import io.github.iw.durableatlas.render.DiagramDecorator
import io.github.iw.durableatlas.render.Emphasis

/** The words overlays put on diagrams. */
internal object OverlayText {
    const val NEVER_RUN: String = "never run"

    /** A hit count as a badge: `×3`, and `×3?` if none of the three is certain. */
    fun count(hits: Int, uncertain: Int = 0): String = "×$hits" + if (hits > 0 && uncertain >= hits) "?" else ""
}

/**
 * Draws the coverage of a workflow type: what no execution reached is greyed and says so, what
 * was reached carries its hit count. What a history cannot show, the handlers of queries, is
 * left as it is.
 */
internal class CoverageDecorator(coverage: WorkflowCoverage) : DiagramDecorator {
    private val nodes = coverage.nodes.associateBy { it.id }
    private val edges = coverage.edges.associateBy { EdgeRef(it.from, it.to, it.kind, it.label) }
    private val entryPoints = coverage.entryPoints.associateBy { it.id }

    override fun node(node: IrNode): Decoration? {
        val found = nodes[node.id]?.takeIf { it.recorded } ?: return null
        return decoration(found.hits, found.uncertain)
    }

    override fun edge(edge: IrEdge): Decoration? {
        val found = edges[EdgeRef(edge)]?.takeIf { it.recorded } ?: return null
        // An edge that was never taken is grey; saying so on every one of them would bury the diagram.
        return if (found.hits == 0) Decoration(Emphasis.DIMMED) else decoration(found.hits, found.uncertain)
    }

    override fun entryPoint(entryPoint: EntryPoint): Decoration? {
        val found = entryPoints[entryPoint.id]?.takeIf { it.recorded } ?: return null
        return decoration(found.hits, 0)
    }

    private fun decoration(hits: Int, uncertain: Int): Decoration =
        if (hits == 0) Decoration(Emphasis.DIMMED, OverlayText.NEVER_RUN) else Decoration(note = OverlayText.count(hits, uncertain))
}

/**
 * Draws one execution: what it reached stands out, with a count where it was reached more than
 * once, and everything else is greyed.
 */
internal class ExecutionDecorator(private val trace: ExecutionTrace) : DiagramDecorator {
    override fun node(node: IrNode): Decoration = decoration(trace.nodes[node.id])

    override fun edge(edge: IrEdge): Decoration = decoration(trace.edges[EdgeRef(edge)])

    override fun entryPoint(entryPoint: EntryPoint): Decoration = decoration(trace.entryPoints[entryPoint.id])

    private fun decoration(hits: Hits?): Decoration = when {
        hits == null || hits.count == 0 -> Decoration(Emphasis.DIMMED)
        hits.count == 1 && hits.uncertain == 0 -> Decoration(Emphasis.HIGHLIGHTED)
        else -> Decoration(Emphasis.HIGHLIGHTED, OverlayText.count(hits.count, hits.uncertain))
    }
}
