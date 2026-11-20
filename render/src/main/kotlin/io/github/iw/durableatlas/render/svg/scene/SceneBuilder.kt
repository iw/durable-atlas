package io.github.iw.durableatlas.render.svg.scene

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.layout.CollapsedRegion
import io.github.iw.durableatlas.render.layout.DiagramLayout
import io.github.iw.durableatlas.render.layout.HandlerGroup
import io.github.iw.durableatlas.render.layout.Item
import io.github.iw.durableatlas.render.layout.LoopRegion
import io.github.iw.durableatlas.render.layout.ParallelRegion
import io.github.iw.durableatlas.render.layout.RequestTexts
import io.github.iw.durableatlas.render.layout.ScopeRegion
import io.github.iw.durableatlas.render.layout.Step
import io.github.iw.durableatlas.render.svg.ConstructStyles
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Shape
import io.github.iw.durableatlas.render.svg.StyleKinds
import io.github.iw.durableatlas.render.svg.engine.Flow
import io.github.iw.durableatlas.render.svg.roundedUpTo
import io.github.iw.durableatlas.render.svg.text.TextFitter
import io.github.iw.durableatlas.render.svg.text.TextMeasurer
import io.github.iw.durableatlas.render.svg.text.TextStyles
import kotlin.math.max

/**
 * Turns the layout of a document into a [Scene]: decides what every card says and how large it
 * is, which nodes a handler's card stands for, and which edges are drawn.
 *
 * - A region is a box, and edges of the region end on its border. So the edges with which other
 *   formats lead from a region to the first of its steps, and back from the last, are not drawn:
 *   being inside the box says the same. An edge that the document itself has from a step to a
 *   region around it, such as the arm of a branch that skips the rest of a loop's round, is
 *   drawn: it runs to the end of that box, out of the boxes that lie between the two where the
 *   region is not the step's own.
 * - A handler is one card. The card stands for the handler's start node, and for the nodes where
 *   the handler simply returns. What a handler runs in between is drawn below its card.
 * - Where a way into the end of a handler says more than that the handler is done, that end is
 *   drawn, as a pill below the handler's steps: the wait whose time is up, the failure that is
 *   caught and the arm of a branch each lead somewhere a reader can see. Every way into such an
 *   end is drawn then, the plain ones too, so that the pill is not taken for the end of one of
 *   them alone.
 * - A `wakes` edge starts at the card of the handler that wakes, or at the start of the workflow
 *   method where the workflow's own code does. Neither is laid out with the flow.
 */
internal class SceneBuilder(private val layout: DiagramLayout, measurer: TextMeasurer, maxLabelLength: Int) {
    private val fitter = TextFitter(measurer)
    private val texts = CardTexts(maxLabelLength)
    private val document = layout.document
    private val nodes: Map<String, IrNode> = document.nodes.associateBy { it.id }
    private val positions: Map<IrEdge, Int> = document.edges.withIndex().associate { (index, edge) -> edge to index }
    private val mainEntry = document.entryPoints.firstOrNull { it.kind == EntryPointKind.WORKFLOW_METHOD }

    /** The card that stands for each handler node that is not drawn itself. */
    private val standIn = HashMap<String, String>()
    private val returns = HashSet<String>()

    /**
     * The ends that a way leads to which is no plain way on: one of another kind than `flow`, or
     * one with words of its own. In a handler such an end is drawn.
     */
    private val toldEnds: Set<String> = document.edges
        .filter { it.kind.isFlow && (it.kind != EdgeKind.FLOW || !it.label.isNullOrBlank()) }
        .mapNotNullTo(HashSet()) { edge -> edge.to.takeIf { nodes[it]?.kind == NodeKind.END } }

    /** Index of the handler each drawn item belongs to; items of the main flow are not in here. */
    private val handlerOf = HashMap<String, Int>()
    private val captions = HashMap<String, String>()

