package io.github.iw.durableatlas.render.mermaid

import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.Decoration
import io.github.iw.durableatlas.render.Emphasis
import io.github.iw.durableatlas.render.DiagramDecorator
import io.github.iw.durableatlas.render.layout.Anchor
import io.github.iw.durableatlas.render.layout.CollapsedRegion
import io.github.iw.durableatlas.render.layout.DiagramLayout
import io.github.iw.durableatlas.render.layout.HandlerGroup
import io.github.iw.durableatlas.render.layout.Item
import io.github.iw.durableatlas.render.layout.LaidEdge
import io.github.iw.durableatlas.render.layout.LayoutDecorations
import io.github.iw.durableatlas.render.layout.LoopRegion
import io.github.iw.durableatlas.render.layout.NodeText
import io.github.iw.durableatlas.render.layout.ParallelRegion
import io.github.iw.durableatlas.render.layout.Port
import io.github.iw.durableatlas.render.layout.ScopeRegion
import io.github.iw.durableatlas.render.layout.Step
import io.github.iw.durableatlas.render.layout.singleLine

/** Brackets that give a Mermaid node its shape. */
private enum class Shape(val open: String, val close: String) {
    RECTANGLE("[", "]"),
    ROUNDED("(", ")"),
    STADIUM("([", "])"),
    SUBROUTINE("[[", "]]"),
    HEXAGON("{{", "}}"),
    DIAMOND("{", "}"),
    CIRCLE("((", "))"),
    CYLINDER("[(", ")]"),
}

/** Looks that are not a matter of shape. Later entries win over earlier ones on the same node. */
private enum class Look(val className: String, val css: String) {
    UNKNOWN("unknown", "fill:#fff7e6,stroke:#b45309,color:#7c2d12,stroke-width:2px,stroke-dasharray:6 4"),
    COLLAPSED("collapsed", "fill:#eef2ff,stroke:#4338ca,color:#312e81,stroke-width:2px"),
    DIMMED("dimmed", "fill:#f3f4f6,stroke:#d1d5db,color:#9ca3af"),
    HIGHLIGHTED("highlighted", "fill:#dbeafe,stroke:#1d4ed8,color:#1e3a8a,stroke-width:3px"),
}

private const val DIMMED_EDGE = "stroke:#d1d5db"
private const val HIGHLIGHTED_EDGE = "stroke:#1d4ed8,stroke-width:3px"
private const val INDENT = "    "
private const val NOTE_SEPARATOR = " · "

/**
 * Writes a [DiagramLayout] as a Mermaid flowchart, one statement per line. Decorations add
 * classes, styles and label notes; the statements and their order come from the layout alone.
 */
