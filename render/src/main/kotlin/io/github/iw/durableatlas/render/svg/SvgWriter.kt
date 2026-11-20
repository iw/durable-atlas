package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.Decoration
import io.github.iw.durableatlas.render.DiagramOverlay
import io.github.iw.durableatlas.render.Emphasis
import io.github.iw.durableatlas.render.NodeLimit
import io.github.iw.durableatlas.render.PageContent
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.layout.LayoutDecorations
import io.github.iw.durableatlas.render.layout.singleLine
import io.github.iw.durableatlas.render.svg.scene.CardTexts
import io.github.iw.durableatlas.render.svg.text.FontWeight
import io.github.iw.durableatlas.render.svg.text.InterFont
import io.github.iw.durableatlas.render.svg.text.TextFitter
import io.github.iw.durableatlas.render.svg.text.TextMeasurer
import io.github.iw.durableatlas.render.svg.text.TextStyle
import io.github.iw.durableatlas.render.svg.text.TextStyles
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.math.max

/**
 * Draws a positioned graph as an SVG document.
 *
 * Drawing never moves anything. Whatever depends on the overlay (emphasis, badges, the lines of
 * text an overlay adds) is drawn on top of, or below, a diagram body whose coordinates depend
 * only on the positioned graph and the document. A plain diagram and an overlay of it therefore
 * line up when one is laid over the other.
 *
 * The order of the layers is fixed: regions, edges, cards, edge labels, legend.
 *
 * @param document the document the graph was laid out for, in canonical order
 */
