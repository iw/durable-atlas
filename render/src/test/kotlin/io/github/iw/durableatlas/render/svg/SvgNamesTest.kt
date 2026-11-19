package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.IrStats
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.Decoration
import io.github.iw.durableatlas.render.DiagramDecorator
import io.github.iw.durableatlas.render.DiagramDirectory
import io.github.iw.durableatlas.render.DiagramOverlay
import io.github.iw.durableatlas.render.DirectoryOptions
import io.github.iw.durableatlas.render.Emphasis
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.TestDocuments
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.children
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.title
import io.github.iw.durableatlas.render.svg.text.FontMetricsMeasurer
import io.github.iw.durableatlas.render.svg.text.TextStyles
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How a diagram says which workflow it shows, where several workflows have one type. */
class SvgNamesTest {
    @TempDir
    lateinit var temp: Path

    private val demo: IrDocument = SvgDocuments.demo()
    private val alone = PageOptions(diagramFiles = mapOf("OrderWorkflow" to listOf("OrderWorkflow.svg")))
    private val shared = PageOptions(diagramFiles = mapOf("OrderWorkflow" to listOf("OrderWorkflow--a.svg", "OrderWorkflow--b.svg")))

    private fun draw(page: PageOptions, document: IrDocument = demo, overlay: DiagramOverlay = DiagramOverlay.NONE): SvgDom =
        SvgDom(SvgFormat().prepare(document, page).render(overlay))

    /** The parts of the heading, each with its class; empty if the diagram has none. */
    private fun heading(dom: SvgDom): List<Pair<String, String>> =
        dom.groups("heading").flatMap { group -> group.children().single().children().map { it.getAttribute("class") to it.textContent } }

    private fun title(dom: SvgDom): String = dom.root.title()!!

    private fun top(dom: SvgDom): Double =
        dom.groups("diagram").single().getAttribute("transform").substringAfter(' ').removeSuffix(")").toDouble()

    private fun link(dom: SvgDom, id: String): String? {
        val parent = dom.group(id)!!.parentNode as Element
        return if (parent.localName == "a") parent.getAttributeNS("http://www.w3.org/1999/xlink", "href") else null
    }

    @Test
    fun `a diagram always names the implementing class in its title, and above the drawing only where its type is shared`() {
        val plain = draw(alone)
        val told = draw(shared)
        for (dom in listOf(plain, told)) {
            assertEquals("OrderWorkflow · com.example.orders.OrderWorkflowImpl", title(dom))
            assertEquals("Workflow diagram: OrderWorkflow, implemented by com.example.orders.OrderWorkflowImpl", dom.root.getAttribute("aria-label"))
        }
        assertEquals(emptyList(), heading(plain))
        assertEquals(listOf("ht" to "OrderWorkflow", "hi" to "com.example.orders.OrderWorkflowImpl"), heading(told))

        // The heading takes a line above the drawing and moves nothing inside it.
        val line = Geometry.HEADING_LINE + 8
        assertEquals(top(plain) + line, top(told), 0.01)
        assertEquals(plain.root.getAttribute("height").toDouble() + line, told.root.getAttribute("height").toDouble(), 0.01)
        assertEquals(plain.root.getAttribute("width"), told.root.getAttribute("width"))
        assertEquals(SvgDiagrams.positionedGraph(plain.text), SvgDiagrams.positionedGraph(told.text))
        val text = told.groups("heading").single().children().single()
        assertEquals(Geometry.MARGIN, text.getAttribute("x").toDouble())
        assertTrue(text.getAttribute("y").toDouble() < top(told) - 8)
    }

