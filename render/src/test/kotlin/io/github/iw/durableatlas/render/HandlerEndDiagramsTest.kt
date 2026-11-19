package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Palette
import io.github.iw.durableatlas.render.svg.PositionedGraph
import io.github.iw.durableatlas.render.svg.SvgDiagrams
import io.github.iw.durableatlas.render.svg.SvgDocuments
import io.github.iw.durableatlas.render.svg.SvgDom
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.children
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.classes
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.ids
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.texts
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.title
import io.github.iw.durableatlas.render.svg.SvgFormat
import io.github.iw.durableatlas.render.svg.compose.PathData
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a handler ends. As a rule its card stands for that: the handler has run its steps and
 * returns. Where a way into the end says more, such as the wait whose time is up, a failure
 * that is caught or the arm of a branch, the SVG diagram draws the end as a pill below the
 * handler's steps and every way into it, as the Mermaid diagram always does.
 */
class HandlerEndDiagramsTest {
    private val documents: Map<String, IrDocument> = SvgDocuments.all()
    private val guarded: IrDocument = documents.getValue("analyser/silent/Guarded")
    private val ends: IrDocument = SvgDocuments.handlerEnds()

    private fun render(document: IrDocument, overlay: DiagramOverlay? = null): String {
        val prepared = SvgFormat().prepare(document, PageOptions())
        return if (overlay == null) prepared.render() else prepared.render(overlay)
    }

    private fun svg(document: IrDocument): SvgDom = SvgDom(render(document))

    private fun graph(document: IrDocument): PositionedGraph = SvgDiagrams.positionedGraph(render(document))

    /** The edges of [document] into the node [id] that are ways the flow takes. */
    private fun into(document: IrDocument, id: String): List<IrEdge> = document.edges.filter { it.to == id && it.kind.isFlow }

    /** Whether [edge] says more than that the flow goes on: it is of another kind than `flow`, or has words of its own. */
    private fun tells(edge: IrEdge): Boolean = edge.kind != EdgeKind.FLOW || !edge.label.isNullOrBlank()