    fun build(): Scene {
        val handlers = layout.handlers.mapIndexed(::handler)
        val main = layout.main.map { item(it, null)!! }

        val mainEdges = ArrayList<SceneEdge>()
        val wakes = ArrayList<SceneEdge>()
        val loose = ArrayList<SceneEdge>()
        val cancels = ArrayList<SceneEdge>()
        val inside = handlers.map { ArrayList<SceneEdge>() }
        layout.edges.forEachIndexed { number, laid ->
            val sources = laid.sources.mapNotNull(positions::get)
            // An edge without a source was added by the layout and is not in the document.
            if (sources.isEmpty()) return@forEachIndexed
            if (laid.from.nodeId in returns || laid.to.nodeId in returns) return@forEachIndexed
            // An edge from a region to a step inside it says what the box already says.
            if (within(laid.to.nodeId, laid.from.nodeId)) return@forEachIndexed
            val from = standIn[laid.from.nodeId] ?: laid.from.nodeId
            val to = standIn[laid.to.nodeId] ?: laid.to.nodeId
            if (from == to) return@forEachIndexed
            val text = if (laid.kind == EdgeKind.WAKES) laid.label else laid.label ?: captions[laid.from.nodeId]
            // A label that is too long for one line goes on with the next, rather than lose its end.
            val lines = text?.let { fitter.lines(it, TextStyles.LABEL, Geometry.LABEL_MAX_WIDTH, Geometry.LABEL_LINES) }.orEmpty()
            val edge = SceneEdge(
                id = "e$number",
                from = from,
                to = to,
                kind = laid.kind,
                label = lines.takeIf { it.isNotEmpty() }?.joinToString(" "),
                labelLines = lines,
                labelWidth = lines.maxOfOrNull { labelWidth(fitter, it) } ?: 0.0,
                sources = sources,
            )
            val source = handlerOf[from]
            val target = handlerOf[to]
            when {
                laid.kind == EdgeKind.WAKES && (from in standIn.values || from == mainEntry?.start) -> wakes += edge
                // What is cancelled lies back along the flow, or beside it: no way the flow takes, and not laid out as one.
                laid.kind == EdgeKind.CANCELS -> if (source == null && target == null) cancels += edge else loose += edge
                source == null && target == null -> mainEdges += edge
                source != null && source == target -> inside[source] += edge
                else -> loose += edge
            }
        }
        return Scene(
            main = main,
            mainEdges = mainEdges,
            handlers = handlers.mapIndexed { index, handler -> SceneHandler(handler.card, handler.body, inside[index]) },
            wakes = wakes,
            loose = loose,
            cancels = cancels,
        )
    }

    private fun handler(index: Int, group: HandlerGroup): SceneHandler {
        val primary = CardTexts.primary(group)
        val starts = group.entryPoints.map { it.start }.toSet()
        val hidden = ArrayList<String>()
        fun collect(items: List<Item>) {
            for (item in items) {
                val node = item.node
                if (node.id in starts || (node.kind == NodeKind.END && node.id !in toldEnds)) {
                    hidden += node.id
                    if (node.id in starts) standIn[node.id] = primary.id else returns += node.id
                }
                if (item is Step && item.caption != null && group.entryPoints.size > 1) captions[node.id] = item.caption
                when (item) {
                    is LoopRegion -> collect(item.body)
                    is ScopeRegion -> collect(item.body)
                    is ParallelRegion -> item.lanes.forEach(::collect)
                    else -> Unit
                }
            }
        }
        collect(group.items)
        val card = card(
            id = primary.id,
            kind = StyleKinds.of(primary.kind),
            text = texts.of(group),
            height = Geometry.HANDLER_CARD_HEIGHT,
            nodes = hidden,
            entryPoints = group.entryPoints.map { it.id },
        )
        handlerOf[card.id] = index
        val body = group.items.mapNotNull { item(it, index) }
        return SceneHandler(card, body, emptyList())
    }

    /** The scene item for a layout item; null for a handler node that its card stands for. */
    private fun item(item: Item, handler: Int?): SceneItem? {
        val node = item.node
        if (node.id in standIn || node.id in returns) return null
        if (handler != null) handlerOf[node.id] = handler
        val kind = StyleKinds.of(node.kind)
        fun children(items: List<Item>) = items.mapNotNull { item(it, handler) }
        return when (item) {
            is LoopRegion -> region(node, kind, Flow.DOWN, children(item.body))
            is ScopeRegion -> region(node, kind, Flow.DOWN, children(item.body))
            is ParallelRegion -> region(node, kind, Flow.RIGHT, children(item.lanes.flatten()))
            is CollapsedRegion -> card(node.id, kind, texts.of(item), Geometry.CARD_HEIGHT, shape = Shape.CARD)
            is Step -> card(
                id = node.id,
                kind = kind,
                // Where a handler ends, the handler returns and the workflow goes on.
                text = if (handler != null && node.kind == NodeKind.END) texts.handlerEnd() else texts.of(node),
                height = if (ConstructStyles.of(kind).shape == "pill") Geometry.PILL_HEIGHT else Geometry.CARD_HEIGHT,
                entryPoints = listOfNotNull(mainEntry?.takeIf { it.start == node.id }?.id),
                requests = RequestTexts.tooltip(node),
            )
        }
    }