    @Test
    fun `overlays and files drawn again keep the heading, so the drawing stays where it is`() {
        val format = SvgFormat()
        val prepared = format.prepare(demo, shared)
        val file = prepared.render()
        val dimmed = object : DiagramDecorator {
            override fun node(node: IrNode) = Decoration(Emphasis.DIMMED)
        }
        val overlay = DiagramOverlay(dimmed, title = "OrderWorkflow: coverage", notices = listOf("Nothing was run."))
        for (drawn in listOf(prepared.render(overlay), format.restyle(demo, file, shared).render(overlay))) {
            val dom = SvgDom(drawn)
            assertEquals(heading(SvgDom(file)), heading(dom))
            assertEquals(top(SvgDom(file)), top(dom))
            // The overlay's own title stands below the drawing, and in the title of the file with the class after it.
            assertEquals("OrderWorkflow: coverage · com.example.orders.OrderWorkflowImpl", title(dom))
            assertEquals("OrderWorkflow: coverage", dom.groups("notes").single().children().first().textContent)
        }
        assertEquals(file, format.restyle(demo, file, shared).render())
    }

    @Test
    fun `a class name that is longer than the diagram is wide loses its beginning in the heading only`() {
        // Three cards in a row: about 480 wide.
        val nodes = listOf(IrNode("start", NodeKind.START), IrNode("track", NodeKind.ACTIVITY, name = "TrackParcel"), IrNode("end", NodeKind.END))
        val name = "com.example.logistics.parcels.tracking.international.DiamondWorkflows.TrackingDiamondWorkflowImpl"
        val long = SvgDocuments.diamond().copy(implementation = name, nodes = nodes, edges = listOf(IrEdge("start", "track"), IrEdge("track", "end")), stats = IrStats.of(nodes))
        val dom = draw(PageOptions(diagramFiles = mapOf(long.workflowType to listOf("a.svg", "b.svg"))), long)

        val (type, implementation) = heading(dom).map { it.second }
        assertEquals("DiamondWorkflow", type)
        // Whole parts of the name are left out, and its end, which tells the class from others, is kept.
        assertTrue(implementation.startsWith("…") && name.endsWith("." + implementation.drop(1)), implementation)
        assertTrue(implementation.endsWith("TrackingDiamondWorkflowImpl"), implementation)
        val width = FontMetricsMeasurer.width(type, TextStyles.HEADING) + Geometry.HEADING_GAP + FontMetricsMeasurer.width(implementation, TextStyles.HEADING_DETAIL)
        assertTrue(width <= dom.root.getAttribute("width").toDouble() - 2 * Geometry.MARGIN, "the heading is $width wide")
        assertEquals("DiamondWorkflow · $name", title(dom))
    }

    @Test
    fun `the heading stands above the warning of a degraded model`() {
        val invoice = TestDocuments.load(TestDocuments.INVOICE)
        val dom = draw(PageOptions(diagramFiles = mapOf(invoice.workflowType to listOf("a.svg", "b.svg"))), invoice)
        val head = dom.groups("heading").single().children().single().getAttribute("y").toDouble()
        val warning = dom.groups("warning").single().children().first().getAttribute("y").toDouble()
        assertTrue(head < warning && warning < top(dom), "heading at $head, warning at $warning, drawing at ${top(dom)}")
        val plain = draw(PageOptions(), invoice)
        assertEquals(top(plain) + Geometry.HEADING_LINE + 8, top(dom), 0.01)
    }

    @Test
    fun `a dynamic workflow is called one above its drawing and in its title, whatever else is drawn with it`() {
        val fallback = SvgDocuments.all().getValue("analyser/constructs/dynamic-Fallback")
        assertTrue(fallback.isDynamic)
        assertEquals("dynamic-Fallback", fallback.workflowType)
        val pages = listOf(PageOptions(), PageOptions(diagramFiles = mapOf(fallback.workflowType to listOf("dynamic-Fallback.svg"))))
        for (page in pages) {
            val dom = draw(page, fallback)
            // Its type is a name made up from its class, so the heading says what it is and names the class.
            assertEquals(listOf("ht" to "Dynamic workflow", "hi" to "constructs.Fallback"), heading(dom))
            assertEquals("Dynamic workflow · constructs.Fallback", title(dom))
            assertEquals("Workflow diagram: dynamic workflow, implemented by constructs.Fallback", dom.root.getAttribute("aria-label"))
        }
        // The heading stands above the warning, as that of a shared type does.
        val plain = draw(PageOptions(), fallback)
        val warned = plain.groups("warning").single().children().first().getAttribute("y").toDouble()
        assertTrue(plain.groups("heading").single().children().single().getAttribute("y").toDouble() < warned)

        // An overlay has a title of its own, which names the type its executions ran as.
        val overlay = DiagramOverlay(title = "ExpenseReport: report-17", notices = listOf("The path of one execution."))
        val laid = draw(PageOptions(), fallback, overlay)
        assertEquals(heading(plain), heading(laid))
        assertEquals(top(plain), top(laid))
        assertEquals("ExpenseReport: report-17 · dynamic workflow constructs.Fallback", title(laid))
        assertEquals("Workflow diagram: ExpenseReport: report-17, a dynamic workflow implemented by constructs.Fallback", laid.root.getAttribute("aria-label"))
        // A page around the diagram is called the same.
        val page = SvgHtmlFormat().prepare(fallback, PageOptions()).render()
        assertTrue("<title>Dynamic workflow · constructs.Fallback</title>" in page)
    }

