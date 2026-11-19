package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.EntryPointKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import io.github.iw.durableatlas.render.svg.Box
import io.github.iw.durableatlas.render.svg.Geometry
import io.github.iw.durableatlas.render.svg.Point
import io.github.iw.durableatlas.render.svg.PositionedEdge
import io.github.iw.durableatlas.render.svg.PositionedGraph
import io.github.iw.durableatlas.render.svg.SvgDiagrams
import io.github.iw.durableatlas.render.svg.SvgDocuments
import io.github.iw.durableatlas.render.svg.SvgDom
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.classes
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.texts
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.title
import io.github.iw.durableatlas.render.svg.SvgFormat
import io.github.iw.durableatlas.render.svg.compose.PathData
import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Waits that the workflow's own code ends: a lane that lets go of what another lane waits for,
 * or the workflow method doing so while a handler waits. Their `wakes` edges start at the
 * workflow method, as those of a handler start at the handler, and say on the edge what is
 * written, since the workflow method lists nothing it writes.
 */
class OwnWakeDiagramsTest {
    private val permit: IrDocument = TestDocuments.load(TestDocuments.PERMIT)
    private val selfWoken: IrDocument = SvgDocuments.selfWoken()
    private val midLane: IrDocument = SvgDocuments.midLane()

    private fun render(document: IrDocument, overlay: DiagramOverlay? = null): String {
        val prepared = SvgFormat().prepare(document, PageOptions())
        return if (overlay == null) prepared.render() else prepared.render(overlay)
    }

    private fun graph(document: IrDocument): PositionedGraph = SvgDiagrams.positionedGraph(render(document))

    private fun PositionedGraph.box(id: String): Box = nodes.firstOrNull { it.id == id }?.box ?: regions.single { it.id == id }.box

    private fun PositionedGraph.wake(from: String, to: String): PositionedEdge = edges.single { it.kind == "wakes" && it.from == from && it.to == to }

    /** The `wakes` edges of [document] that start at the workflow method. */
    private fun own(document: IrDocument): List<IrEdge> {
        val main = document.entryPoints.single { it.kind == EntryPointKind.WORKFLOW_METHOD }
        return document.edges.filter { it.kind == EdgeKind.WAKES && it.from == main.id }
    }

    /** Points along [edge], no further apart than a card is thick anywhere. */
    private fun along(edge: PositionedEdge): List<Point> {
        val corners = PathData.points(edge.path)
        return corners.zipWithNext().flatMap { (from, to) ->
            val pieces = max(1, (hypot(to.x - from.x, to.y - from.y) / 4).toInt())
            (0..pieces).map { Point(from.x + (to.x - from.x) * it / pieces, from.y + (to.y - from.y) * it / pieces) }
        }
    }

    /** The cards and pills of [graph] that [edge] runs over, by their ids. */
    private fun crossed(graph: PositionedGraph, edge: PositionedEdge): List<String> {
        val line = along(edge)
        return graph.nodes.filter { node -> line.any { node.box.grown(-1.5).contains(it) } }.map { it.id }
    }

    @Test
    fun `Mermaid draws such an edge from the start of the workflow method and says what is written`() {
        val mermaid = MermaidRenderer.render(permit)
        MermaidLint.check(mermaid)
        assertEquals(3, own(permit).size)
        for (edge in own(permit)) assertContains(mermaid, "    n_p00 -.->|\"permits\"| n_${edge.to}\n")
        // The edges of the handler say what the handler writes and the wait reads, as they always have.
        assertContains(mermaid, "    n_p20 -.->|\"permits\"| n_p02\n")
        // With them, the way the update takes when its time is up.
        assertContains(mermaid, "    n_p21 -->|\"timeout\"| n_p23\n")
    }

    @Test
    fun `in SVG it is a wakes edge like those of the handlers, from the start pill, and the wait is labelled once`() {
        val dom = SvgDom(render(permit))
        val lines = dom.edges.filter { it.getAttribute("data-kind") == "wakes" && it.getAttribute("data-from") == "p00" }
        assertEquals(listOf("p02", "p04", "p21"), lines.map { it.getAttribute("data-to") }.sorted())
        // What the line says in full names what is written.
        assertEquals("Start wakes permits.acquire() (permits)", lines.single { it.getAttribute("data-to") == "p02" }.title())
        assertEquals("Start wakes permits.tryAcquire() (permits)", lines.single { it.getAttribute("data-to") == "p21" }.title())
        assertEquals("weigh wakes permits.acquire()", dom.edges.single { it.getAttribute("data-from") == "update-weigh" && it.getAttribute("data-to") == "p02" }.title())
        lines.forEach { assertEquals(listOf("edge", "wakes"), it.classes()) }

        // Each wait has the word once, whoever wakes it.
        val graph = graph(permit)
        for ((to, wakes) in graph.edges.filter { it.kind == "wakes" }.groupBy { it.to }) {
            assertEquals(2, wakes.size, to)
            assertEquals(listOf("wakes"), wakes.mapNotNull { it.label?.text }, to)
        }
        assertEquals(1, dom.legendEntries.count { it.getAttribute("data-kind") == "wakes" })
        // Every edge of the document is drawn, the one to the end of the update among them.
        assertEquals(permit.edges.size, dom.edges.size)
        assertEquals(listOf("TERMINAL", "Return"), dom.group("p23")!!.texts())
    }