internal class SvgWriter(
    private val document: IrDocument,
    private val graph: PositionedGraph,
    private val options: SvgOptions,
    private val page: PageOptions,
    private val limit: NodeLimit,
    private val overlay: DiagramOverlay,
    measurer: TextMeasurer,
) {
    private val fitter = TextFitter(measurer)
    private val out = StringBuilder()
    private val nodes: Map<String, IrNode> = document.nodes.associateBy { it.id }
    private val entryPoints: Map<String, EntryPoint> = document.entryPoints.associateBy { it.id }
    private val decorations = LayoutDecorations(overlay.decorator)
    private val content = PageContent.of(document, limit, page, overlay)
    private val legend = Legend(graph, options.legend, fitter)
    private val regionKinds: Map<String, String> = graph.regions.associate { it.id to it.kind }

    /** What an edge calls the things it joins. */
    private val names: Map<String, String> =
        graph.regions.associate { it.id to it.title } +
            graph.nodes.associate { it.id to it.name.joinToString(" ").ifEmpty { it.kindLabel } }

    // Everything above the diagram body depends on the document and on what is written with it,
    // not on the overlay, so the body starts at the same height in every drawing of it.
    private val heading: Heading?
    private val warningTop: Double
    private val warning: List<String>
    private val notes: List<String>
    private val innerWidth: Double
    private val bodyTop: Double
    private val notesTop: Double
    private val legendTop: Double
    private val width: Double
    private val height: Double

    init {
        val least = max(graph.width, max(legend.minWidth, MIN_WIDTH))
        innerWidth = least
        heading = if (DiagramNames.needsHeading(document, page)) heading(innerWidth) else null
        warningTop = Geometry.MARGIN + if (heading == null) 0.0 else Geometry.HEADING_LINE + NOTICE_GAP
        warning = content.warning?.let { wrap("Warning: $it", innerWidth) }.orEmpty()
        notes = (
            listOfNotNull(overlay.title, content.limitNotice, content.simplifiedNotice, content.skippedNotice, content.leftOutNotice) +
                content.notices +
                overlay.facts.map { "${it.name}: ${it.value}" }
            ).flatMap { wrap(it.singleLine(), innerWidth) }
        bodyTop = warningTop + if (warning.isEmpty()) 0.0 else warning.size * Geometry.NOTICE_LINE + NOTICE_GAP
        notesTop = bodyTop + graph.height + if (notes.isEmpty()) 0.0 else NOTICE_GAP
        val notesBottom = notesTop + notes.size * Geometry.NOTICE_LINE
        legendTop = notesBottom + Geometry.LEGEND_GAP
        legend.arrange(innerWidth)
        width = innerWidth + 2 * Geometry.MARGIN
        height = (if (legend.isEmpty) notesBottom else legendTop + legend.height) + Geometry.MARGIN
    }

    fun write(): String {
        line(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" class=\"atlas\" " +
                "width=\"${width.text()}\" height=\"${height.text()}\" viewBox=\"0 0 ${width.text()} ${height.text()}\" " +
                "role=\"img\" aria-label=\"${Xml.attribute(DiagramNames.spoken(document, overlay))}\">",
        )
        line("<title>${Xml.text(DiagramNames.title(document, overlay))}</title>")
        metadata()
        defs()
        if (options.background == Background.PAPER) {
            line("<rect class=\"paper\" width=\"${width.text()}\" height=\"${height.text()}\" fill=\"${Palette.PAPER}\"/>")
        }
        heading?.let(::heading)
        notices("warning", warning, warningTop, Palette.WARNING)

        line("<g class=\"diagram\" transform=\"translate(${Geometry.MARGIN.text()} ${bodyTop.text()})\">")
        line("<g class=\"regions\">")
        graph.band?.let(::band)
        graph.regions.forEach(::region)
        line("</g>")
        line("<g class=\"edges\">")
        // Edges that share a stretch are drawn over each other there; the one that stands out most goes on top.
        graph.edges.sortedBy { decoration(it)?.emphasis ?: Emphasis.NORMAL }.forEach(::edge)
        line("</g>")
        line("<g class=\"nodes\">")
        graph.nodes.forEach(::node)
        line("</g>")
        line("<g class=\"edge-labels\">")
        graph.edges.forEach(::edgeLabel)
        line("</g>")
        line("</g>")

        notices("notes", notes, notesTop, Palette.NOTE)
        if (!legend.isEmpty) legend()
        line("</svg>")
        return out.toString()
    }

    // --- head ---

    private fun metadata() {
        line("<metadata>")
        line(
            "<atlas:diagram xmlns:atlas=\"${SvgDiagrams.NAMESPACE}\"" +
                attribute("workflowType", document.workflowType) +
                attribute("interface", document.interfaceName) +
                attribute("implementation", document.implementation) +
                attribute("module", document.module) +
                attribute("commit", document.commit) +
                attribute("modelMode", PageContent.modeName(document.modelMode)) +
                attribute("nodes", document.nodes.size.toString()) +
                attribute("unknownNodes", document.nodes.count { it.kind == NodeKind.UNKNOWN }.toString()) +
                (if (document.skippedCalls.isEmpty()) "" else attribute("skippedCalls", document.skippedCalls.size.toString())) +
                (if (document.leftOutCalls.isEmpty()) "" else attribute("leftOutCalls", document.leftOutCalls.size.toString())) +
                attribute("collapsedRegions", limit.collapsedRegions.toString()) +
                // Only a diagram that is drawn more simply than asked for says so, and how long the drawing as asked was given.
                page.render.simplification?.let { attribute(SvgDiagrams.SIMPLIFIED, it.level.key) + attribute(SvgDiagrams.TIME_LIMIT, it.limit.toString()) }.orEmpty() +
                ">",
        )
        line("<atlas:layout>${Xml.text(graph.encode())}</atlas:layout>")
        line("</atlas:diagram>")
        line("</metadata>")
    }

    private fun defs() {
        line("<defs>")
        line("<style>")
        if (options.embedFont) {
            for (weight in FontWeight.entries) {
                val data = Base64.getEncoder().encodeToString(InterFont.bytes(weight))
                line(
                    "@font-face{font-family:${InterFont.FAMILY};font-style:normal;font-weight:${weight.css};" +
                        "src:url(data:font/otf;base64,$data) format(\"opentype\")}",
                )
            }
        }
        line(".atlas text{font-family:${InterFont.FAMILY},system-ui,Arial,sans-serif}")
        line(".atlas .k{font-size:${Geometry.KIND_SIZE.text()}px;font-weight:700;letter-spacing:${Geometry.KIND_TRACKING}em}")
        line(".atlas .n{font-size:${Geometry.NAME_SIZE.text()}px;font-weight:600;fill:${Palette.INK}}")
        line(".atlas .pill .n{fill:${Palette.WHITE}}")
        line(".atlas .pill .k{fill:${Palette.WHITE};fill-opacity:${Geometry.PILL_KIND_OPACITY}}")
        line(".atlas .el{font-size:${Geometry.LABEL_SIZE.text()}px;font-weight:600;text-anchor:middle}")
        line(".atlas .e{fill:none;stroke-width:${Geometry.EDGE_WIDTH}}")
        line(".atlas .timeout .e{stroke-dasharray:${Geometry.TIMEOUT_DASH}}")
        line(".atlas .wakes .e{stroke-dasharray:${Geometry.WAKES_DASH};stroke-linecap:round}")
        // What only some diagrams draw is defined in those alone, so that the others stay as they are.
        val cancels = graph.edges.any { it.kind == CANCELS } || legend.entries.any { it.edge && it.key == CANCELS }
        if (cancels) line(".atlas .cancels .e{stroke-dasharray:${Geometry.CANCELS_DASH}}")
        line(".atlas .highlighted .e{stroke-width:${Geometry.EDGE_HIGHLIGHT_WIDTH}}")
        line(".atlas .lg{font-size:${Geometry.LEGEND_SIZE.text()}px;font-weight:500;fill:${Palette.LEGEND_TEXT}}")
        line(
            ".atlas .lt{font-size:${Geometry.KIND_SIZE.text()}px;font-weight:700;" +
                "letter-spacing:${Geometry.LEGEND_TITLE_TRACKING}em;fill:${Palette.LEGEND_TITLE}}",
        )
        line(".atlas .ht{font-size:${Geometry.HEADING_SIZE.text()}px;font-weight:600;fill:${Palette.INK}}")
        line(".atlas .hi{font-size:${Geometry.HEADING_DETAIL_SIZE.text()}px;font-weight:500;fill:${Palette.LEGEND_TITLE}}")
        line(".atlas .note{font-size:${Geometry.NOTICE_SIZE.text()}px;font-weight:600}")
        line(".atlas .badge{font-size:${Geometry.BADGE_SIZE.text()}px;font-weight:700;text-anchor:middle;fill:${Palette.WHITE}}")
        line("</style>")
        // The soft shadow under every card: 0 1 3, ten percent ink.
        line(
            "<filter id=\"$SHADOW\" x=\"-10%\" y=\"-20%\" width=\"120%\" height=\"150%\">" +
                "<feGaussianBlur in=\"SourceAlpha\" stdDeviation=\"1.5\"/><feOffset dy=\"1\" result=\"blur\"/>" +
                "<feFlood flood-color=\"${Palette.SHADOW}\" flood-opacity=\"${Palette.SHADOW_OPACITY}\"/>" +
                "<feComposite in2=\"blur\" operator=\"in\"/>" +
                "<feMerge><feMergeNode/><feMergeNode in=\"SourceGraphic\"/></feMerge></filter>",
        )
        for ((name, colour) in ARROWS) {
            if (name == CANCELS && !cancels) continue
            val size = (ARROW_BOX * Geometry.EDGE_WIDTH).text()
            line(
                "<marker id=\"$ARROW$name\" viewBox=\"0 0 10 10\" refX=\"8.5\" refY=\"5\" markerUnits=\"userSpaceOnUse\" " +
                    "markerWidth=\"$size\" markerHeight=\"$size\" orient=\"auto\"><path d=\"M1 1.5L9 5 1 8.5z\" fill=\"$colour\"/></marker>",
            )
        }
        val used = (
            graph.nodes.map { it.kind } + graph.regions.map { it.kind } +
                listOfNotNull(StyleKinds.HANDLERS.takeIf { graph.band != null }) + legend.kinds
            ).toSet()
        for (kind in StyleKinds.legendOrder + StyleKinds.HANDLERS) {
            if (kind !in used) continue
            line("<symbol id=\"$ICON$kind\" viewBox=\"0 0 20 20\">${ConstructStyles.of(kind).icon}</symbol>")
        }
        line("</defs>")
    }

    // --- heading ---

    /** @param implementation the class that implements the workflow, or as much of its end as there is room for */
    private class Heading(val workflowType: String, val implementation: String?)

    /**
     * The line that tells this diagram from the others of its workflow type, or says that its
     * workflow has no type of its own: the type, or what the workflow is instead, and the class
     * that implements it. Where the line is too long, the class loses its beginning, which says
     * least, and the title of the file has it in full.
     */
    private fun heading(width: Double): Heading {
        val type = fitter.truncate(DiagramNames.workflow(document).singleLine(), TextStyles.HEADING, width)
        val room = width - fitter.width(type, TextStyles.HEADING) - Geometry.HEADING_GAP
        val implementation = document.implementation.singleLine().takeIf { it.isNotEmpty() }?.let { fitter.ending(it, TextStyles.HEADING_DETAIL, room) }
        return Heading(type, implementation)
    }

    private fun heading(heading: Heading) {
        val baseline = Geometry.MARGIN + baseline(Geometry.HEADING_LINE, Geometry.HEADING_SIZE)
        line("<g class=\"heading\">")
        line(
            "<text x=\"${Geometry.MARGIN.text()}\" y=\"${baseline.text()}\"><tspan class=\"ht\">${Xml.text(heading.workflowType)}</tspan>" +
                (heading.implementation?.let { "<tspan class=\"hi\" dx=\"${Geometry.HEADING_GAP.text()}\">${Xml.text(it)}</tspan>" } ?: "") +
                "</text>",
        )
        line("</g>")
    }

    // --- regions ---

    private fun band(box: Box) {
        line("<g class=\"band\" data-kind=\"${StyleKinds.HANDLERS}\">")
        line("<title>${CardTexts.HANDLERS}</title>")
        regionBox(box, StyleKinds.HANDLERS, listOf(CardTexts.HANDLERS))
        line("</g>")
    }

    private fun region(region: PositionedRegion) {
        val node = nodes.getValue(region.id)
        val decoration = decorations.combine(listOf(overlay.decorator.node(node)))
        open(
            link = sourceLink(node),
            id = region.id,
            kind = region.kind,
            classes = "region",
            decoration = decoration,
            title = region.title,
            extra = attribute("data-parent", region.parent),
        )
        regionBox(region.box, region.kind, region.heading)
        badge(region.box, decoration)
        close(link = sourceLink(node))
    }

    /** @param title the lines of the title, the first beside the icon and the others below it */
    private fun regionBox(box: Box, kind: String, title: List<String>) {
        val style = ConstructStyles.of(kind)
        val tint = Palette.tints.getValue(kind)
        val half = Geometry.REGION_BORDER / 2
        line(
            "<rect x=\"${(box.x + half).text()}\" y=\"${(box.y + half).text()}\" width=\"${(box.width - 2 * half).text()}\" " +
                "height=\"${(box.height - 2 * half).text()}\" rx=\"${(Geometry.REGION_RADIUS - half).text()}\" " +
                "fill=\"${tint.fill}\" stroke=\"${tint.border}\" stroke-width=\"${Geometry.REGION_BORDER}\"/>",
        )
        val chipX = box.x + Geometry.REGION_HEADER_X
        val chipY = box.y + Geometry.REGION_HEADER_Y
        line(
            "<rect x=\"${chipX.text()}\" y=\"${chipY.text()}\" width=\"${Geometry.CHIP_SIZE.text()}\" height=\"${Geometry.CHIP_SIZE.text()}\" " +
                "rx=\"${Geometry.CHIP_RADIUS.text()}\" fill=\"${style.colour}\"/>",
        )
        val inset = (Geometry.CHIP_SIZE - Geometry.CHIP_ICON) / 2
        icon(kind, chipX + inset, chipY + inset, Geometry.CHIP_ICON)
        val baseline = chipY + (Geometry.CHIP_SIZE - Geometry.KIND_SIZE * Geometry.LINE_NORMAL) / 2 + Geometry.KIND_SIZE * Geometry.ASCENT
        title.forEachIndexed { index, words ->
            text("k", chipX + Geometry.CHIP_SIZE + Geometry.CHIP_GAP, baseline + index * Geometry.REGION_TITLE_LINE, words, fill = style.colour)
        }
    }

    // --- cards ---

    private fun node(node: PositionedNode) {
        val style = ConstructStyles.of(node.kind)
        val decoration = decoration(node)
        val source = if (node.handler) node.nodes.firstNotNullOfOrNull { nodes[it]?.takeIf { start -> start.kind == NodeKind.START } } else nodes[node.id]
        val link = childLink(source) ?: sourceLink(source)
        val shown = if (source?.interfaceName != null) "implementations of ${source.interfaceName}" else "this workflow type"
        val others = childDiagrams(source).size.takeIf { it > 1 }?.let { " ($it diagrams of $shown, none linked)" }.orEmpty()
        val box = node.box
        open(
            link = link,
            id = node.id,
            kind = node.kind,
            classes = if (node.shape == Shape.PILL) "node pill" else if (node.handler) "node handler" else "node",
            decoration = decoration,
            title = (listOf(node.kindLabel) + node.name.joinToString(" ").ifEmpty { null }).filterNotNull().joinToString(": ") + others,
            extra = attribute("data-parent", node.parent) +
                attribute("data-nodes", list(node.nodes)) +
                attribute("data-entry-points", list(node.entryPoints)),
        )
        if (decoration?.emphasis == Emphasis.HIGHLIGHTED) ring(box, node.shape, style.colour)
        when (node.shape) {
            Shape.PILL -> pill(box, style)
            Shape.OUTLINE_CARD -> outlineCard(box, style)
            Shape.CARD -> card(box, style)
        }
        val textX = box.x + if (node.shape == Shape.PILL) Geometry.PILL_TEXT_INSET else Geometry.TILE_WIDTH + Geometry.CARD_TEXT_INSET + 1
        val lines = TextBlock(box, node.name.size)
        text("k", textX, lines.kind, node.kindLabel, fill = style.colour.takeIf { node.shape != Shape.PILL })
        node.name.forEachIndexed { index, part -> text("n", textX, lines.name(index), part) }
        badge(box, decoration)
        close(link)
    }

    private fun card(box: Box, style: ConstructStyle) {
        base(box, Geometry.CARD_RADIUS, Palette.WHITE)
        val r = Geometry.CARD_RADIUS.text()
        val tile = "M${(box.x + Geometry.CARD_RADIUS).text()} ${box.y.text()}H${(box.x + Geometry.TILE_WIDTH).text()}V${box.bottom.text()}" +
            "H${(box.x + Geometry.CARD_RADIUS).text()}A$r $r 0 0 1 ${box.x.text()} ${(box.bottom - Geometry.CARD_RADIUS).text()}" +
            "V${(box.y + Geometry.CARD_RADIUS).text()}A$r $r 0 0 1 ${(box.x + Geometry.CARD_RADIUS).text()} ${box.y.text()}Z"
        line("<path d=\"$tile\" fill=\"${style.colour}\"/>")
        border(box, style.colour, dashed = false)
        icon(style.kind, box.x + (Geometry.TILE_WIDTH - Geometry.ICON_SIZE) / 2, box.centerY - Geometry.ICON_SIZE / 2, Geometry.ICON_SIZE)
    }

    private fun outlineCard(box: Box, style: ConstructStyle) {
        base(box, Geometry.CARD_RADIUS, Palette.WHITE)
        border(box, style.colour, dashed = true)
        val divider = box.x + Geometry.TILE_WIDTH + Geometry.CARD_BORDER / 2
        line(
            "<path d=\"M${divider.text()} ${(box.y + Geometry.CARD_BORDER).text()}V${(box.bottom - Geometry.CARD_BORDER).text()}\" fill=\"none\" " +
                "stroke=\"${style.colour}\" stroke-width=\"${Geometry.CARD_BORDER}\" stroke-dasharray=\"${Geometry.OUTLINE_DASH}\"/>",
        )
        icon(style.kind, box.x + (Geometry.TILE_WIDTH - Geometry.ICON_SIZE) / 2, box.centerY - Geometry.ICON_SIZE / 2, Geometry.ICON_SIZE)
    }

    private fun pill(box: Box, style: ConstructStyle) {
        base(box, box.height / 2, style.colour)
        icon(style.kind, box.x + Geometry.PILL_ICON_INSET, box.centerY - Geometry.ICON_SIZE / 2, Geometry.ICON_SIZE)
    }

    /** The filled shape of a card or pill, with its shadow. */
    private fun base(box: Box, radius: Double, fill: String) {
        line(
            "<rect x=\"${box.x.text()}\" y=\"${box.y.text()}\" width=\"${box.width.text()}\" height=\"${box.height.text()}\" " +
                "rx=\"${radius.text()}\" fill=\"$fill\" filter=\"url(#$SHADOW)\"/>",
        )
    }

    private fun border(box: Box, colour: String, dashed: Boolean) {
        val half = Geometry.CARD_BORDER / 2
        line(
            "<rect x=\"${(box.x + half).text()}\" y=\"${(box.y + half).text()}\" width=\"${(box.width - 2 * half).text()}\" " +
                "height=\"${(box.height - 2 * half).text()}\" rx=\"${(Geometry.CARD_RADIUS - half).text()}\" fill=\"none\" " +
                "stroke=\"$colour\" stroke-width=\"${Geometry.CARD_BORDER}\"" +
                (if (dashed) " stroke-dasharray=\"${Geometry.OUTLINE_DASH}\"" else "") + "/>",
        )
    }

    /** A soft outline around a card that an overlay makes stand out. */
    private fun ring(box: Box, shape: Shape, colour: String) {
        val grown = box.grown(RING / 2)
        val radius = (if (shape == Shape.PILL) box.height / 2 else Geometry.CARD_RADIUS) + RING / 2
        line(
            "<rect class=\"ring\" x=\"${grown.x.text()}\" y=\"${grown.y.text()}\" width=\"${grown.width.text()}\" height=\"${grown.height.text()}\" " +
                "rx=\"${radius.text()}\" fill=\"none\" stroke=\"$colour\" stroke-opacity=\"0.35\" stroke-width=\"${RING.text()}\"/>",
        )
    }

    /** The note of an overlay, such as a hit count, at the top right corner. */
    private fun badge(box: Box, decoration: Decoration?) {
        val note = decoration?.note ?: return
        if (decoration.emphasis == Emphasis.DIMMED) return
        val width = max(Geometry.BADGE_HEIGHT, fitter.width(note, TextStyles.BADGE) + 2 * Geometry.BADGE_PADDING).roundedUpTo(1.0)
        val badge = Geometry.badge(box, width)
        line(
            "<rect class=\"badge-box\" x=\"${badge.x.text()}\" y=\"${badge.y.text()}\" width=\"${width.text()}\" height=\"${Geometry.BADGE_HEIGHT.text()}\" " +
                "rx=\"${(Geometry.BADGE_HEIGHT / 2).text()}\" fill=\"${Palette.INK}\" stroke=\"${Palette.WHITE}\" stroke-width=\"1.5\"/>",
        )
        text("badge", badge.centerX, badge.y + baseline(Geometry.BADGE_HEIGHT, Geometry.BADGE_SIZE), note)
    }

    /**
     * How a card is decorated. The card of a handler stands for its entry points and for the nodes
     * they start at: it takes what the overlay says about any of them, the strongest emphasis first.
     */
    private fun decoration(node: PositionedNode): Decoration? =
        if (node.handler) {
            val starts = node.nodes.mapNotNull { nodes[it] }.filter { it.kind == NodeKind.START }
            val said = node.entryPoints.mapNotNull { entryPoints[it] }.mapNotNull(overlay.decorator::entryPoint) +
                starts.mapNotNull(overlay.decorator::node)
            if (said.isEmpty()) null else decorations.combine(said)
        } else {
            decorations.combine(listOf(nodes[node.id]?.let(overlay.decorator::node)))
        }

    // --- edges ---

    private fun decoration(edge: PositionedEdge): Decoration? =
        decorations.combine(edge.sources.map { overlay.decorator.edge(document.edges[it]) })

    private fun edge(edge: PositionedEdge) {
        val decoration = decoration(edge)
        val dimmed = decoration?.emphasis == Emphasis.DIMMED
        val style = if (dimmed) MUTED else edge.kind
        line("<g${edgeAttributes("edge", edge, decoration)}>")
        line("<title>${Xml.text(edgeTitle(edge, decoration))}</title>")
        line("<path class=\"e\" d=\"${edge.path}\" stroke=\"${ARROWS.getValue(style)}\" marker-end=\"url(#$ARROW$style)\"/>")
        line("</g>")
    }

    private fun edgeLabel(edge: PositionedEdge) {
        val label = edge.label ?: return
        val decoration = decoration(edge)
        val dimmed = decoration?.emphasis == Emphasis.DIMMED
        val colour = ARROWS.getValue(if (dimmed) MUTED else edge.kind)
        line("<g${edgeAttributes("edge-label", edge, decoration)}>")
        // What the edge says in full, as the document has it: the label may have been broken into lines, or cut.
        val said = edge.sources.mapNotNull { document.edges[it].label?.singleLine()?.takeIf(String::isNotEmpty) }.distinct()
        line("<title>${Xml.text(if (said.isEmpty() || edge.kind == "wakes" || edge.kind == "cancels") label.text else said.joinToString(", "))}</title>")
        // The patch keeps the label readable where a line runs behind it. It has the colour of what the label lies on.
        val box = label.box
        val behind = label.inside?.let { regionKinds[it] }?.let { Palette.tints.getValue(it).fill }
            ?: graph.band?.takeIf { it.contains(Point(box.centerX, box.centerY)) }?.let { Palette.tints.getValue(StyleKinds.HANDLERS).fill }
            ?: Palette.PAPER.takeIf { options.background == Background.PAPER }
        if (behind != null) {
            line(
                "<rect x=\"${box.x.text()}\" y=\"${box.y.text()}\" width=\"${box.width.text()}\" height=\"${box.height.text()}\" " +
                    "rx=\"3\" fill=\"$behind\"/>",
            )
        }
        label.shown.forEachIndexed { index, words ->
            text("el", box.centerX, box.y + baseline(Geometry.LABEL_HEIGHT, Geometry.LABEL_SIZE) + index * Geometry.LABEL_LINE, words, fill = colour)
        }
        line("</g>")
    }

    private fun edgeAttributes(classes: String, edge: PositionedEdge, decoration: Decoration?): String =
        " class=\"$classes ${edge.kind}${emphasisClass(decoration)}\"" +
            attribute("data-from", edge.from) +
            attribute("data-to", edge.to) +
            attribute("data-kind", edge.kind)

    private fun edgeTitle(edge: PositionedEdge, decoration: Decoration?): String {
        val from = names[edge.from] ?: edge.from
        val to = names[edge.to] ?: edge.to
        val text = when (edge.kind) {
            "wakes" -> {
                val shared = edge.sources.mapNotNull { document.edges[it].label?.singleLine()?.takeIf(String::isNotEmpty) }.distinct()
                "$from wakes $to" + if (shared.isEmpty()) "" else " (${shared.joinToString(", ")})"
            }
            "cancels" -> "$from cancels $to"
            else -> {
                val labels = edge.sources.mapNotNull { document.edges[it].label?.singleLine()?.takeIf(String::isNotEmpty) }.distinct()
                val said = (listOfNotNull(edge.kind.takeIf { it != "flow" }) + labels).joinToString(", ")
                "$from → $to" + if (said.isEmpty()) "" else ": $said"
            }
        }
        return text + (decoration?.note?.let { " — $it" } ?: "")
    }

    // --- legend and notes ---

    private fun legend() {
        line("<g class=\"legend\" transform=\"translate(0 ${legendTop.text()})\">")
        val rule = Geometry.LEGEND_RULE
        line("<path d=\"M0 ${(rule / 2).text()}H${width.text()}\" fill=\"none\" stroke=\"${Palette.RULE}\" stroke-width=\"$rule\"/>")
        text("lt", Geometry.MARGIN + 4, Geometry.LEGEND_PADDING + 4 + Geometry.KIND_SIZE * Geometry.ASCENT, "LEGEND")
        for (cell in legend.cells) {
            val x = Geometry.MARGIN + cell.x
            val y = cell.y
            val entry = cell.entry
            line("<g class=\"legend-entry\"${attribute("data-kind", entry.key)}>")
            if (entry.edge) {
                val middle = y + Geometry.LEGEND_ROW / 2
                val colour = ARROWS.getValue(entry.key)
                line("<g class=\"${entry.key}\"><path class=\"e\" d=\"M${(x + 1).text()} ${middle.text()}H${(x + 22).text()}\" stroke=\"$colour\"/></g>")
                line("<path d=\"M${(x + 21).text()} ${(middle - 4).text()}l7 4-7 4z\" fill=\"$colour\"/>")
            } else {
                val style = ConstructStyles.of(entry.key)
                val tile = Geometry.LEGEND_TILE
                if (style.outlined) {
                    line(
                        "<rect x=\"${(x + 0.75).text()}\" y=\"${(y + 0.75).text()}\" width=\"${(tile - 1.5).text()}\" height=\"${(tile - 1.5).text()}\" " +
                            "rx=\"${(Geometry.CHIP_RADIUS - 0.75).text()}\" fill=\"${Palette.WHITE}\" stroke=\"${style.colour}\" stroke-width=\"1.5\" " +
                            "stroke-dasharray=\"${Geometry.OUTLINE_DASH}\"/>",
                    )
                } else {
                    line(
                        "<rect x=\"${x.text()}\" y=\"${y.text()}\" width=\"${tile.text()}\" height=\"${tile.text()}\" " +
                            "rx=\"${Geometry.CHIP_RADIUS.text()}\" fill=\"${style.colour}\"/>",
                    )
                }
                val inset = (tile - Geometry.CHIP_ICON) / 2
                icon(entry.key, x + inset, y + inset, Geometry.CHIP_ICON)
            }
            text("lg", x + entry.lead, y + baseline(Geometry.LEGEND_ROW, Geometry.LEGEND_SIZE), entry.name)
            line("</g>")
        }
        line("</g>")
    }

    private fun notices(name: String, lines: List<String>, top: Double, colour: String) {
        if (lines.isEmpty()) return
        line("<g class=\"$name\">")
        lines.forEachIndexed { index, text ->
            text("note", Geometry.MARGIN, top + index * Geometry.NOTICE_LINE + baseline(Geometry.NOTICE_LINE, Geometry.NOTICE_SIZE), text, fill = colour)
        }
        line("</g>")
    }

    /** Breaks [text] into lines of at most [width] at its spaces. */
    private fun wrap(text: String, width: Double): List<String> {
        val lines = ArrayList<String>()
        var current = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val longer = if (current.isEmpty()) word else "$current $word"
            if (current.isNotEmpty() && fitter.width(longer, TextStyles.NOTICE) > width) {
                lines += current
                current = word
            } else {
                current = longer
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    // --- links ---

    private fun sourceLink(node: IrNode?): String? {
        val base = options.sourceBaseUrl?.takeIf { it.isNotBlank() } ?: return null
        val location = node?.loc ?: return null
        val root = base.replace("{commit}", Links.encodeSegment(document.commit)).trimEnd('/')
        return "$root/${Links.encodePath(location.file)}#L${location.line}"
    }

    /**
     * The diagram of the workflow a child workflow card starts, if it was written next to this
     * one. A child is known by its workflow type, and by the interface of its stub where the
     * type alone does not say which workflow is meant. Where several diagrams still fit, nothing
     * says which of them is started, and the card links none rather than the wrong one.
     */
    private fun childLink(node: IrNode?): String? = childDiagrams(node).singleOrNull()?.let(Links::encodePath)

    /**
     * The diagrams written next to this one that show a workflow a child workflow card may
     * start: those of the interface its stub was made for, if the card names one, and else
     * those of its workflow type.
     */
    private fun childDiagrams(node: IrNode?): List<String> =
        when {
            node?.kind != NodeKind.CHILD_WORKFLOW -> emptyList()
            node.interfaceName != null -> page.interfaceFiles[node.interfaceName].orEmpty()
            else -> node.name?.let { page.diagramFiles[it] }.orEmpty()
        }

    // --- elements ---

    private fun open(link: String?, id: String, kind: String, classes: String, decoration: Decoration?, title: String, extra: String) {
        if (link != null) line("<a xlink:href=\"${Xml.attribute(link)}\">")
        val dimmed = decoration?.emphasis == Emphasis.DIMMED
        line(
            "<g" + attribute("id", id) + attribute("data-kind", kind) + " class=\"$classes${emphasisClass(decoration)}\"" + extra +
                (if (dimmed) " opacity=\"${Geometry.DIMMED_OPACITY}\"" else "") + ">",
        )
        line("<title>${Xml.text(title + (decoration?.note?.let { " — $it" } ?: ""))}</title>")
    }

    private fun close(link: String?) {
        line("</g>")
        if (link != null) line("</a>")
    }

    private fun emphasisClass(decoration: Decoration?): String = when (decoration?.emphasis) {
        Emphasis.DIMMED -> " dimmed"
        Emphasis.HIGHLIGHTED -> " highlighted"
        else -> ""
    }

    private fun icon(kind: String, x: Double, y: Double, size: Double) {
        line("<use xlink:href=\"#$ICON$kind\" x=\"${x.text()}\" y=\"${y.text()}\" width=\"${size.text()}\" height=\"${size.text()}\"/>")
    }

    private fun text(classes: String, x: Double, baseline: Double, text: String, fill: String? = null) {
        line(
            "<text class=\"$classes\" x=\"${x.text()}\" y=\"${baseline.text()}\"" + (fill?.let { " fill=\"$it\"" } ?: "") + ">" +
                Xml.text(text) + "</text>",
        )
    }

    private fun attribute(name: String, value: String?): String = if (value == null) "" else " $name=\"${Xml.attribute(value)}\""

    /** A list of ids as an attribute value. Ids may hold any character, so they are written as a JSON array. */
    private fun list(values: List<String>): String? =
        if (values.isEmpty()) null else Json.encodeToString(ListSerializer(String.serializer()), values)

    private fun line(text: String) {
        out.append(text).append('\n')
    }

    /** Where the lines of text on a card or pill sit: the block is centred between top and bottom. */
    private class TextBlock(private val box: Box, names: Int) {
        private val kindHeight = Geometry.KIND_SIZE * Geometry.LINE_NORMAL
        private val nameHeight = Geometry.NAME_SIZE * Geometry.NAME_LINE_HEIGHT
        private val top = box.y + (box.height - kindHeight - if (names == 0) 0.0 else Geometry.TEXT_GAP + names * nameHeight) / 2

        val kind: Double
            get() = top + Geometry.KIND_SIZE * Geometry.ASCENT

        fun name(index: Int): Double =
            top + kindHeight + Geometry.TEXT_GAP + index * nameHeight +
                (nameHeight - Geometry.NAME_SIZE * Geometry.LINE_NORMAL) / 2 + Geometry.NAME_SIZE * Geometry.ASCENT
    }

    private companion object {
        const val SHADOW = "atlas-shadow"
        const val ARROW = "atlas-arrow-"
        const val ICON = "atlas-icon-"
        const val MUTED = "muted"
        const val CANCELS = "cancels"

        /** The colour of the arrowhead, and of the line, of each edge style. */
        val ARROWS: Map<String, String> = linkedMapOf(
            "flow" to Palette.FLOW,
            "exception" to Palette.EXCEPTION,
            "timeout" to Palette.TIMEOUT,
            "wakes" to Palette.WAKES,
            CANCELS to Palette.CANCELS,
            MUTED to Palette.MUTED,
        )

        /** Side of the arrowhead's box, in line widths. The head itself is about 9.5 long. */
        const val ARROW_BOX = 7.0
        const val RING = 5.0
        const val NOTICE_GAP = 8.0

        /** A diagram is never narrower than this, so that notes and legend have room. */
        const val MIN_WIDTH = 360.0

        /** The distance of a baseline from the top of a line of [height] in which text of [size] is centred. */
        fun baseline(height: Double, size: Double): Double =
            (height - size * Geometry.LINE_NORMAL) / 2 + size * Geometry.ASCENT
    }
}

/** Measures against which the legend is laid out; kept apart so the writer can ask for its size first. */
internal class Legend(graph: PositionedGraph, mode: LegendMode, private val fitter: TextFitter) {
    /**
     * @param key the construct, or the edge style
     * @param lead distance from the left of the entry to its text
     */
    class Entry(val key: String, val name: String, val edge: Boolean, val lead: Double, val width: Double)

    class Cell(val entry: Entry, val x: Double, val y: Double)

    val entries: List<Entry>
    var cells: List<Cell> = emptyList()
        private set
    var height: Double = 0.0
        private set

    init {
        val kinds = (graph.nodes.map { it.kind } + graph.regions.map { it.kind }).toSet()
        val styles = graph.edges.map { it.kind }.toSet()
        val constructs = when (mode) {
            LegendMode.NONE -> emptyList()
            LegendMode.FULL -> ConstructStyles.all
            LegendMode.PRESENT -> ConstructStyles.all.filter { it.kind in kinds }
        }
        val edges = when (mode) {
            LegendMode.NONE -> emptyList()
            LegendMode.FULL -> EDGES
            LegendMode.PRESENT -> EDGES.filter { it.first in styles }
        }
        entries = constructs.map { entry(it.kind, it.legend, edge = false) } + edges.map { entry(it.first, it.second, edge = true) }
    }

    val isEmpty: Boolean
        get() = entries.isEmpty()

    /** The constructs whose icons the legend shows. */
    val kinds: Set<String>
        get() = entries.filter { !it.edge }.map { it.key }.toSet()

    private val widest: Double = entries.maxOfOrNull { it.width } ?: 0.0

    /** The least width a diagram with this legend has: room for the title and one column. */
    val minWidth: Double
        get() = if (isEmpty) 0.0 else LEAD + widest

    /**
     * Lays the entries out in rows, in a grid of as many columns as fit [width], eight at most;
     * and of no more columns than it takes to fill that many rows evenly, so that the last row
     * is not left with an entry or two.
     */
    fun arrange(width: Double) {
        if (isEmpty) return
        val room = width - LEAD
        val gap = Geometry.LEGEND_COLUMN_GAP
        val fitting = ((room + gap) / (widest + gap)).toInt().coerceIn(1, Geometry.LEGEND_COLUMNS)
        val lines = (entries.size + fitting - 1) / fitting
        val columns = (entries.size + lines - 1) / lines
        val column = minOf((room - (columns - 1) * gap) / columns, max(widest, Geometry.LEGEND_COLUMN_MAX))
        cells = entries.mapIndexed { index, entry ->
            Cell(
                entry,
                x = LEAD + (index % columns) * (column + gap),
                y = Geometry.LEGEND_PADDING + (index / columns) * (Geometry.LEGEND_ROW + Geometry.LEGEND_ROW_GAP),
            )
        }
        val rows = (entries.size + columns - 1) / columns
        height = Geometry.LEGEND_PADDING + rows * Geometry.LEGEND_ROW + (rows - 1) * Geometry.LEGEND_ROW_GAP
    }

    private fun entry(key: String, name: String, edge: Boolean): Entry {
        val lead = (if (edge) Geometry.LEGEND_SAMPLE else Geometry.LEGEND_TILE) + Geometry.LEGEND_TILE_GAP
        return Entry(key, name, edge, lead, lead + fitter.width(name, LEGEND_TEXT))
    }

    private companion object {
        val LEGEND_TEXT: TextStyle = TextStyles.LEGEND
        const val LEAD = Geometry.LEGEND_TITLE_WIDTH + Geometry.LEGEND_TITLE_GAP

        /** The edge styles, in the order they are listed, with their names. */
        val EDGES = listOf("flow" to "Flow", "exception" to "Exception", "timeout" to "Timeout", "wakes" to "Wakes an await", "cancels" to "Cancels a step")
    }
}
