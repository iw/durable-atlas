package io.github.iw.durableatlas.render.mermaid

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.render.DiagramFormat
import io.github.iw.durableatlas.render.DiagramOverlay
import io.github.iw.durableatlas.render.NodeLimit
import io.github.iw.durableatlas.render.PageContent
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.PreparedDiagram
import io.github.iw.durableatlas.render.layout.DiagramLayout
import io.github.iw.durableatlas.render.layout.LayoutBuilder
import io.github.iw.durableatlas.render.markdown.MarkdownPage

/**
 * Writes each workflow as a Markdown page with the Mermaid diagram in a fenced block, which Git
 * hosts draw in place. Child workflows are linked below the diagram, because links inside a
 * Mermaid diagram are switched off on most hosts.
 */
object MermaidFormat : DiagramFormat {
    override val name: String = "mermaid"
    override val extension: String = "md"

    override fun prepare(document: IrDocument, options: PageOptions): PreparedDiagram =
        Prepared(LayoutBuilder.build(document, options.render), options)

    override fun indexSection(): String = NOTATION

    private class Prepared(private val layout: DiagramLayout, private val options: PageOptions) : PreparedDiagram {
        override val limit: NodeLimit
            get() = layout.limit

        override fun render(overlay: DiagramOverlay): String {
            val mermaid = MermaidWriter(layout, options.render.maxLabelLength, overlay.decorator).write()
            val content = PageContent.of(layout.document, layout.limit, options, overlay)
            return MarkdownPage.render(content, "```mermaid\n$mermaid```")
        }
    }

    private val NOTATION = """
        ## Mermaid notation

        - **Rounded ends**: where a flow starts and where it stops (`start`, `end`, `continueAsNew`, `fail`).
        - **Rectangle**: an activity, by its activity type; `(local)` marks a local activity. A prefix names other
          steps: `nexus:`, `signal external:`, `side effect:`, `compensation:`.
        - **Rectangle with double sides**: a child workflow. Its own diagram is linked below the diagram.
        - **Hexagon**: the workflow waits, for a timer or until the listed properties change (`await`).
        - **Diamond**: a branch, or a version gate with its change id. Conditions are on the arrows.
        - **Box**: a loop, with its `loop` node and an arrow back to it; a `parallel` section, with one inner box per
          branch between `fork` and `join`; the steps a compensation runs; or the signal, query and update handlers.
        - **Dashed, coloured rectangle**: a call that could not be resolved, with the reason. The page lists
          where each one is.
        - **Dotted arrow**: a handler changes a property that the step it points to is waiting on.
        - **Labelled arrow**: a condition, the type of a caught exception, or `timeout`.
    """.trimIndent()
}