    @Test
    fun `a child workflow card links no diagram where several show a workflow of its type`() {
        val one = PageOptions(diagramFiles = mapOf("ShipmentWorkflow" to listOf("ShipmentWorkflow.svg")))
        val several = PageOptions(diagramFiles = mapOf("ShipmentWorkflow" to listOf("ShipmentWorkflow--b.svg", "ShipmentWorkflow--a.svg")))
        val child = "n13-shipment-workflow"

        assertEquals("ShipmentWorkflow.svg", link(draw(one), child))
        assertEquals("CHILD WORKFLOW: ShipmentWorkflow", draw(one).group(child)!!.title())
        // The type is all that is known of the child, so none of the two is more likely than the other.
        val dom = draw(several)
        assertNull(link(dom, child))
        assertEquals("CHILD WORKFLOW: ShipmentWorkflow (2 diagrams of this workflow type, none linked)", dom.group(child)!!.title())
        // The link to the source line is still there.
        val sourced = SvgDom(SvgFormat(SvgOptions(sourceBaseUrl = "https://example.org/src")).prepare(demo, several).render())
        assertTrue(link(sourced, child)!!.startsWith("https://example.org/src/"))
    }

    @Test
    fun `in a directory the diagrams of one workflow type are told apart, and the others are left as they are`() {
        val ir = temp.resolve("ir")
        val out = temp.resolve("out")
        Files.createDirectories(ir)
        fun write(name: String, document: IrDocument) = Files.writeString(ir.resolve("$name.ir.json"), IrJson.encode(document))
        val diamond = SvgDocuments.diamond()
        write("DiamondWorkflow--com.example.north.DiamondWorkflowImpl", diamond.copy(implementation = "com.example.north.DiamondWorkflowImpl"))
        write("DiamondWorkflow--com.example.south.DiamondWorkflowImpl", diamond.copy(implementation = "com.example.south.DiamondWorkflowImpl"))
        write("SkippingWorkflow", SvgDocuments.skipping())

        DiagramDirectory.render(ir, out, DirectoryOptions(formats = listOf(SvgFormat())))

        fun drawn(name: String) = SvgDom(Files.readString(out.resolve("$name.svg")))
        val north = drawn("DiamondWorkflow--com.example.north.DiamondWorkflowImpl")
        val south = drawn("DiamondWorkflow--com.example.south.DiamondWorkflowImpl")
        assertEquals(listOf("ht" to "DiamondWorkflow", "hi" to "com.example.north.DiamondWorkflowImpl"), heading(north))
        assertEquals(listOf("ht" to "DiamondWorkflow", "hi" to "com.example.south.DiamondWorkflowImpl"), heading(south))
        assertEquals("DiamondWorkflow · com.example.north.DiamondWorkflowImpl", title(north))
        assertEquals("DiamondWorkflow · com.example.south.DiamondWorkflowImpl", title(south))
        val other = drawn("SkippingWorkflow")
        assertEquals(emptyList(), heading(other))
        assertEquals("SkippingWorkflow · com.example.SkippingWorkflowImpl", title(other))
    }
}
