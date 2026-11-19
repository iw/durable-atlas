package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.render.DiagramFormat
import io.github.iw.durableatlas.render.DiagramOverlay
import io.github.iw.durableatlas.render.NodeLimit
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.PreparedDiagram
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.Simplification
import io.github.iw.durableatlas.render.layout.DiagramLayout
import io.github.iw.durableatlas.render.layout.LayoutBuilder
import io.github.iw.durableatlas.render.limit.Deadline
import io.github.iw.durableatlas.render.limit.DrawingRunner
import io.github.iw.durableatlas.render.limit.DrawingTimeoutException
import io.github.iw.durableatlas.render.svg.compose.DiagramComposer
import io.github.iw.durableatlas.render.svg.compose.RowSearch
import io.github.iw.durableatlas.render.svg.engine.ElkLayoutEngine
import io.github.iw.durableatlas.render.svg.engine.LayoutEngine
import io.github.iw.durableatlas.render.svg.scene.SceneBuilder
import io.github.iw.durableatlas.render.svg.text.FontMetricsMeasurer
import io.github.iw.durableatlas.render.svg.text.TextFitter

/**
 * Writes each workflow as one self-contained SVG file: every construct is a card with its own
 * icon and colour, regions are boxes around their steps, the handlers sit in a band above the
 * main flow, and a legend below explains what appears.
 *
 * The file carries no script and refers to nothing outside itself, apart from the links to
 * source lines and to the diagrams of child workflows. The same document and settings always
 * give the same bytes.
 *
 * A written file also records where everything in it was placed. [restyle] reads that back, so a
 * later drawing of the same workflow, with an overlay, lines up with the file exactly and needs
 * no layout of its own.
 *
 * **Time.** What takes time is finding where the rows of the main flow end. The search places
 * only the steps near each row to learn that (see [RowSearch.NEAR]), which takes about as long
 * for each row however long the flow is. A flow that no row can end in has to be placed whole,
 * and that can take very long. A diagram in which a label is left without room is arranged a
 * second time, with room for it, and seldom a third; all of that is the one arrangement the
 * time is for. With [SvgOptions.timeLimit] set, a diagram that is not arranged in that time is
 * given up: [prepare] throws a [DrawingTimeoutException]. The same workflow can then be asked
 * for more simply, in one of the ways [simplifications] names, each of which is given the same
 * time again: with plain rows, which end where they are full, and with its regions as single
 * nodes too. A simpler diagram says below the drawing what was done; one that is drawn as asked
 * says nothing of time. What a format made of a document, at each level of simplicity, is kept
 * until it is asked for another document, so the page that holds the diagram of a file costs no
 * second arrangement.
 */
class SvgFormat internal constructor(
    val options: SvgOptions,
    private val engines: (Deadline) -> LayoutEngine,
    private val runner: DrawingRunner,
) : DiagramFormat {
    constructor(options: SvgOptions = SvgOptions()) : this(options, ::ElkLayoutEngine, DrawingRunner())

    override val name: String = NAME
    override val extension: String = "svg"

    /** What became of arranging one document with given settings: where everything goes, or the time that was not enough. */
    private class Arrangements(val document: IrDocument) {
        val outcomes = HashMap<RenderOptions, Result<PositionedGraph>>()
    }

    private var last: Arrangements? = null

    override fun prepare(document: IrDocument, options: PageOptions): PreparedDiagram {
        val layout = LayoutBuilder.build(document, options.render)
        return Prepared(layout, arranged(document, layout, options.render), options, this.options)
    }

    override fun simplifications(document: IrDocument, options: PageOptions): List<Simplification.Level> {
        if (options.render.simplification != null) return emptyList()
        return listOfNotNull(
            // Without rows there are no breaks to look for.
            Simplification.Level.PLAIN_ROWS.takeIf { this.options.wrapWidth > 0 },
            // A document over the node limit has its regions as single nodes as it is.
            Simplification.Level.COLLAPSED.takeIf { document.nodes.size <= options.render.maxNodes && LayoutBuilder.hasRegions(document) },
        )
    }

    /**
     * The diagram of [document] arranged exactly as in [drawn], an SVG file this format wrote for
     * the same document earlier. Nothing is laid out again: whatever [PreparedDiagram.render]
     * returns has every card, region and edge where [drawn] has it. A file that was drawn more
     * simply than asked for is redrawn as simply, and says so as it did. A file of an earlier
     * version that says its rows were found with only the steps near each in view is a diagram
     * drawn as asked, since every diagram is drawn so now: it is redrawn without that remark.
     *
     * @throws IllegalArgumentException if [drawn] was not written by this format, or not for a
     *   document with these nodes and edges
     */
    fun restyle(document: IrDocument, drawn: String, options: PageOptions = PageOptions()): PreparedDiagram {
        val page = options.copy(render = options.render.copy(simplification = SvgDiagrams.simplification(drawn)))
        val layout = LayoutBuilder.build(document, page.render)
        val graph = SvgDiagrams.positionedGraph(drawn)
        SvgDiagrams.check(graph, layout.document)
        return Prepared(layout, graph, page, this.options)
    }

    /** Where everything in the diagram of [document] goes, from the last time if it was asked for the same way then. */
    @Synchronized
    private fun arranged(document: IrDocument, layout: DiagramLayout, render: RenderOptions): PositionedGraph {
        val known = last?.takeIf { it.document === document } ?: Arrangements(document).also { last = it }
        val outcome = known.outcomes[render] ?: run {
            val fresh = runCatching { arrange(layout, render) }
            // Only an end that would come again is kept: a failure of another kind is the caller's to see every time.
            if (fresh.isSuccess || fresh.exceptionOrNull() is DrawingTimeoutException) known.outcomes[render] = fresh
            fresh
        }
        return outcome.getOrThrow()
    }

    private fun arrange(layout: DiagramLayout, render: RenderOptions): PositionedGraph =
        runner.run(options.timeLimit) { deadline ->
            val scene = SceneBuilder(layout, FontMetricsMeasurer, render.maxLabelLength).build()
            DiagramComposer(
                scene = scene,
                engine = engines(deadline),
                fitter = TextFitter(FontMetricsMeasurer),
                wrapWidth = options.wrapWidth.toDouble(),
                rows = when (render.simplification?.level) {
                    null -> RowSearch.NEAR
                    Simplification.Level.PLAIN_ROWS, Simplification.Level.COLLAPSED -> RowSearch.PLAIN
                },
                deadline = deadline,
            ).compose()
        }

    internal class Prepared(
        private val layout: DiagramLayout,
        val graph: PositionedGraph,
        private val page: PageOptions,
        private val options: SvgOptions,
    ) : PreparedDiagram {
        override val limit: NodeLimit
            get() = layout.limit

        override val simplification: Simplification?
            get() = page.render.simplification

        override fun render(overlay: DiagramOverlay): String =
            SvgWriter(layout.document, graph, options, page, layout.limit, overlay, FontMetricsMeasurer).write()
    }

    companion object {
        const val NAME: String = "svg"
    }
}
