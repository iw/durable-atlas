package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.render.limit.DrawingTimeoutException
import io.github.iw.durableatlas.render.mermaid.MermaidFormat
import io.github.iw.durableatlas.render.svg.SvgFormat
import io.github.iw.durableatlas.render.svg.SvgHtmlFormat
import io.github.iw.durableatlas.render.svg.SvgOptions

/**
 * One way of writing a workflow diagram to a file. Settings that only one format has belong to
 * that format's own constructor.
 */
interface DiagramFormat {
    /** Name of the format on the command line and in the index, for example `mermaid`. */
    val name: String

    /** Extension of the files this format writes, without the dot. */
    val extension: String

    /**
     * Does everything that decides what is drawn where. The result can be written any number of
     * times, plain or with different overlays, and every one of them has the same arrangement.
     *
     * @throws IllegalArgumentException if the document is not consistent (see `IrDocument.validate`)
     * @throws DrawingTimeoutException if the format allows a diagram only so much time and this
     *   one took longer; one of the [simplifications] may still be drawn in time
     */
    fun prepare(document: IrDocument, options: PageOptions = PageOptions()): PreparedDiagram

    /**
     * The simpler ways in which this format can draw [document] where drawing it with [options]
     * takes too long, the one that leaves out least first. Each is asked for with
     * [RenderOptions.simplification]. A way that would draw this document no differently is not
     * among them. See [DrawingWatch.Attempt.prepare], which tries them in turn.
     */
    fun simplifications(document: IrDocument, options: PageOptions = PageOptions()): List<Simplification.Level> = emptyList()

    /** A Markdown section for the index page, for example a legend; null for none. */
    fun indexSection(): String? = null
}

/** A diagram that is laid out and ready to be written. */
interface PreparedDiagram {
    val limit: NodeLimit

    /** How the diagram is drawn more simply than was asked for because drawing it as asked took too long; null if it is not. */
    val simplification: Simplification?
        get() = null

    /** The content of the diagram file. Lines end in `\n`, and so does the text. */
    fun render(overlay: DiagramOverlay = DiagramOverlay.NONE): String
}

/**
 * What is put on top of a prepared diagram, for example by a history overlay.
 *
 * @param decorator how single nodes, edges and handlers are emphasised and annotated
 * @param title heading of the diagram; the workflow type when null
 * @param notices plain-text remarks shown in the header, each on its own
 * @param facts properties added to the header, after the standard ones
 */
data class DiagramOverlay(
    val decorator: DiagramDecorator = DiagramDecorator.NONE,
    val title: String? = null,
    val notices: List<String> = emptyList(),
    val facts: List<PageFact> = emptyList(),
) {
    companion object {
        val NONE: DiagramOverlay = DiagramOverlay()
    }
}

/** One property in the header of a diagram. Both parts are plain text. */
data class PageFact(val name: String, val value: String)

/**
 * Settings of one diagram file that do not depend on the format.
 *
 * @param render what is drawn
 * @param diagramFiles file names of the diagrams of other workflows in the same format, by
 *   workflow type and relative to this file; child workflows are linked through it. One type has
 *   several files when several workflows share a type name
 * @param indexFile file name of the page that lists all workflows, linked from the header
 * @param interfaceFiles the same files by the fully qualified name of the workflow interface
 *   each diagram's workflow implements; a child workflow that names the interface of its stub
 *   is linked through it
 * @param endpoints where the activities that make requests link to on the endpoints page written
 *   with the diagram; `null` where none is
 */
data class PageOptions(
    val render: RenderOptions = RenderOptions(),
    val diagramFiles: Map<String, List<String>> = emptyMap(),
    val indexFile: String? = null,
    val interfaceFiles: Map<String, List<String>> = emptyMap(),
    val endpoints: EndpointLinks? = null,
)

/** The formats this tool can write. */
object DiagramFormats {
    /** SVG diagrams with the default settings; [svg] makes one with others. */
    val SVG: DiagramFormat = SvgFormat()
    val MERMAID: DiagramFormat = MermaidFormat

    /** The formats written when none is asked for, in the order their files are listed in the index. */
    val all: List<DiagramFormat> = listOf(SVG, MERMAID)

    fun named(name: String): DiagramFormat? = all.firstOrNull { it.name == name }

    fun svg(options: SvgOptions): DiagramFormat = SvgFormat(options)

    /** HTML pages that wrap the SVG diagrams. Not one of [all]: it is written on request, next to the SVG files. */
    fun html(options: SvgOptions = SvgOptions()): DiagramFormat = SvgHtmlFormat(options)

    /**
     * [formats] with the settings of the SVG diagrams applied: an SVG format among them draws with
     * [options], and with [html] the HTML pages are written too, after the SVG files if there are
     * any and else after everything.
     */
    fun configured(formats: List<DiagramFormat>, options: SvgOptions, html: Boolean = false): List<DiagramFormat> {
        // The pages hold the diagrams of the SVG files, so both come from one format, which arranges each workflow once.
        val diagrams = SvgFormat(options)
        val pages = if (html) listOf(SvgHtmlFormat(diagrams)) else emptyList()
        val drawn = formats.any { it is SvgFormat }
        val configured = formats.flatMap { format -> if (format is SvgFormat) listOf(diagrams) + pages else listOf(format) }
        return if (drawn) configured else configured + pages
    }
}
