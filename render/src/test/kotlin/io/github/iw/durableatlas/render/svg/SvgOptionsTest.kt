package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.render.DiagramDirectory
import io.github.iw.durableatlas.render.DiagramFormats
import io.github.iw.durableatlas.render.DirectoryOptions
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.TestDocuments
import io.github.iw.durableatlas.render.mermaid.MermaidFormat
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.children
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.texts
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SvgOptionsTest {
    @TempDir
    lateinit var temp: Path

    private val demo: IrDocument = SvgDocuments.demo()

    private fun draw(options: SvgOptions = SvgOptions(), page: PageOptions = PageOptions(), document: IrDocument = demo): String =
        SvgFormat(options).prepare(document, page).render()

    /** The link around the group of [id], if there is one. */
    private fun link(dom: SvgDom, id: String): String? {
        val parent = dom.group(id)!!.parentNode as Element
        return if (parent.localName == "a") parent.getAttributeNS("http://www.w3.org/1999/xlink", "href") else null
    }

    @Test
    fun `cards link to the source lines of their nodes when a base URL is given`() {
        val plain = SvgDom(draw())
        assertTrue(plain.named("a").isEmpty())

        val dom = SvgDom(draw(SvgOptions(sourceBaseUrl = "https://example.org/acme/shop/blob/{commit}/demo/")))
        val file = "https://example.org/acme/shop/blob/0000000000000000000000000000000000000000/demo/orders/src/main/kotlin/com/example/orders/OrderWorkflowImpl.kt"
        assertEquals("$file#L59", link(dom, "n01-validate-order"))
        assertEquals("$file#L90", link(dom, "n12-shipments"))
        // The card of a handler links to where the handler is declared.
        assertEquals("$file#L138", link(dom, "signal-approvePayment"))
        // A node without a location has no link.
        assertNull(link(dom, "n21-end"))
        assertEquals(demo.nodes.count { it.loc != null } - 1, dom.named("a").size)

        val spaced = SvgDom(draw(SvgOptions(sourceBaseUrl = "https://example.org/src"), document = demo.copy(nodes = demo.nodes.map { if (it.id == "n00-start") it.copy(loc = it.loc!!.copy(file = "my code/Größe.kt")) else it })))
        assertEquals("https://example.org/src/my%20code/Gr%C3%B6%C3%9Fe.kt#L57", link(spaced, "n00-start"))
    }

    @Test
    fun `a child workflow card links to the diagram of that workflow when one is written next to it`() {
        val page = PageOptions(diagramFiles = mapOf("ShipmentWorkflow" to listOf("Shipment Workflow.svg"), "OrderWorkflow" to listOf("OrderWorkflow.svg")))
        assertEquals("Shipment%20Workflow.svg", link(SvgDom(draw(page = page)), "n13-shipment-workflow"))
        // That link wins over the one to the source line.
        assertEquals("Shipment%20Workflow.svg", link(SvgDom(draw(SvgOptions(sourceBaseUrl = "https://example.org"), page)), "n13-shipment-workflow"))
        assertNull(link(SvgDom(draw(page = PageOptions(diagramFiles = mapOf("OtherWorkflow" to listOf("OtherWorkflow.svg"))))), "n13-shipment-workflow"))
    }

    @Test
    fun `the background can be left out`() {
        val paper = SvgDom(draw())
        assertEquals(Palette.PAPER, paper.elements.single { it.getAttribute("class") == "paper" }.getAttribute("fill"))
        val clear = SvgDom(draw(SvgOptions(background = Background.TRANSPARENT)))
        assertTrue(clear.elements.none { it.getAttribute("class") == "paper" })
        // Labels on the bare canvas lose their patch; those inside a region keep one in the region's colour.
        assertTrue(clear.edgeLabels.none { label -> label.children().any { it.localName == "rect" && it.getAttribute("fill") == Palette.PAPER } })
        assertEquals(SvgDiagrams.positionedGraph(paper.text), SvgDiagrams.positionedGraph(clear.text))
    }

    @Test
    fun `the typeface is embedded only on request`() {
        val plain = draw()
        assertFalse("@font-face" in plain)
        assertContains(plain, "font-family:Inter,system-ui,Arial,sans-serif")
        assertTrue(plain.length < 100_000, "a diagram of ${plain.length} characters")

        val embedded = draw(SvgOptions(embedFont = true))
        assertEquals(2, Regex("@font-face").findAll(embedded).count())
        assertContains(embedded, "font-weight:600;src:url(data:font/otf;base64,")
        assertContains(embedded, "font-weight:700;src:url(data:font/otf;base64,")
        assertFalse("url(http" in embedded)
        // Nothing but the style differs.
        assertEquals(plain.substringAfter("</style>"), embedded.substringAfter("</style>"))
        SvgDom(embedded)
    }

    @Test
    fun `the font files and their licence come with the tool`() {
        assertNotNull(javaClass.getResource("/com/formdev/flatlaf/fonts/inter/Inter-SemiBold.otf"))
        assertNotNull(javaClass.getResource("/com/formdev/flatlaf/fonts/inter/Inter-Bold.otf"))
        val licence = javaClass.getResourceAsStream("/com/formdev/flatlaf/fonts/inter/LICENSE.txt")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertContains(licence, "SIL OPEN FONT LICENSE Version 1.1")
        assertContains(licence, "The Inter Project Authors")
    }

    @Test
    fun `a degraded model is announced at the top of the diagram`() {
        val invoice = TestDocuments.load(TestDocuments.INVOICE)
        assertEquals(ModelMode.DEGRADED, invoice.modelMode)
        val dom = SvgDom(draw(document = invoice))
        val warning = dom.groups("warning").single()
        assertTrue(warning.texts().joinToString(" ").startsWith("Warning: this diagram comes from a degraded model."))
        assertEquals("degraded", dom.named("diagram").single().getAttribute("modelMode"))
        // The warning is above the diagram, which starts lower for it.
        val top = Regex("""translate\(16 ([\d.]+)\)""").find(dom.groups("diagram").single().getAttribute("transform"))!!.groupValues[1].toDouble()
        assertTrue(top > Geometry.MARGIN)
        assertTrue(warning.children().all { it.getAttribute("y").toDouble() < top })
        assertTrue(SvgDom(draw()).groups("warning").isEmpty())
    }

    @Test
    fun `an HTML page wraps the diagram without script`() {
        val format = SvgHtmlFormat(SvgOptions(sourceBaseUrl = "https://example.org/src"))
        assertEquals("html", format.name)
        assertEquals("html", format.extension)
        val page = format.prepare(demo, PageOptions(diagramFiles = mapOf("ShipmentWorkflow" to listOf("ShipmentWorkflow.html")), indexFile = "index.md")).render()
        assertTrue(page.startsWith("<!doctype html>\n<html lang=\"en\">\n"))
        assertTrue(page.endsWith("</html>\n"))
        assertFalse("<script" in page)
        assertContains(page, "<title>OrderWorkflow · com.example.orders.OrderWorkflowImpl</title>")
        assertContains(page, "<a href=\"index.md\">All workflows</a>")
        assertContains(page, "xlink:href=\"ShipmentWorkflow.html\"")
        val svg = page.substring(page.indexOf("<svg "), page.indexOf("</svg>") + "</svg>".length)
        assertEquals(SvgFormat(format.options).prepare(demo, PageOptions(diagramFiles = mapOf("ShipmentWorkflow" to listOf("ShipmentWorkflow.html")))).render().trimEnd(), svg)
    }

    @Test
    fun `the settings reach the diagrams a directory is rendered to`() {
        val formats = DiagramFormats.configured(DiagramFormats.all, SvgOptions(wrapWidth = 0, legend = LegendMode.NONE), html = true)
        assertEquals(listOf("svg", "html", "mermaid"), formats.map { it.name })
        assertEquals(listOf("mermaid", "html"), DiagramFormats.configured(listOf(MermaidFormat), SvgOptions(), html = true).map { it.name })
        assertEquals(listOf("mermaid"), DiagramFormats.configured(listOf(MermaidFormat), SvgOptions()).map { it.name })

        val out = temp.resolve("out")
        val summary = DiagramDirectory.render(TestDocuments.directory, out, DirectoryOptions(formats = formats))
        val written = Files.list(out).use { files -> files.map { it.name }.sorted().toList() }
        assertEquals(4 * 3 + 1, written.size)
        assertEquals(listOf("OrderWorkflow.svg", "OrderWorkflow.html", "OrderWorkflow.md"), summary.diagrams.last().files.map { it.file })
        val order = Files.readString(out.resolve("OrderWorkflow.svg"))
        assertEquals(1, SvgDiagrams.positionedGraph(order).rows.size)
        assertTrue(SvgDom(order).legendEntries.isEmpty())
        // The child workflow is linked in the format of the page that links it.
        assertContains(order, "xlink:href=\"InvoiceWorkflow.svg\"")
        assertContains(Files.readString(out.resolve("OrderWorkflow.html")), "xlink:href=\"InvoiceWorkflow.html\"")
        assertContains(Files.readString(out.resolve("index.md")), "[svg](OrderWorkflow.svg) · [html](OrderWorkflow.html) · [mermaid](OrderWorkflow.md)")
    }

    @Test
    fun `settings are checked`() {
        assertFailsWith<IllegalArgumentException> { SvgOptions(wrapWidth = -1) }
        assertEquals(1700, SvgOptions().wrapWidth)
        assertEquals(LegendMode.PRESENT, SvgOptions().legend)
        assertEquals(Background.PAPER, SvgOptions().background)
        assertFalse(SvgOptions().embedFont)
        assertNull(SvgOptions().sourceBaseUrl)
    }
}