    private fun region(node: IrNode, kind: String, flow: Flow, children: List<SceneItem>): SceneRegion {
        // A title that is too long for one line takes a second, so that the box is not wider than what it holds.
        val title = fitter.lines(texts.regionTitle(node), TextStyles.KIND, Geometry.REGION_TITLE_MAX, Geometry.REGION_TITLE_LINES)
        val header = title.maxOf { headerWidth(fitter, it) }
        // The cards of a region are all as wide as the widest, so they line up; and where they
        // sit in one column, as wide as the header, so the box is filled.
        val cards = children.filterIsInstance<SceneCard>().filter { it.shape != Shape.PILL }
        val column = cards.size == children.size && (flow == Flow.DOWN || cards.size == 1)
        val widest = max(cards.maxOfOrNull { it.width } ?: 0.0, if (column) header - 2 * Geometry.REGION_SIDE else 0.0)
        cards.forEach { it.width = widest.roundedUpTo(Geometry.CARD_WIDTH_STEP).coerceAtMost(Geometry.CARD_MAX_WIDTH) }
        return SceneRegion(node.id, kind, title, header, flow, children)
    }

    private fun card(
        id: String,
        kind: String,
        text: CardText,
        height: Double,
        shape: Shape? = null,
        nodes: List<String> = emptyList(),
        entryPoints: List<String> = emptyList(),
        requests: List<String> = emptyList(),
    ): SceneCard {
        val drawn = shape ?: when (ConstructStyles.of(kind).shape) {
            "pill" -> Shape.PILL
            "outlineCard" -> Shape.OUTLINE_CARD
            else -> Shape.CARD
        }
        val inset = if (drawn == Shape.PILL) Geometry.PILL_TEXT_INSET + Geometry.PILL_END_PADDING else Geometry.TILE_WIDTH + Geometry.CARD_TEXT_PADDING
        // Widths are measured with slack for other typefaces. A card that is as wide as cards get
        // spends that slack rather than break a name that fits as measured.
        val room = (Geometry.CARD_MAX_WIDTH - inset) * Geometry.TEXT_SLACK
        val kindLabel = fitter.truncate(text.kindLabel, TextStyles.KIND, room)
        val name = when {
            text.name == null -> emptyList()
            text.detail != null -> listOf(fitter.truncate(text.name, TextStyles.NAME, room), detail(text.detail, text.more, room))
            else -> fitter.twoLines(text.name, TextStyles.NAME, room)
        }
        val widest = max(fitter.width(kindLabel, TextStyles.KIND), name.maxOfOrNull { fitter.width(it, TextStyles.NAME) } ?: 0.0)
        val least = if (drawn == Shape.PILL) Geometry.PILL_MIN_WIDTH else Geometry.CARD_MIN_WIDTH
        val width = (inset + widest).roundedUpTo(Geometry.CARD_WIDTH_STEP).coerceIn(least, Geometry.CARD_MAX_WIDTH)
        return SceneCard(id, kind, drawn, width, height, kindLabel, name, nodes, entryPoints, requests)
    }

    /** The second line of a name, cut to [room] as names are, with [more] after it whatever is cut. */
    private fun detail(detail: String, more: String?, room: Double): String {
        if (more == null) return fitter.truncate(detail, TextStyles.NAME, room)
        val tail = " $more"
        return fitter.truncate(detail, TextStyles.NAME, room - fitter.width(tail, TextStyles.NAME)) + tail
    }

    /** Whether node [id] lies inside region [container], at any depth. */
    private fun within(id: String, container: String): Boolean =
        generateSequence(nodes[id]?.parent) { nodes[it]?.parent }.any { it == container }

    companion object {
        fun labelWidth(fitter: TextFitter, text: String): Double =
            fitter.width(text, TextStyles.LABEL) + 2 * Geometry.LABEL_PADDING

        /** The width the header of a region or band with this title takes. */
        fun headerWidth(fitter: TextFitter, title: String): Double =
            Geometry.REGION_HEADER_X + Geometry.CHIP_SIZE + Geometry.CHIP_GAP + fitter.width(title, TextStyles.KIND) + Geometry.REGION_HEADER_X
    }
}
