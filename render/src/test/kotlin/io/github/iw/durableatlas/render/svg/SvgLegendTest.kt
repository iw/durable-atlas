package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrEdge
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.TestDocuments
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.children
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.classes
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.texts
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.title
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgLegendTest {
    private fun legend(name: String, mode: LegendMode): SvgDom =
        SvgDom(SvgFormat(SvgOptions(legend = mode)).prepare(SvgDocuments.all().getValue(name), PageOptions()).render())

    @Test
    fun `by default the legend explains exactly what appears in the diagram`() {
        for ((name, document) in SvgDocuments.all()) {
            val dom = SvgDom(SvgFormat().prepare(document, PageOptions()).render())
            val constructs = (dom.nodes + dom.regions).map { it.getAttribute("data-kind") }.toSet()
            val styles = dom.edges.map { it.getAttribute("data-kind") }.toSet()
            assertEquals(constructs + styles, dom.legendEntries.map { it.getAttribute("data-kind") }.toSet(), name)
            assertEquals(dom.legendEntries.size, dom.legendEntries.map { it.getAttribute("data-kind") }.distinct().size, name)
            // In the order of the notation: constructs first, edge styles last.
            val order = StyleKinds.legendOrder + listOf("flow", "exception", "timeout", "wakes", "cancels")
            val positions = dom.legendEntries.map { order.indexOf(it.getAttribute("data-kind")) }
            assertEquals(positions.sorted(), positions, name)
            assertEquals(dom.legendEntries.size, dom.legendEntries.count { it.texts().single().isNotBlank() }, name)
        }
    }

    @Test
    fun `the full legend explains every construct and edge style`() {
        val dom = legend("render/${TestDocuments.INVOICE}", LegendMode.FULL)
        assertEquals(StyleKinds.legendOrder + listOf("flow", "exception", "timeout", "wakes", "cancels"), dom.legendEntries.map { it.getAttribute("data-kind") })
        assertEquals(
            listOf("Workflow start", "Activity", "Local activity", "Child workflow", "Nexus operation", "Timer", "Await condition"),
            dom.legendEntries.take(7).map { it.texts().single() },
        )
        assertEquals(listOf("Flow", "Exception", "Timeout", "Wakes an await", "Cancels a step"), dom.legendEntries.takeLast(5).map { it.texts().single() })
        // Every construct shows its icon, also those the diagram does not draw.
        assertEquals(25, dom.named("symbol").size)
        dom.legendEntries.take(25).forEach { entry -> assertEquals(1, entry.children().count { it.localName == "use" }) }
    }

    @Test
    fun `every kind of edge is drawn in a style of its own, which the legend shows`() {
        // A workflow whose last step can fail, with an await that can time out and that handlers wake.
        val made = SvgDocuments.wokenLoops().let { it.copy(edges = it.edges + IrEdge("settle", "report", EdgeKind.TIMEOUT, "timeout")) }
        val dom = SvgDom(SvgFormat().prepare(made, PageOptions()).render())
        val colours = mapOf("flow" to Palette.FLOW, "exception" to Palette.EXCEPTION, "timeout" to Palette.TIMEOUT, "wakes" to Palette.WAKES)
        assertEquals(4, colours.values.toSet().size)
        assertEquals(colours.keys, dom.edges.map { it.getAttribute("data-kind") }.toSet())
        for (edge in dom.edges) {
            val kind = edge.getAttribute("data-kind")
            assertEquals(listOf("edge", kind), edge.classes())
            val line = edge.children().single { it.localName == "path" }
            assertEquals(colours.getValue(kind), line.getAttribute("stroke"), kind)
            assertEquals("url(#atlas-arrow-$kind)", line.getAttribute("marker-end"))
        }
        for (label in dom.edgeLabels) {
            assertEquals(colours.getValue(label.getAttribute("data-kind")), label.children().single { it.localName == "text" }.getAttribute("fill"))
        }
        // The arrowheads have the colours of their lines, and the legend draws each line as the diagram does.
        for ((kind, colour) in colours) {
            val head = dom.named("marker").single { it.getAttribute("id") == "atlas-arrow-$kind" }
            assertEquals(colour, head.children().single().getAttribute("fill"))
            val sample = dom.legendEntries.single { it.getAttribute("data-kind") == kind }
            assertEquals(listOf(kind), sample.children().first().classes())
            assertEquals(colour, sample.children().first().children().single().getAttribute("stroke"))
        }
        assertEquals(listOf("Flow", "Exception", "Timeout", "Wakes an await"), dom.legendEntries.takeLast(4).map { it.texts().single() })
        // Dashes tell the lines apart without their colours, but for the two that are solid: those say what they are in their labels.
        val style = dom.named("style").single().textContent
        assertContains(style, ".atlas .timeout .e{stroke-dasharray:")
        assertContains(style, ".atlas .wakes .e{stroke-dasharray:")
        assertFalse(".exception" in style)
        dom.edgeLabels.filter { it.getAttribute("data-kind") == "exception" }.forEach { assertEquals(listOf("ActivityFailure"), it.texts()) }
    }

    @Test
    fun `what a cancelled scope reaches is drawn in a style of its own, in the colour of the card that cancels it`() {
        val dom = SvgDom(SvgFormat().prepare(TestDocuments.load(TestDocuments.DISPATCH), PageOptions()).render())
        val cancels = dom.edges.filter { it.getAttribute("data-kind") == "cancels" }
        assertEquals(4, cancels.size)
        for (edge in cancels) {
            assertEquals(listOf("edge", "cancels"), edge.classes())
            val line = edge.children().single { it.localName == "path" }
            assertEquals(Palette.CANCELS, line.getAttribute("stroke"))
            assertEquals("url(#atlas-arrow-cancels)", line.getAttribute("marker-end"))
        }
        assertTrue(Palette.CANCELS !in listOf(Palette.FLOW, Palette.EXCEPTION, Palette.TIMEOUT, Palette.WAKES))
        assertEquals(Palette.CANCELS, dom.named("marker").single { it.getAttribute("id") == "atlas-arrow-cancels" }.children().single().getAttribute("fill"))
        assertContains(dom.named("style").single().textContent, ".atlas .cancels .e{stroke-dasharray:")
        // The legend names the line and the two constructs, and draws the line as the diagram does.
        val sample = dom.legendEntries.single { it.getAttribute("data-kind") == "cancels" }
        assertEquals(listOf("Cancels a step"), sample.texts())
        assertEquals(listOf("cancels"), sample.children().first().classes())
        assertEquals(listOf("Cancel scope", "Cancel external"), listOf("cancelScope", "cancelExternal").map { kind -> dom.legendEntries.single { it.getAttribute("data-kind") == kind }.texts().single() })
        // The edges of one place share one label: the place in the main flow has one, the handler has one.
        assertEquals(listOf("cancels", "cancels"), dom.edgeLabels.filter { it.getAttribute("data-kind") == "cancels" }.map { it.texts().single() })
        assertTrue(cancels.all { " cancels " in it.title().orEmpty() })
    }

    @Test
    fun `the legend can be left out`() {
        val dom = legend("demo/OrderWorkflow", LegendMode.NONE)
        assertTrue(dom.legendEntries.isEmpty())
        assertTrue(dom.groups("legend").isEmpty())
        val with = legend("demo/OrderWorkflow", LegendMode.PRESENT)
        assertTrue(with.root.getAttribute("height").toDouble() > dom.root.getAttribute("height").toDouble())
        assertEquals(1, with.groups("legend").size)
        // A wide diagram has the legend in eight columns.
        val columns = with.legendEntries.take(21).map { it.children().single { child -> child.localName == "use" }.getAttribute("x") }.distinct()
        assertEquals(8, columns.size)
        assertEquals(24, with.legendEntries.size)
    }
}