internal class MermaidWriter(
    private val layout: DiagramLayout,
    private val max: Int,
    decorator: DiagramDecorator,
) {
    private val decorations = LayoutDecorations(decorator)
    private val ids = MermaidIds(layout.document.nodes.map { it.id })
    private val joins = HashMap<String, String>()
    private val lines = mutableListOf<String>()
    private val looks = Look.entries.associateWith { mutableListOf<String>() }
    private val groupStyles = mutableListOf<String>()

    fun write(): String {
        lines += "flowchart TD"
        layout.main.forEach { item(it, depth = 1) }
        handlers(layout.handlers.filter { it.items.isNotEmpty() })
        layout.edges.forEach(::edge)
        looks()
        lines += groupStyles
        edgeStyles()
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    // --- nodes and groups ---

    private fun item(item: Item, depth: Int) {
        val node = item.node
        when (item) {
            is Step ->
                node(item, ids.node(node.id), shapeOf(node.kind), NodeText.lines(node, max, item.caption), depth, withNote = true)
            is CollapsedRegion -> {
                val id = ids.node(node.id)
                node(item, id, Shape.ROUNDED, NodeText.collapsed(item, max), depth, withNote = true)
                looks.getValue(Look.COLLAPSED) += id
            }
            is LoopRegion -> group(ids.derived("${ids.node(node.id)}_region"), NodeText.regionTitle(node, max), depth) {
                node(item, ids.node(node.id), Shape.CIRCLE, NodeText.lines(node, max), depth + 1, withNote = true)
                item.body.forEach { item(it, depth + 1) }
            }
            is ScopeRegion -> group(ids.derived("${ids.node(node.id)}_region"), NodeText.regionTitle(node, max), depth) {
                node(item, ids.node(node.id), shapeOf(node.kind), NodeText.lines(node, max), depth + 1, withNote = true)
                item.body.forEach { item(it, depth + 1) }
            }
            is ParallelRegion -> group(ids.derived("${ids.node(node.id)}_region"), NodeText.regionTitle(node, max), depth) {
                val fork = ids.node(node.id)
                node(item, fork, Shape.CIRCLE, NodeText.lines(node, max), depth + 1, withNote = true)
                item.lanes.forEachIndexed { index, lane ->
                    group(ids.derived("${fork}_lane${index + 1}"), NodeText.laneTitle(index + 1), depth + 1) {
                        lane.forEach { item(it, depth + 2) }
                    }
                }
                val join = ids.derived("${fork}_join")
                joins[node.id] = join
                // Where nothing waits for the lanes, they end without anything joining them; where the
                // flow waits for the first of them, that is what leaves here.
                node(item, join, Shape.CIRCLE, listOf(NodeText.waiting(node) ?: NodeText.joining(node) ?: NodeText.JOIN), depth + 1, withNote = false)
            }
        }
        if (node.kind == NodeKind.UNKNOWN) looks.getValue(Look.UNKNOWN) += ids.node(node.id)
    }

    private fun node(item: Item, id: String, shape: Shape, text: List<String>, depth: Int, withNote: Boolean) {
        val decoration = decorations.of(item)
        val note = decoration?.note?.takeIf { withNote }?.singleLine(max)
        line(depth, id + shape.open + MermaidText.quote(text + listOfNotNull(note)) + shape.close)
        lookOf(decoration)?.let { looks.getValue(it) += id }
    }

    private fun handlers(handlers: List<HandlerGroup>) {
        if (handlers.isEmpty()) return
        group(ids.derived(NodeText.HANDLERS), NodeText.HANDLERS, depth = 1) {
            for (handler in handlers) {
                val id = ids.derived("h_${handler.entryPoints.last().id}")
                val decoration = decorations.of(handler)
                group(id, noted(NodeText.handlerTitle(handler, max), decoration), depth = 2) {
                    handler.items.forEach { item(it, depth = 3) }
                }
                lookOf(decoration)?.let { groupStyles += "${INDENT}style $id ${it.css}" }
            }
        }
    }

    private fun group(id: String, title: String, depth: Int, content: () -> Unit) {
        line(depth, "subgraph $id [${MermaidText.quote(title)}]")
        content()
        line(depth, "end")
    }

    private fun shapeOf(kind: NodeKind): Shape = when (kind) {
        NodeKind.START, NodeKind.END, NodeKind.CONTINUE_AS_NEW, NodeKind.FAIL -> Shape.STADIUM
        NodeKind.CHILD_WORKFLOW -> Shape.SUBROUTINE
        NodeKind.TIMER, NodeKind.AWAIT -> Shape.HEXAGON
        NodeKind.BRANCH, NodeKind.VERSION_GATE -> Shape.DIAMOND
        NodeKind.LOOP, NodeKind.PARALLEL -> Shape.CIRCLE
        // What is written there is kept with the workflow, for whoever looks it up.
        NodeKind.UPSERT_SEARCH_ATTRIBUTES, NodeKind.UPSERT_MEMO -> Shape.CYLINDER
        NodeKind.ACTIVITY,
        NodeKind.LOCAL_ACTIVITY,
        NodeKind.SIGNAL_EXTERNAL,
        NodeKind.NEXUS_OPERATION,
        NodeKind.SIDE_EFFECT,
        NodeKind.COMPENSATION,
        NodeKind.UNKNOWN,
        NodeKind.CANCEL_SCOPE,
        NodeKind.CANCEL_EXTERNAL,
        -> Shape.RECTANGLE
    }

    // --- edges ---

    private val dimmedEdges = mutableListOf<Int>()
    private val highlightedEdges = mutableListOf<Int>()
    private var edgeCount = 0

    private fun edge(edge: LaidEdge) {
        val arrow = if (edge.kind.isFlow) "-->" else "-.->"
        val decoration = decorations.of(edge)
        val text = edge.label?.let { noted(it, decoration) } ?: decoration?.note?.singleLine(max)
        val caption = if (text == null) "" else "|${MermaidText.quote(text)}|"
        line(1, "${idOf(edge.from)} $arrow$caption ${idOf(edge.to)}")
        when (decoration?.emphasis) {
            Emphasis.DIMMED -> dimmedEdges += edgeCount
            Emphasis.HIGHLIGHTED -> highlightedEdges += edgeCount
            Emphasis.NORMAL, null -> Unit
        }
        edgeCount++
    }

    private fun idOf(anchor: Anchor): String =
        if (anchor.port == Port.JOIN) joins.getValue(anchor.nodeId) else ids.node(anchor.nodeId)

    // --- looks ---

    private fun looks() {
        val used = looks.filterValues { it.isNotEmpty() }
        used.keys.forEach { line(1, "classDef ${it.className} ${it.css}") }
        used.forEach { (look, nodes) -> line(1, "class ${nodes.joinToString(",")} ${look.className}") }
    }

    private fun edgeStyles() {
        if (dimmedEdges.isNotEmpty()) line(1, "linkStyle ${dimmedEdges.joinToString(",")} $DIMMED_EDGE")
        if (highlightedEdges.isNotEmpty()) line(1, "linkStyle ${highlightedEdges.joinToString(",")} $HIGHLIGHTED_EDGE")
    }

    private fun lookOf(decoration: Decoration?): Look? = when (decoration?.emphasis) {
        Emphasis.DIMMED -> Look.DIMMED
        Emphasis.HIGHLIGHTED -> Look.HIGHLIGHTED
        Emphasis.NORMAL, null -> null
    }

    private fun noted(text: String, decoration: Decoration?): String {
        val note = decoration?.note?.singleLine(max) ?: return text
        return text + NOTE_SEPARATOR + note
    }

    private fun line(depth: Int, text: String) {
        lines += INDENT.repeat(depth) + text
    }
}