    @Test
    fun `the edges are drawn after the flow is laid out and move nothing of it`() {
        for (document in listOf(permit, selfWoken, midLane)) {
            val with = graph(document)
            val without = graph(document.copy(edges = document.edges - own(document).toSet()))
            fun places(graph: PositionedGraph): Map<String, Box> {
                // Lines that reach beyond the rows move the whole drawing; within it the flow stays as it is.
                val origin = graph.rows.first()
                val flow = graph.nodes.filter { !it.handler && !graph.band.holds(it.box) }.map { it.id to it.box } + graph.regions.map { it.id to it.box }
                return flow.associate { (id, box) -> id to box.moved(-origin.x, -origin.y) }
            }
            assertEquals(places(without), places(with), document.workflowType)
            assertEquals(without.rows.size, with.rows.size)
        }
    }

    private fun Box?.holds(box: Box): Boolean = this?.contains(box) == true

    @Test
    fun `no edge that wakes runs over a card, whoever wakes and wherever the wait stands`() {
        for (document in listOf(permit, selfWoken, midLane, SvgDocuments.handlerEnds())) {
            val graph = graph(document)
            val wakes = graph.edges.filter { it.kind == "wakes" }
            assertEquals(document.edges.count { it.kind == EdgeKind.WAKES }, wakes.size, document.workflowType)
            for (edge in wakes) assertEquals(emptyList(), crossed(graph, edge), "${document.workflowType}: ${edge.from} wakes ${edge.to}")
        }
    }

    @Test
    fun `to a wait in its own row the edge runs along above the row and comes down on the wait or in from its side`() {
        val graph = graph(permit)
        val start = graph.box("p00")
        val top = graph.rows.first().y
        for (to in listOf("p02", "p04")) {
            val line = PathData.points(graph.wake("p00", to).path)
            // Up out of the pill, to above everything in the row.
            assertEquals(Point(start.centerX, start.y), line.first())
            assertTrue(line.minOf { it.y } < top, "the edge to $to stays in the row")
            assertTrue(line.minOf { it.y } > graph.band!!.bottom, "the edge to $to enters the band")
        }
        // The wait of the first lane has nothing above it: the edge arrives on top of it.
        val first = graph.box("p02")
        val above = PathData.points(graph.wake("p00", "p02").path).last()
        assertEquals(first.y - Geometry.EDGE_END_GAP, above.y, 0.11)
        assertTrue(above.x > first.x && above.x < first.right)
        // The wait of the second lane lies below it: the edge passes the region at its side and enters the wait from there.
        val second = graph.box("p04")
        val beside = PathData.points(graph.wake("p00", "p04").path)
        assertEquals(Point(second.x - Geometry.EDGE_END_GAP, second.centerY), beside.last())
        assertTrue(beside.any { it.x < graph.box("p01").x && it.y > graph.box("p01").y }, "the edge does not pass the region at its side")
    }

    @Test
    fun `to a step of a handler the edge goes up the channel at the right of that handler's steps`() {
        val graph = graph(permit)
        val wait = graph.box("p21")
        val line = PathData.points(graph.wake("p00", "p21").path)
        assertEquals(Point(wait.right + Geometry.EDGE_END_GAP, wait.centerY), line.last())
        // It enters the band from below, right of everything the handler draws, the way to its end included.
        val handler = listOf("update-weigh", "p21", "p22", "p23").map { graph.box(it) }
        val timeout = graph.edges.single { it.kind == "timeout" }
        val taken = Box.around(handler + PathData.points(timeout.path).map { Box(it.x, it.y, 0.0, 0.0) } + timeout.label!!.box)!!
        val inBand = line.filter { it.y < graph.band!!.bottom && it.y > wait.centerY + 1 }
        assertTrue(inBand.isNotEmpty())
        assertTrue(inBand.all { it.x > taken.right }, "the edge runs through what the handler draws")
        // The handler wakes the same wait of its own: the two share the last stretch and the word.
        val own = PathData.points(graph.wake("update-weigh", "p21").path)
        assertEquals(line.last(), own.last())
        assertEquals(1, listOf(graph.wake("p00", "p21"), graph.wake("update-weigh", "p21")).count { it.label != null })
    }

