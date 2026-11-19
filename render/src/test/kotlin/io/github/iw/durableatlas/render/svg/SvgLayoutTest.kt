package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrStats
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.TestDocuments
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.texts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SvgLayoutTest {
    private fun graph(document: IrDocument, options: SvgOptions = SvgOptions(), page: PageOptions = PageOptions()): PositionedGraph =
        SvgDiagrams.positionedGraph(SvgFormat(options).prepare(document, page).render())

    @Test
    fun `the same document always gives the same bytes`() {
        for ((name, document) in SvgDocuments.all()) {
            val first = SvgFormat().prepare(document, PageOptions()).render()
            assertEquals(first, SvgFormat().prepare(document, PageOptions()).render(), name)
            // The order in which a document lists its parts does not matter either.
            val shuffled = document.copy(
                entryPoints = document.entryPoints.reversed(),
                nodes = document.nodes.shuffled(java.util.Random(7)),
                edges = document.edges.shuffled(java.util.Random(11)),
            )
            assertEquals(first, SvgFormat().prepare(shuffled, PageOptions()).render(), name)
        }
    }

    @Test
    fun `a flow wider than the limit is broken into rows no wider than it`() {
        val demo = SvgDocuments.demo()
        val wrapped = graph(demo)
        assertEquals(2, wrapped.rows.size)
        wrapped.rows.forEach { assertTrue(it.width <= SvgOptions.DEFAULT_WRAP_WIDTH, "row of ${it.width}") }
        assertTrue(wrapped.rows[0].bottom < wrapped.rows[1].y)

        // No region is torn apart: every region and card lies in one row.
        for (box in wrapped.regions.map { it.box } + wrapped.nodes.filter { !it.handler }.map { it.box }) {
            assertEquals(1, wrapped.rows.count { it.grown(0.05).contains(box) }, "$box")
        }
        // The rows are joined by the one edge that leads on: it leaves the first row downwards and enters the second from above.
        val across = wrapped.edges.filter { edge ->
            val from = wrapped.rows.indexOfFirst { row -> row.grown(0.05).contains(box(wrapped, edge.from)) }
            val to = wrapped.rows.indexOfFirst { row -> row.grown(0.05).contains(box(wrapped, edge.to)) }
            edge.kind != "wakes" && from != to
        }
        assertEquals(listOf("n07-await-payment" to "n12-shipments"), across.map { it.from to it.to })
        assertEquals("paid", across.single().label?.text)

        val single = graph(demo, SvgOptions(wrapWidth = 0))
        assertEquals(1, single.rows.size)
        assertTrue(single.rows.single().width > SvgOptions.DEFAULT_WRAP_WIDTH)

        val narrow = graph(demo, SvgOptions(wrapWidth = 900))
        assertTrue(narrow.rows.size > 2)
        narrow.rows.forEach { assertTrue(it.width <= 900, "row of ${it.width}") }
    }

    @Test
    fun `a flow whose steps all lead to one failure handler is broken into rows too`() {
        val saga = graph(SvgDocuments.saga(12))
        assertEquals(3, saga.rows.size)
        saga.rows.forEach { assertTrue(it.width <= SvgOptions.DEFAULT_WRAP_WIDTH, "row of ${it.width}") }
        // Every failure edge is still there, and the ones that cross rows share one label.
        val failures = saga.edges.filter { it.kind == "exception" }
        assertEquals(12, failures.size)
        assertEquals(setOf("undo"), failures.map { it.to }.toSet())
        assertEquals(listOf("ActivityFailure"), failures.mapNotNull { it.label?.text }.distinct())
        assertTrue(failures.count { it.label != null } in 1..2, "${failures.count { it.label != null }} labels")
    }

    @Test
    fun `a short flow stays in one row`() {
        val invoice = graph(TestDocuments.load(TestDocuments.INVOICE))
        assertEquals(1, invoice.rows.size)
        assertNull(invoice.band)
        // Cards follow each other from left to right, a fixed distance apart, on one line.
        val cards = invoice.nodes.sortedBy { it.box.x }
        cards.zipWithNext { a, b -> assertEquals(Geometry.ROW_SPACING, b.box.x - a.box.right, 0.11) }
        assertEquals(1, cards.map { it.box.centerY }.distinct().size)
    }

    @Test
    fun `cards are sized by their text within fixed bounds`() {
        for ((name, document) in SvgDocuments.all()) {
            for (node in graph(document).nodes) {
                val least = if (node.shape == Shape.PILL) Geometry.PILL_MIN_WIDTH else Geometry.CARD_MIN_WIDTH
                assertTrue(node.box.width in least..Geometry.CARD_MAX_WIDTH, "$name: ${node.id} is ${node.box.width} wide")
                assertTrue(node.box.width == least || node.box.width % Geometry.CARD_WIDTH_STEP == 0.0, "$name: ${node.id} is ${node.box.width} wide")
                val height = when {
                    node.handler -> Geometry.HANDLER_CARD_HEIGHT
                    node.shape == Shape.PILL -> Geometry.PILL_HEIGHT
                    else -> Geometry.CARD_HEIGHT
                }
                assertEquals(height, node.box.height, "$name: ${node.id}")
                assertTrue(node.name.size <= 2)
            }
        }
    }

    @Test
    fun `the steps of a loop follow each other downwards and the branches of a parallel section are stacked`() {
        val demo = graph(SvgDocuments.demo())
        fun box(id: String) = demo.nodes.single { it.id == id }.box
        // Loop and compensation: one below the other, in the order of the flow.
        assertTrue(box("n13-shipment-workflow").bottom < box("n14-shipment-ready").y)
        assertEquals(box("n13-shipment-workflow").x, box("n14-shipment-ready").x)
        assertTrue(box("n09-release-stock").bottom < box("n10-void-payment").y)
        // Parallel: the branches are stacked, each starting at the left.
        assertEquals(box("n05-reserve-stock").x, box("n06-authorise-payment").x)
        assertFalse(box("n05-reserve-stock").intersects(box("n06-authorise-payment")))
        // An edge that ends at a region stops at the region's border, not at a card inside it.
        val region = demo.regions.single { it.id == "n08-compensation" }.box
        val incoming = demo.edges.filter { it.to == "n08-compensation" }
        assertEquals(setOf("flow", "timeout"), incoming.map { it.kind }.toSet())
        incoming.forEach { edge ->
            val end = Regex("""H(-?[\d.]+)$""").find(edge.path)!!.groupValues[1].toDouble()
            assertEquals(region.x - Geometry.EDGE_END_GAP, end, 0.11)
        }
    }

    @Test
    fun `handlers sit in a band above the main flow, those that wake an await nearest to it`() {
        val demo = graph(SvgDocuments.demo())
        val band = demo.band!!
        assertTrue(band.bottom < demo.rows.first().y)
        val handlers = demo.nodes.filter { it.handler }
        assertEquals(setOf("signal", "query", "update"), handlers.map { it.kind }.toSet())
        assertEquals("UPDATE + VALIDATOR", handlers.single { it.kind == "update" }.kindLabel)
        assertEquals(listOf("validator-changeAddress", "update-changeAddress"), handlers.single { it.kind == "update" }.entryPoints)

        val await = demo.nodes.single { it.id == "n07-await-payment" }.box
        val wakers = demo.edges.filter { it.kind == "wakes" }
        assertEquals(setOf("signal-approvePayment", "signal-cancelOrder"), wakers.map { it.from }.toSet())
        val distance = handlers.associate { it.id to kotlin.math.abs(it.box.centerX - await.centerX) }
        val nearest = distance.entries.sortedBy { it.value }.take(2).map { it.key }.toSet()
        assertEquals(wakers.map { it.from }.toSet(), nearest)
        // "wakes" is written once for the await, and the edges are curves that end on top of it.
        assertEquals(listOf("wakes"), wakers.mapNotNull { it.label?.text })
        wakers.forEach { assertTrue("C" in it.path) }
    }

    @Test
    fun `the handlers stand side by side below the header of their band, which is no larger than they need`() {
        for (document in listOf(SvgDocuments.demo(), SvgDocuments.wokenLoops())) {
            val drawn = graph(document)
            val band = drawn.band!!
            val handlers = drawn.nodes.filter { it.handler }.sortedBy { it.box.x }
            assertEquals(band.x + Geometry.REGION_SIDE, handlers.first().box.x, 0.11)
            assertEquals(band.right - Geometry.REGION_SIDE, handlers.last().box.right, 0.11)
            handlers.forEach { assertEquals(band.y + Geometry.REGION_TOP, it.box.y, 0.11) }
            handlers.zipWithNext { left, right -> assertEquals(Geometry.BAND_CARD_GAP, right.box.x - left.box.right, 0.11) }
            assertEquals(Geometry.REGION_TOP + Geometry.HANDLER_CARD_HEIGHT + Geometry.REGION_SIDE, band.height, 0.11)
        }
        // A single handler has a band of its own width.
        val woken = SvgDocuments.wokenLoops()
        fun kept(id: String) = id.removePrefix("signal-").substringBefore('.') !in setOf("finish", "cancel")
        val one = graph(
            woken.copy(
                entryPoints = woken.entryPoints.filter { kept(it.id) },
                nodes = woken.nodes.filter { kept(it.id) },
                edges = woken.edges.filter { kept(it.from) },
                stats = IrStats.of(woken.nodes.filter { kept(it.id) }),
            ),
        )
        val alone = one.band!!
        assertEquals(Geometry.CARD_MIN_WIDTH + 2 * Geometry.REGION_SIDE, alone.width, 0.11)
        assertEquals(alone.x + Geometry.REGION_SIDE, one.nodes.single { it.handler }.box.x, 0.11)
    }

    @Test
    fun `what a handler runs is drawn below its card, inside the band`() {
        val order = TestDocuments.load(TestDocuments.ORDER)
        val drawn = graph(order)
        val band = drawn.band!!
        val card = drawn.nodes.single { it.id == "upd-address" }
        val step = drawn.nodes.single { it.kindLabel == "ACTIVITY" && it.name == listOf("ValidateAddress") }
        assertTrue(card.handler && !step.handler)
        assertTrue(band.contains(step.box))
        assertTrue(step.box.y > card.box.bottom)
        val edge = drawn.edges.single { it.from == card.id && it.to == step.id }
        assertEquals("flow", edge.kind)
    }

    @Test
    fun `over the node limit every region is one card that says how much it hides`() {
        val document = TestDocuments.load(TestDocuments.FULFILMENT)
        val prepared = SvgFormat().prepare(document, PageOptions(render = RenderOptions(maxNodes = 10)))
        val svg = prepared.render()
        val drawn = SvgDiagrams.positionedGraph(svg)
        assertEquals(2, prepared.limit.collapsedRegions)
        assertTrue(drawn.regions.isEmpty())
        val parallel = drawn.nodes.single { it.id == "n104" }
        assertEquals("parallel", parallel.kind)
        assertEquals("PARALLEL", parallel.kindLabel)
        assertEquals(listOf("8 nodes hidden"), parallel.name)
        val loop = SvgDom(svg).group("n116")!!
        assertEquals("loop", loop.getAttribute("data-kind"))
        // The title of the loop is code and keeps its case.
        assertEquals("LOOP · do … while (!delivered)", loop.texts()[0])
        assertEquals("3 nodes hidden", loop.texts()[1])
        assertTrue("more than the limit of 10" in svg)
        assertNotEquals(svg, SvgFormat().prepare(document, PageOptions()).render())

        // A title that is longer than a card is wide is cut.
        val wordy = document.copy(nodes = document.nodes.map { if (it.id == "n116") it.copy(label = "do … while (!delivered && attempts < maximumAttempts)") else it })
        val cut = SvgDom(SvgFormat().prepare(wordy, PageOptions(render = RenderOptions(maxNodes = 10))).render()).group("n116")!!.texts()[0]
        assertTrue(cut.startsWith("LOOP · do … while (!delivered") && cut.endsWith("…"), cut)
    }

    @Test
    fun `an edge from a step to the region around it runs to the end of that region`() {
        val drawn = graph(SvgDocuments.skipping())

        // In a loop, whose steps follow each other downwards, the end of the round is the bottom of the box.
        val loop = box(drawn, "each")
        val skip = drawn.edges.single { it.from == "in-stock" && it.to == "each" }
        assertEquals("flow", skip.kind)
        assertEquals("no", skip.label?.text)
        val down = corners(skip.path)
        assertEquals(loop.bottom - Geometry.EDGE_END_GAP, down.last().y, 0.11)
        assertEquals(down[down.size - 2].x, down.last().x)
        down.forEach { assertTrue(loop.contains(it), "$it leaves the loop") }
        assertTrue(loop.contains(skip.label!!.box))
        // The arm that is taken is drawn as well, and the step it leads to is the next one down.
        assertEquals("yes", drawn.edges.single { it.from == "in-stock" && it.to == "ship" }.label?.text)
        assertTrue(box(drawn, "ship").y > box(drawn, "in-stock").bottom)

        // In a parallel section, whose lanes run from left to right, it is the right side.
        val section = box(drawn, "both")
        val lane = drawn.edges.single { it.from == "urgent" && it.to == "both" }
        assertEquals("no", lane.label?.text)
        val right = corners(lane.path)
        assertEquals(section.right - Geometry.EDGE_END_GAP, right.last().x, 0.11)
        assertEquals(right[right.size - 2].y, right.last().y)
        right.forEach { assertTrue(section.contains(it), "$it leaves the section") }

        // Nothing leads from a region to its own steps: being in the box says that.
        assertTrue(drawn.edges.none { it.from == "each" && it.to in setOf("in-stock", "ship") })
        assertEquals(7, drawn.edges.size)
    }

    /** The corners of an edge that is drawn with straight stretches. */
    private fun corners(path: String): List<Point> {
        val points = ArrayList<Point>()
        for (match in Regex("""([MLHV])(-?[\d.]+)(?: (-?[\d.]+))?""").findAll(path)) {
            val (command, first, second) = match.destructured
            points += when (command) {
                "H" -> Point(first.toDouble(), points.last().y)
                "V" -> Point(points.last().x, first.toDouble())
                else -> Point(first.toDouble(), second.toDouble())
            }
        }
        return points
    }

    private fun box(graph: PositionedGraph, id: String): Box =
        graph.nodes.firstOrNull { it.id == id }?.box ?: graph.regions.single { it.id == id }.box
}
