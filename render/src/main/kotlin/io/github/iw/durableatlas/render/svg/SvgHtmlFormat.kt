package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.render.DiagramFormat
import io.github.iw.durableatlas.render.DiagramOverlay
import io.github.iw.durableatlas.render.NodeLimit
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.PreparedDiagram
import io.github.iw.durableatlas.render.Simplification

/**
 * Writes each workflow as a small HTML page with the SVG diagram inside it. In a page, unlike in
 * an image, the links of the diagram work, and the browser can zoom and scroll it. The page has
 * no script.
 *
 * A page made by [io.github.iw.durableatlas.render.DiagramFormats.configured] shares the format of the
 * SVG files written with it, so a workflow that was arranged for its file is not arranged again
 * for its page, and one whose file was given up or drawn more simply has a page to match.
 */
class SvgHtmlFormat internal constructor(private val svg: SvgFormat) : DiagramFormat {
    constructor(options: SvgOptions = SvgOptions()) : this(SvgFormat(options))

    val options: SvgOptions
        get() = svg.options

    override val name: String = NAME
    override val extension: String = "html"

    override fun prepare(document: IrDocument, options: PageOptions): PreparedDiagram =
        wrap(document, svg.prepare(document, options), options)

    override fun simplifications(document: IrDocument, options: PageOptions): List<Simplification.Level> =
        svg.simplifications(document, options)

    /**
     * The page of [document] with its diagram arranged exactly as in [drawn], an SVG file written
     * for the same document earlier; see [SvgFormat.restyle].
     *
     * @throws IllegalArgumentException if [drawn] is not a diagram of the document
     */
    fun restyle(document: IrDocument, drawn: String, options: PageOptions = PageOptions()): PreparedDiagram =
        wrap(document, svg.restyle(document, drawn, options), options)

    private fun wrap(document: IrDocument, diagram: PreparedDiagram, options: PageOptions): PreparedDiagram =
        object : PreparedDiagram {
            override val limit: NodeLimit
                get() = diagram.limit

            override val simplification: Simplification?
                get() = diagram.simplification

            override fun render(overlay: DiagramOverlay): String =
                page(DiagramNames.title(document, overlay), diagram.render(overlay), options.indexFile)
        }

    private fun page(title: String, svg: String, index: String?): String = buildString {
        val background = if (options.background == Background.PAPER) Palette.PAPER else Palette.WHITE
        append("<!doctype html>\n")
        append("<html lang=\"en\">\n")
        append("<head>\n")
        append("<meta charset=\"utf-8\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("<title>").append(Xml.text(title)).append("</title>\n")
        append("<style>\n")
        append("body{margin:0;background:").append(background).append(";font-family:Inter,system-ui,Arial,sans-serif}\n")
        append("nav{padding:12px 16px 0;font-size:13px}\n")
        append("nav a{color:").append(Palette.FLOW).append("}\n")
        append("main{overflow:auto}\n")
        append("main svg{display:block;margin:0 auto}\n")
        append("</style>\n")
        append("</head>\n")
        append("<body>\n")
        if (index != null) {
            append("<nav><a href=\"").append(Xml.attribute(Links.encodePath(index))).append("\">All workflows</a></nav>\n")
        }
        append("<main>\n")
        append(svg)
        append("</main>\n")
        append("</body>\n")
        append("</html>\n")
    }

    companion object {
        const val NAME: String = "html"
    }
}