    /** The `end` nodes of the handlers of [document]: every one but those of the workflow method. */
    private fun handlerEnds(document: IrDocument): List<IrNode> {
        val out = document.edges.filter { it.kind.isFlow }.groupBy({ it.from }, { it.to })
        val children = document.nodes.filter { it.parent != null }.groupBy({ it.parent!! }, { it.id })
        val main = document.entryPoints.firstOrNull { it.kind == EntryPointKind.WORKFLOW_METHOD } ?: return emptyList()
        val reached = HashSet<String>()
        val pending = ArrayDeque(listOf(main.start))
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (reached.add(id)) pending += out[id].orEmpty() + children[id].orEmpty()
        }
        return document.nodes.filter { it.kind == NodeKind.END && it.id !in reached }
    }

    @Test
    fun `a wait in a handler whose time is up leads to the end of the handler, which is drawn for it`() {
        val timeout = guarded.edges.single { it.kind == EdgeKind.TIMEOUT }
        val wait = guarded.nodes.single { it.id == timeout.from }
        assertEquals("lock.tryLock()", wait.label)
        val store = guarded.nodes.single { it.name == "Store" }
        val dom = svg(guarded)

        // The end of the update is a pill of the kind every end is, which says that the handler returns.
        val pill = assertNotNull(dom.group(timeout.to))
        assertEquals("end", pill.getAttribute("data-kind"))
        assertEquals(listOf("node", "pill"), pill.classes())
        assertEquals(listOf("TERMINAL", "Return"), pill.texts())
        assertEquals("TERMINAL: Return", pill.title())

        // Both ways into it are drawn: the one the wait takes when its time is up, and the one after the last step.
        val drawn = dom.edges.filter { it.getAttribute("data-to") == timeout.to }
        assertEquals(setOf(wait.id to "timeout", store.id to "flow"), drawn.map { it.getAttribute("data-from") to it.getAttribute("data-kind") }.toSet())
        assertEquals("lock.tryLock() → Return: timeout", drawn.single { it.getAttribute("data-kind") == "timeout" }.title())
        val labels = dom.edgeLabels.filter { it.getAttribute("data-to") == timeout.to }
        assertEquals(listOf(listOf("timeout")), labels.map { it.texts() })
        // The legend explains the dashed line now that the diagram has one.
        assertContains(dom.legendEntries.map { it.getAttribute("data-kind") }, "timeout")

        // The card of the update stands for its start alone; the cards of the other handlers for their ends as well.
        val starts = guarded.entryPoints.associate { it.id to it.start }
        assertEquals(listOf(starts.getValue("update-tryChange")), dom.group("update-tryChange")!!.ids("data-nodes"))
        for (handler in listOf("update-change", "signal-release")) {
            val stoodFor = dom.group(handler)!!.ids("data-nodes")
            assertEquals(2, stoodFor.size, handler)
            assertEquals(NodeKind.END, guarded.nodes.single { it.id == stoodFor.last() }.kind)
            assertNull(dom.group(stoodFor.last()))
        }

        // The pill lies in the band, below the steps of its handler.
        val graph = graph(guarded)
        val box = graph.nodes.single { it.id == timeout.to }.box
        assertTrue(graph.band!!.contains(box))
        assertTrue(box.y > graph.nodes.single { it.id == store.id }.box.bottom)

        // Mermaid has drawn the same way all along.
        assertContains(MermaidRenderer.render(guarded), "    ${wait.id} -->|\"timeout\"| ${timeout.to}\n")
    }

    @Test
    fun `the end of a handler is drawn wherever a way into it tells something, and nowhere else`() {
        var drawn = 0
        var stoodFor = 0
        for ((name, document) in documents) {
            val dom = svg(document)
            for (end in handlerEnds(document)) {
                val ways = into(document, end.id)
                val lines = dom.edges.filter { it.getAttribute("data-to") == end.id }
                if (ways.any(::tells)) {
                    assertNotNull(dom.group(end.id), "$name: '${end.id}' is not drawn")
                    // Every way into it is drawn then, the plain ones too.
                    assertEquals(ways.map { it.from to it.kind.name.lowercase() }.toSet().size, lines.size, "$name: ways into '${end.id}'")
                    drawn++
                } else {
                    assertNull(dom.group(end.id), "$name: '${end.id}' is drawn")
                    assertEquals(emptyList(), lines, "$name: ways into '${end.id}'")
                    stoodFor++
                }
            }
        }
        // The check is not empty-handed, either way.
        assertTrue(drawn >= 6, drawn.toString())
        assertTrue(stoodFor >= 20, stoodFor.toString())
    }

    @Test
    fun `the arm of a branch, a failure that is caught and a timeout all lead to the pill, each with its words`() {
        val dom = svg(ends)
        fun line(from: String, to: String, kind: String) =
            dom.edges.single { it.getAttribute("data-from") == from && it.getAttribute("data-to") == to && it.getAttribute("data-kind") == kind }
        fun words(from: String, to: String, kind: String) =
            dom.edgeLabels.filter { it.getAttribute("data-from") == from && it.getAttribute("data-to") == to && it.getAttribute("data-kind") == kind }.flatMap { it.texts() }

        // A branch that skips the handler's one step.
        assertEquals("already done? → Return: yes", line("skip.done", "skip.end", "flow").title())
        assertEquals(listOf("yes"), words("skip.done", "skip.end", "flow"))
        assertEquals("ArchiveOrder → Return", line("skip.archive", "skip.end", "flow").title())
        // A step whose failure is caught and passed over: the handler returns either way.
        assertEquals("ReserveRoom → Return: exception, ActivityFailure", line("book.reserve", "book.end", "exception").title())
        assertEquals(listOf("ActivityFailure"), words("book.reserve", "book.end", "exception"))
        assertNotNull(line("book.reserve", "book.end", "flow"))
        // A wait that is the handler's only step: answered or not, the handler returns.
        assertEquals(listOf("timeout"), words("hold.wait", "hold.end", "timeout"))
        assertNotNull(line("hold.wait", "hold.end", "flow"))

        // A validator and the update it belongs to share a card, and each has the pill of its own end.
        assertEquals(listOf("check.start", "book.start"), dom.group("update-book")!!.ids("data-nodes"))
        assertEquals(listOf("TERMINAL", "Return"), dom.group("check.end")!!.texts())
        assertEquals(listOf("TERMINAL", "Return"), dom.group("book.end")!!.texts())
        assertEquals(listOf("FAIL", "InvalidRequest"), dom.group("check.fail")!!.texts())
        // The handler that simply returns is its card and nothing more.
        assertEquals(listOf("state.start", "state.end"), dom.group("query-state")!!.ids("data-nodes"))
        // The end of the workflow method says what it always says.
        assertEquals(listOf("TERMINAL", "Complete"), dom.group("end")!!.texts())

        // Mermaid draws each of these edges, and the SVG diagram none it does not.
        val mermaid = MermaidRenderer.render(ends)
        assertContains(mermaid, "    n_skip_done -->|\"yes\"| n_skip_end\n")
        assertContains(mermaid, "    n_book_reserve -->|\"ActivityFailure\"| n_book_end\n")
        assertContains(mermaid, "    n_hold_wait -->|\"timeout\"| n_hold_end\n")
    }

    @Test
    fun `the ways into the pill pass the steps between at the side, clear of the lines that wake`() {
        val graph = graph(ends)
        fun box(id: String): Box = graph.nodes.single { it.id == id }.box
        val timeout = graph.edges.single { it.from == "nudge.lock" && it.to == "nudge.end" }
        val line = PathData.points(timeout.path)
        // It starts below the wait, ends on top of the pill, and runs over neither the step between nor the pill.
        assertEquals(box("nudge.lock").bottom, line.first().y, 0.11)
        assertEquals(box("nudge.end").y, line.last().y, 2.11)
        val step = box("nudge.poke")
        assertTrue(line.none { step.grown(-1.0).contains(it) }, "the edge runs over the step it passes")
        assertTrue(line.maxOf { it.x } > step.right, "the edge does not pass the step at its side")

        // The handler wakes its own wait and one of the workflow method. Those lines run further out than the edge and its label.
        val taken = Box.around(line.map { Box(it.x, it.y, 0.0, 0.0) } + timeout.label!!.box)!!
        val wakes = graph.edges.filter { it.kind == "wakes" && it.from == "signal-nudge" }
        assertEquals(setOf("wait", "nudge.lock"), wakes.map { it.to }.toSet())
        for (wake in wakes) {
            val points = PathData.points(wake.path)
            assertTrue(points.none { taken.grown(1.0).contains(it) }, "the edge that wakes '${wake.to}' crosses the way into the pill")
            assertTrue(points.filter { it.y > box("nudge.lock").bottom && it.y < box("nudge.end").bottom }.all { it.x > taken.right })
        }

        // Two ways from one step into the pill run side by side.
        val caught = PathData.points(graph.edges.single { it.from == "book.reserve" && it.kind == "exception" }.path)
        val plain = PathData.points(graph.edges.single { it.from == "book.reserve" && it.kind == "flow" }.path)
        assertTrue(abs(caught.last().x - plain.last().x) >= 14, "the two ways arrive ${abs(caught.last().x - plain.last().x)} apart")
        listOf(caught, plain).forEach { way -> assertEquals(1, way.map { it.x }.distinct().size) }
    }

    @Test
    fun `an overlay counts and greys the pill and the ways into it like everything else`() {
        val timeout = guarded.edges.single { it.kind == EdgeKind.TIMEOUT }
        val plainWay = into(guarded, timeout.to).single { it.kind == EdgeKind.FLOW }
        val coverage = object : DiagramDecorator {
            override fun node(node: IrNode): Decoration? = if (node.id == timeout.to) Decoration(note = "×3") else null

            override fun edge(edge: IrEdge): Decoration? = when (edge) {
                timeout -> Decoration(note = "×1")
                plainWay -> Decoration(Emphasis.DIMMED)
                else -> null
            }

            override fun entryPoint(entryPoint: EntryPoint): Decoration? = null
        }
        val path = object : DiagramDecorator {
            override fun node(node: IrNode): Decoration = Decoration(if (node.id == timeout.to) Emphasis.HIGHLIGHTED else Emphasis.DIMMED)

            override fun edge(edge: IrEdge): Decoration = Decoration(if (edge == timeout) Emphasis.HIGHLIGHTED else Emphasis.DIMMED)

            override fun entryPoint(entryPoint: EntryPoint): Decoration = Decoration(Emphasis.DIMMED)
        }
        val plain = render(guarded)

        val counted = SvgDom(render(guarded, DiagramOverlay(coverage)))
        val pill = counted.group(timeout.to)!!
        assertEquals("×3", pill.children().single { it.getAttribute("class") == "badge" }.textContent)
        assertEquals("TERMINAL: Return — ×3", pill.title())
        val taken = counted.edges.single { it.getAttribute("data-kind") == "timeout" }
        assertEquals("lock.tryLock() → Return: timeout — ×1", taken.title())
        assertFalse("dimmed" in taken.classes())
        val never = counted.edges.single { it.getAttribute("data-to") == timeout.to && it.getAttribute("data-kind") == "flow" }
        assertContains(never.classes(), "dimmed")
        assertEquals(Palette.MUTED, never.children().single { it.localName == "path" }.getAttribute("stroke"))

        val walked = SvgDom(render(guarded, DiagramOverlay(path)))
        assertContains(walked.group(timeout.to)!!.classes(), "highlighted")
        assertContains(walked.edges.single { it.getAttribute("data-kind") == "timeout" }.classes(), "highlighted")
        assertContains(walked.group(plainWay.from)!!.classes(), "dimmed")

        // Nothing moved: both are the plain diagram, restyled.
        for (overlaid in listOf(counted, walked)) assertEquals(SvgDiagrams.positionedGraph(plain), SvgDiagrams.positionedGraph(overlaid.text))
    }

    @Test
    fun `a diagram over the node limit still draws the pill`() {
        val small = PageOptions(render = RenderOptions(maxNodes = 5))
        val dom = SvgDom(SvgFormat().prepare(ends, small).render())
        assertEquals(listOf("TERMINAL", "Return"), dom.group("nudge.end")!!.texts())
        assertEquals(1, dom.edges.count { it.getAttribute("data-kind") == "timeout" && it.getAttribute("data-to") == "nudge.end" })
    }
}
