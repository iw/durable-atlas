package io.github.iw.durableatlas.render.mermaid

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.render.DiagramDecorator
import io.github.iw.durableatlas.render.NodeLimit
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.layout.LayoutBuilder

/**
 * Draws a workflow as a Mermaid `flowchart TD`.
 *
 * | Element | Drawn as |
 * |---|---|
 * | `start`, `end`, `continueAsNew`, `fail` | stadium labelled with the kind |
 * | `activity`, `localActivity` | rectangle with the activity name; local ones end in `(local)` |
 * | `childWorkflow` | subroutine shape with the workflow type |
 * | `nexusOperation`, `signalExternal`, `sideEffect`, `compensation`, `cancelScope`, `cancelExternal` | rectangle with a kind prefix |
 * | `upsertSearchAttributes`, `upsertMemo` | cylinder with a kind prefix and the keys written, where the document names them, followed by `…` where it says that there are more |
 * | `compensation` with steps | box titled `compensation` around that rectangle and the steps |
 * | `timer`, `await` | hexagon with the duration, or the properties awaited and the timeout |
 * | `branch`, `versionGate` | diamond; a version gate shows its change id |
 * | `loop` | box titled with the loop label around a `loop` head node and the body |
 * | `parallel` | box titled `parallel` with a `fork` node, one inner box per branch, and a `join` node |
 * | `unknown` | dashed rectangle in a warning colour, labelled with the reason |
 * | handlers | one box per handler inside a `handlers` box; an update shares a box with its validator |
 * | a step that is not waited for | its own shape, with a line `not awaited` or `waits for start only` |
 * | `childWorkflow` that outlives its parent | a line `parent close: abandon` |
 * | `parallel` that is not waited for | box titled `parallel: not awaited`, or `parallel: waits for start only`; its lanes end at a node that says the same in place of the join, and the edges that leave it start at its `fork` |
 * | `parallel` that is left when its first lane ends | box titled `parallel: first to finish`, with a node that says the same in place of the join |
 * | `wakes` edge | dotted arrow from the start node of the handler, or of the workflow method, labelled with the properties in question |
 * | `cancels` edge | dotted arrow labelled `cancels`, from the `cancelScope` node to what it cancels |
 * | `exception`, `timeout` edges | arrows labelled with the exception type or `timeout` |
 *
 * The output depends only on the arguments: the same input gives the same bytes. Lines end in
 * `\n`. A decorator changes classes, styles and label notes, never the statements or their order.
 *
 * @throws IllegalArgumentException if the document is not consistent (see `IrDocument.validate`)
 */
object MermaidRenderer {
    /** The Mermaid source of the diagram, without a Markdown fence around it. */
    fun render(
        document: IrDocument,
        options: RenderOptions = RenderOptions(),
        decorator: DiagramDecorator = DiagramDecorator.NONE,
    ): String = diagram(document, options, decorator).text

    /** As [render], with what there is to report about the diagram. */
    fun diagram(
        document: IrDocument,
        options: RenderOptions = RenderOptions(),
        decorator: DiagramDecorator = DiagramDecorator.NONE,
    ): MermaidDiagram {
        val layout = LayoutBuilder.build(document, options)
        return MermaidDiagram(MermaidWriter(layout, options.maxLabelLength, decorator).write(), layout.limit)
    }
}

class MermaidDiagram internal constructor(
    /** Mermaid source, ending in a line break. */
    val text: String,
    val limit: NodeLimit,
)
