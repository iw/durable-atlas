package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.childTexts
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.texts
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.title
import io.github.iw.durableatlas.render.svg.text.FontMetricsMeasurer
import io.github.iw.durableatlas.render.svg.text.TextStyles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Labels of edges and titles of regions that are too long for one line. */
class SvgWrappingTest {
    private fun draw(document: IrDocument, options: SvgOptions = SvgOptions(), page: PageOptions = PageOptions()): String =
        SvgFormat(options).prepare(document, page).render()

    private fun width(text: String) = FontMetricsMeasurer.width(text, TextStyles.LABEL)

    @Test
    fun `a label that is too long for one line goes on with the next, and is not cut`() {
        val made = SvgDocuments.longLabels(SvgDocuments.regionPairs())
        val svg = draw(made)
        val graph = SvgDiagrams.positionedGraph(svg)
        val labels = graph.edges.mapNotNull { it.label }
        assertEquals(8, labels.size)
        assertTrue(labels.all { it.lines.size == 2 }, labels.map { it.lines }.toString())

        for (label in labels) {
            // What the label says is its lines read one after the other, and nothing of it is left out.
            assertEquals(label.text, label.shown.joinToString(" "))
            assertFalse("…" in label.text, label.text)
            label.shown.forEach { assertTrue(width(it) <= Geometry.LABEL_MAX_WIDTH, it) }
            // It is as high as its lines, and as wide as the longest of them.
            assertEquals(Geometry.LABEL_HEIGHT + (label.shown.size - 1) * Geometry.LABEL_LINE, label.box.height, 0.11)
            assertEquals(label.shown.maxOf { width(it) } + 2 * Geometry.LABEL_PADDING, label.box.width, 0.11)
        }
        assertEquals(
            listOf("pendingOrders.isEmpty() and", "approvedReason != null"),
            graph.edges.single { it.from == "wait" && it.kind == "flow" }.label!!.lines,
        )
        // A label of one line is written as before: nothing but its text.
        val plain = SvgDiagrams.positionedGraph(draw(SvgDocuments.regionPairs())).edges.mapNotNull { it.label }
        assertEquals(8, plain.size)
        assertTrue(plain.all { it.lines.isEmpty() && it.box.height == Geometry.LABEL_HEIGHT })

        // Every line is a text of its own, one below the other, and the whole is what the pointer shows.
        val dom = SvgDom(svg)
        val drawn = dom.edgeLabels.single { it.getAttribute("data-from") == "wait" && it.getAttribute("data-kind") == "flow" }
        assertEquals(listOf("pendingOrders.isEmpty() and", "approvedReason != null"), drawn.texts())
        assertEquals("pendingOrders.isEmpty() and approvedReason != null", drawn.title())
        val lines = drawn.childTexts()
        assertEquals(Geometry.LABEL_LINE, lines[1].getAttribute("y").toDouble() - lines[0].getAttribute("y").toDouble(), 0.11)
        assertEquals(lines[0].getAttribute("x"), lines[1].getAttribute("x"))
        dom.edgeLabels.forEach { assertEquals(it.texts().joinToString(" "), it.title()) }
    }

    @Test
    fun `a label that takes more than three lines is cut at the end of the third, and the pointer still shows all of it`() {
        val text = "orders.filter { it.isOpen() && it.lines.isNotEmpty() }.sumOf { line -> line.quantity * line.unitPrice } > creditLimit.forCustomer(customer)"
        val diamond = SvgDocuments.diamond()
        val made = diamond.copy(edges = diamond.edges.map { if (it.label == "yes" && it.from == "more") it.copy(label = text) else it })
        val svg = draw(made, page = PageOptions(render = RenderOptions(maxLabelLength = 200)))
        val label = SvgDiagrams.positionedGraph(svg).edges.single { it.from == "more" && it.to == "again" }.label!!

        assertEquals(Geometry.LABEL_LINES, label.lines.size)
        assertTrue(label.lines.last().endsWith("…") && label.lines.dropLast(1).none { "…" in it }, label.lines.toString())
        assertTrue(text.startsWith(label.text.dropLast(1).take(40)))
        label.lines.forEach { assertTrue(width(it) <= Geometry.LABEL_MAX_WIDTH, it) }
        val drawn = SvgDom(svg).edgeLabels.single { it.getAttribute("data-from") == "more" && it.getAttribute("data-to") == "again" }
        assertEquals(text, drawn.title())
    }

    @Test
    fun `labels of several lines keep clear of cards, borders and each other wherever edges run`() {
        // Between steps of the main flow, between stacked steps, to the end of a region, in pairs, into a later row.
        val documents = listOf(SvgDocuments.demo(), SvgDocuments.regionArms(), SvgDocuments.regionPairs(), SvgDocuments.outerEnds(), SvgDocuments.skipping())
        for (document in documents.map(SvgDocuments::longLabels)) {
            for (options in listOf(SvgOptions(), SvgOptions(wrapWidth = 700), SvgOptions(wrapWidth = 0))) {
                val graph = SvgDiagrams.positionedGraph(draw(document, options))
                assertTrue(graph.edges.any { it.label?.lines.orEmpty().size > 1 }, document.workflowType)
                assertEquals(emptyList(), LabelPlacement.problems(graph) + LabelPlacement.headerProblems(graph), "${document.workflowType} at ${options.wrapWidth}")
            }
        }
    }