    @Test
    fun `to a wait in a later row the edge goes around the rows, and needs no handlers to be there`() {
        val graph = graph(selfWoken)
        assertEquals(null, graph.band)
        assertEquals(2, graph.rows.size)
        val start = graph.box("start")
        // The first step of a loop has the title of the loop above it: the edge comes in from the side.
        val slot = graph.box("slot")
        assertEquals(Point(slot.x - Geometry.EDGE_END_GAP, slot.centerY), PathData.points(graph.wake("start", "slot").path).last())
        // The lanes lie in the second row: the edges leave the first row at its left and come in above the second.
        val left = graph.rows.minOf { it.x }
        for (to in listOf("first", "second")) {
            val line = PathData.points(graph.wake("start", to).path)
            assertEquals(Point(start.centerX, start.y), line.first())
            assertTrue(line.any { it.x < left }, "the edge to $to does not go around the rows")
            assertTrue(line.none { it.y > graph.rows[0].y && it.y < graph.rows[0].bottom && it.x > left && it.x < start.x })
        }
        assertEquals(graph.box("first").y - Geometry.EDGE_END_GAP, PathData.points(graph.wake("start", "first").path).last().y, 0.11)
        assertEquals(graph.box("second").x - Geometry.EDGE_END_GAP, PathData.points(graph.wake("start", "second").path).last().x, 0.11)
        // Everything drawn lies inside the diagram.
        val all = Box(0.0, 0.0, graph.width, graph.height).grown(0.05)
        graph.edges.filter { it.kind == "wakes" }.forEach { edge -> assertTrue(PathData.points(edge.path).all(all::contains), "${edge.to}") }
    }

    @Test
    fun `a wait with steps above it and on both sides is entered from the gap above it`() {
        val graph = graph(midLane)
        // The waits, from the top lane down.
        val waits = midLane.nodes.filter { it.label == "gate.lock()" }.map { it.id }.sortedBy { graph.box(it).y }
        assertEquals(3, waits.size)
        for ((above, id) in waits.zipWithNext()) {
            val wait = graph.box(id)
            val over = graph.box(above)
            for (from in listOf("start", "signal-go")) {
                val line = PathData.points(graph.wake(from, id).path)
                // It comes down on the wait, from a line that runs between the lane above and its own.
                assertEquals(wait.y - Geometry.EDGE_END_GAP, line.last().y, 0.11)
                val level = line[line.size - 2].y
                assertTrue(level > over.bottom + 4 && level < wait.y - 4, "the edge from $from comes in at $level, between ${over.bottom} and ${wait.y}")
                assertTrue(line.any { it.x < graph.box("lanes").x }, "the edge from $from does not pass the region at its side")
            }
        }
        // The wait of the first lane is reached from above, as before.
        val top = graph.box(waits.first())
        listOf("start", "signal-go").forEach { assertEquals(top.y - Geometry.EDGE_END_GAP, PathData.points(graph.wake(it, waits.first()).path).last().y, 0.11) }
    }

    @Test
    fun `an overlay marks such an edge by the edge of the document it stands for`() {
        val woken = own(permit).single { it.to == "p04" }
        val path = object : DiagramDecorator {
            override fun node(node: IrNode): Decoration? = null

            override fun edge(edge: IrEdge): Decoration = if (edge == woken) Decoration(Emphasis.HIGHLIGHTED, "×2") else Decoration(Emphasis.DIMMED)

            override fun entryPoint(entryPoint: EntryPoint): Decoration? = null
        }
        val plain = render(permit)
        val overlaid = render(permit, DiagramOverlay(path))
        val dom = SvgDom(overlaid)
        val lines = dom.edges.filter { it.getAttribute("data-kind") == "wakes" && it.getAttribute("data-from") == "p00" }
        assertEquals(listOf("highlighted"), lines.single { it.getAttribute("data-to") == "p04" }.classes() - listOf("edge", "wakes"))
        assertEquals("Start wakes permits.acquire() (permits) — ×2", lines.single { it.getAttribute("data-to") == "p04" }.title())
        assertEquals(listOf("dimmed"), lines.single { it.getAttribute("data-to") == "p02" }.classes() - listOf("edge", "wakes"))
        assertEquals(SvgDiagrams.positionedGraph(plain), SvgDiagrams.positionedGraph(overlaid))
    }
}
