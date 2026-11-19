package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.Decoration
import io.github.iw.durableatlas.render.DiagramDecorator
import io.github.iw.durableatlas.render.DiagramOverlay
import io.github.iw.durableatlas.render.Emphasis
import io.github.iw.durableatlas.render.PageFact
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.TestDocuments
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.children
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.classes
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.outline
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.texts
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.title
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgOverlayTest {
    private val demo = SvgDocuments.demo()

    /** The demo workflow as one execution took it: paid, version 2, completed. */
    private val taken = setOf(
        "n00-start", "n01-validate-order", "n02-fraud-check-version", "n03-fraud-check", "n04-parallel", "n05-reserve-stock",
        "n06-authorise-payment", "n07-await-payment", "n12-shipments", "n13-shipment-workflow", "n14-shipment-ready",
        "n15-invoice-number", "n16-award-points", "n17-discount-policy", "n18-returns-window", "n19-continue-as-new-suggested", "n21-end",
    )

    private val coverage = object : DiagramDecorator {
        override fun node(node: IrNode): Decoration? = when {
            node.kind == NodeKind.START || node.id.startsWith("signal-") || node.id.startsWith("query-") -> null
            node.id in taken -> Decoration(note = if (node.id == "n13-shipment-workflow") "×24" else "×12")
            else -> Decoration(Emphasis.DIMMED, "never run")
        }

        override fun edge(edge: IrEdge): Decoration? =
            if (edge.from in taken && edge.to in taken) null else Decoration(Emphasis.DIMMED)

        override fun entryPoint(entryPoint: EntryPoint): Decoration? =
            if (entryPoint.name == "approvePayment") Decoration(note = "×12") else Decoration(Emphasis.DIMMED, "never run")
    }

    private val path = object : DiagramDecorator {
        override fun node(node: IrNode): Decoration = if (node.id in taken) Decoration(Emphasis.HIGHLIGHTED) else Decoration(Emphasis.DIMMED)

        override fun edge(edge: IrEdge): Decoration = if (edge.from in taken && edge.to in taken) Decoration(Emphasis.HIGHLIGHTED) else Decoration(Emphasis.DIMMED)

        override fun entryPoint(entryPoint: EntryPoint): Decoration = Decoration(Emphasis.DIMMED)
    }

    @Test
    fun `an overlay restyles the diagram and moves nothing`() {
        val prepared = SvgFormat().prepare(demo, PageOptions())
        val plain = prepared.render()
        for (decorator in listOf(coverage, path)) {
            val overlaid = prepared.render(DiagramOverlay(decorator))
            assertEquals(SvgDiagrams.positionedGraph(plain), SvgDiagrams.positionedGraph(overlaid))
            val before = SvgDom(plain)
            val after = SvgDom(overlaid)
            assertEquals(before.root.getAttribute("viewBox"), after.root.getAttribute("viewBox"))
            assertEquals((before.nodes + before.regions).map { it.getAttribute("id") to it.outline() }, (after.nodes + after.regions).map { it.getAttribute("id") to it.outline() })
            // Edges keep their lines; only the order they are painted in may change.
            assertEquals(
                before.edges.map { it.children().single { p -> p.localName == "path" }.getAttribute("d") }.sorted(),
                after.edges.map { it.children().single { p -> p.localName == "path" }.getAttribute("d") }.sorted(),
            )
            assertEquals(before.edgeLabels.map { it.children().last().getAttribute("x") to it.texts() }, after.edgeLabels.map { it.children().last().getAttribute("x") to it.texts() })
            assertEquals(transform(before, "diagram"), transform(after, "diagram"))
            assertEquals(transform(before, "legend"), transform(after, "legend"))
            // Drawing plain again gives the plain diagram again.
            assertEquals(plain, prepared.render())
        }
    }

    @Test
    fun `coverage greys what never ran and counts the rest`() {
        val dom = SvgDom(SvgFormat().prepare(demo, PageOptions()).render(DiagramOverlay(coverage)))

        val never = dom.group("n11-order-cancelled")!!
        assertEquals("0.35", never.getAttribute("opacity"))
        assertContains(never.classes(), "dimmed")
        assertEquals("FAIL: OrderCancelled — never run", never.title())
        assertTrue(never.children().none { it.getAttribute("class") == "badge" })
        // A region is greyed on its own; its children say for themselves.
        assertEquals("0.35", dom.group("n08-compensation")!!.getAttribute("opacity"))

        val hit = dom.group("n13-shipment-workflow")!!
        assertEquals("", hit.getAttribute("opacity"))
        assertEquals("×24", hit.children().single { it.getAttribute("class") == "badge" }.textContent)
        assertEquals("CHILD WORKFLOW: ShipmentWorkflow — ×24", hit.title())
        // The badge sits on the top right corner of the card.
        val badge = hit.children().single { it.getAttribute("class") == "badge-box" }
        val box = hit.outline()
        assertTrue(badge.getAttribute("y").toDouble() < box.y && badge.getAttribute("x").toDouble() > box.centerX)

        assertEquals("×12", dom.group("signal-approvePayment")!!.children().single { it.getAttribute("class") == "badge" }.textContent)
        assertEquals("0.35", dom.group("signal-cancelOrder")!!.getAttribute("opacity"))
        // The start pill says nothing and stays as it is.
        assertEquals(listOf("node", "pill"), dom.group("n00-start")!!.classes())

        val cancelled = dom.edges.single { it.getAttribute("data-to") == "n08-compensation" && it.getAttribute("data-kind") == "flow" }
        assertContains(cancelled.classes(), "dimmed")
        assertEquals(Palette.MUTED, cancelled.children().single { it.localName == "path" }.getAttribute("stroke"))
        val paid = dom.edges.single { it.getAttribute("data-to") == "n12-shipments" }
        assertFalse("dimmed" in paid.classes())
        assertEquals(Palette.FLOW, paid.children().single { it.localName == "path" }.getAttribute("stroke"))
    }

    @Test
    fun `the path of one execution stands out and everything else is greyed`() {
        val overlay = DiagramOverlay(path, title = "OrderWorkflow order-1001", notices = listOf("Execution order-1001, completed."), facts = listOf(PageFact("Events", "57")))
        val svg = SvgFormat().prepare(demo, PageOptions()).render(overlay)
        val dom = SvgDom(svg)
        val onPath = dom.group("n07-await-payment")!!
        assertContains(onPath.classes(), "highlighted")
        assertEquals(1, onPath.children().count { it.getAttribute("class") == "ring" })
        assertEquals("", onPath.getAttribute("opacity"))
        assertEquals("0.35", dom.group("n20-continue-as-new")!!.getAttribute("opacity"))
        assertEquals("0.35", dom.group("query-getStatus")!!.getAttribute("opacity"))
        assertContains(dom.edges.single { it.getAttribute("data-to") == "n12-shipments" }.classes(), "highlighted")

        // What the overlay says is written below the diagram, so the diagram stays where it was.
        assertEquals("OrderWorkflow order-1001 · com.example.orders.OrderWorkflowImpl", dom.root.children().first { it.localName == "title" }.textContent)
        assertEquals("Workflow diagram: OrderWorkflow order-1001, implemented by com.example.orders.OrderWorkflowImpl", dom.root.getAttribute("aria-label"))
        assertEquals(listOf("OrderWorkflow order-1001", "Execution order-1001, completed.", "Events: 57"), dom.groups("notes").single().texts())
        val plain = SvgDom(SvgFormat().prepare(demo, PageOptions()).render())
        assertTrue(dom.root.getAttribute("height").toDouble() > plain.root.getAttribute("height").toDouble())
        assertEquals(transform(plain, "diagram"), transform(dom, "diagram"))
    }

    @Test
    fun `a diagram can be restyled later from its file alone, without laying it out again`() {
        val page = PageOptions(diagramFiles = mapOf("ShipmentWorkflow" to listOf("ShipmentWorkflow.svg")))
        val format = SvgFormat(SvgOptions(sourceBaseUrl = "https://example.org/blob/{commit}"))
        val prepared = format.prepare(demo, page)
        val file = prepared.render()

        val later = format.restyle(demo, file, page)
        assertEquals(file, later.render())
        assertEquals(prepared.render(DiagramOverlay(coverage)), later.render(DiagramOverlay(coverage)))
        assertEquals(prepared.limit.total, later.limit.total)

        // The file decides the arrangement, not the settings in force when it is read back.
        val narrow = SvgFormat(SvgOptions(wrapWidth = 600, sourceBaseUrl = "https://example.org/blob/{commit}"))
        assertEquals(file, narrow.restyle(demo, file, page).render())
        assertTrue(SvgDiagrams.positionedGraph(narrow.prepare(demo, page).render()).rows.size > 2)
    }

    @Test
    fun `an HTML page can be restyled from the SVG file too`() {
        val page = PageOptions(indexFile = "index.md")
        val file = SvgFormat().prepare(demo, page).render()
        val pages = SvgHtmlFormat()

        val later = pages.restyle(demo, file, page)
        assertEquals(pages.prepare(demo, page).render(), later.render())
        val overlay = DiagramOverlay(coverage, title = "OrderWorkflow: coverage")
        assertEquals(pages.prepare(demo, page).render(overlay), later.render(overlay))
        assertContains(later.render(overlay), "<title>OrderWorkflow: coverage · com.example.orders.OrderWorkflowImpl</title>")
        // The page holds the diagram as it stands in the file, whatever the settings in force now.
        val narrow = SvgHtmlFormat(SvgOptions(wrapWidth = 600)).restyle(demo, file, page).render()
        assertEquals(SvgDiagrams.positionedGraph(file), SvgDiagrams.positionedGraph(narrow))
        assertFailsWith<IllegalArgumentException> { pages.restyle(TestDocuments.load(TestDocuments.INVOICE), file) }
    }

    @Test
    fun `a file that is not a diagram of the document is refused`() {
        val file = SvgFormat().prepare(demo, PageOptions()).render()
        val other = assertFailsWith<IllegalArgumentException> { SvgFormat().restyle(TestDocuments.load(TestDocuments.INVOICE), file) }
        assertContains(other.message.orEmpty(), "another document")
        val foreign = assertFailsWith<IllegalArgumentException> { SvgFormat().restyle(demo, "<svg xmlns=\"http://www.w3.org/2000/svg\"/>") }
        assertContains(foreign.message.orEmpty(), "no layout")
        assertFailsWith<IllegalArgumentException> { SvgFormat().restyle(demo, file.replace("\"width\":", "\"breadth\":")) }
    }

    @Test
    fun `the recorded layout survives being written and read`() {
        for ((name, document) in SvgDocuments.all()) {
            val svg = SvgFormat().prepare(document, PageOptions()).render()
            val graph = SvgDiagrams.positionedGraph(svg)
            assertEquals(graph, PositionedGraph.decode(graph.encode()), name)
            assertEquals(svg, SvgFormat().restyle(document, svg).render(), name)
        }
    }

    private fun transform(dom: SvgDom, group: String): String = dom.groups(group).single().getAttribute("transform")
}