    @Test
    fun `the label of an edge into the next row has room below the row it leaves, however many lines it takes`() {
        // The connector of two rows is labelled beside the stretch that drops out of the upper row.
        val demo = SvgDocuments.longLabels(SvgDocuments.demo())
        val collapsed = PageOptions(render = RenderOptions(maxNodes = 8))
        val drawings = listOf(
            SvgDiagrams.positionedGraph(draw(demo, SvgOptions(wrapWidth = 700), collapsed)),
            SvgDiagrams.positionedGraph(draw(demo, SvgOptions(wrapWidth = 1100), collapsed)),
            SvgDiagrams.positionedGraph(draw(SvgDocuments.all().getValue("render/odd-workflow"), SvgOptions(wrapWidth = 500))),
        )
        for (graph in drawings) {
            assertTrue(graph.rows.size > 1)
            assertEquals(emptyList(), LabelPlacement.problems(graph) + LabelPlacement.headerProblems(graph))
        }
        val graph = drawings.first()
        val connector = graph.edges.single { it.from == "n07-await-payment" && it.to == "n12-shipments" }
        val label = connector.label!!
        assertEquals(2, label.lines.size)
        val await = graph.nodes.single { it.id == "n07-await-payment" }.box
        val row = graph.rows.first { it.contains(await) }
        val next = graph.rows[graph.rows.indexOf(row) + 1]
        assertTrue(label.box.y > await.bottom && label.box.bottom < next.y, "the label at ${label.box} lies between the rows")
    }

    @Test
    fun `a title that is too long for one line takes a second, and its region is no wider for it`() {
        val retries = SvgDocuments.all().getValue("analyser/constructs/Retries")
        val svg = draw(retries)
        val graph = SvgDiagrams.positionedGraph(svg)
        val long = graph.regions.single { it.titleLines.isNotEmpty() }
        val short = graph.regions.single { it.titleLines.isEmpty() }

        // Plain words are set in capitals, with their commas.
        assertEquals("LOOP · RETRY ON FAILURE, UP TO 3 ATTEMPTS, FOR UP TO 10M", long.title)
        assertEquals(listOf("LOOP · RETRY ON FAILURE, UP TO 3 ATTEMPTS,", "FOR UP TO 10M"), long.titleLines)
        assertEquals("LOOP · RETRY ON FAILURE", short.title)
        long.titleLines.forEach { assertTrue(FontMetricsMeasurer.width(it, TextStyles.KIND) <= Geometry.REGION_TITLE_MAX, it) }
        assertTrue(long.box.width <= Geometry.REGION_TITLE_MAX + Geometry.CHIP_SIZE + Geometry.CHIP_GAP + 2 * Geometry.REGION_HEADER_X + 0.11, "the loop is ${long.box.width} wide")

        // The steps start one line further down than under a title of one line.
        fun first(region: PositionedRegion) = graph.nodes.filter { it.parent == region.id }.minOf { it.box.y } - region.box.y
        assertEquals(Geometry.REGION_TOP, first(short), 0.11)
        assertEquals(Geometry.REGION_TOP + Geometry.REGION_TITLE_LINE, first(long), 0.11)

        // The lines are drawn one below the other, from where the first starts beside the icon.
        val dom = SvgDom(svg)
        val group = dom.group(long.id)!!
        assertEquals(long.titleLines, group.texts())
        assertEquals(long.title, group.title())
        val lines = group.childTexts()
        assertEquals(lines[0].getAttribute("x"), lines[1].getAttribute("x"))
        assertEquals(Geometry.REGION_TITLE_LINE, lines[1].getAttribute("y").toDouble() - lines[0].getAttribute("y").toDouble(), 0.11)
        assertEquals(listOf("LOOP · RETRY ON FAILURE"), dom.group(short.id)!!.texts())
    }

    @Test
    fun `an edge keeps clear of both lines of a title`() {
        // The first step of a loop with a long title is woken by a handler: the title is in the way on two lines.
        val woken = SvgDocuments.wokenLoops()
        val made = woken.copy(nodes = woken.nodes.map { if (it.id == "inbox") it.copy(label = "retry on failure, up to 3 attempts, for up to 10m") else it })
        val graph = SvgDiagrams.positionedGraph(draw(made))
        val loop = graph.regions.single { it.id == "inbox" }
        assertEquals(2, loop.titleLines.size)
        assertEquals(emptyList(), LabelPlacement.problems(graph) + LabelPlacement.headerProblems(graph))
        val wait = graph.nodes.single { it.id == "wait" }.box
        assertTrue(wait.y >= loop.box.y + Geometry.REGION_TOP + Geometry.REGION_TITLE_LINE - 0.11)
        assertEquals(NodeKind.LOOP, made.nodes.single { it.id == "inbox" }.kind)
    }
}
