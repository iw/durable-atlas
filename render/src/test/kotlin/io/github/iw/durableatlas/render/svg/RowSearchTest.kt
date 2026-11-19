package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.layout.LayoutBuilder
import io.github.iw.durableatlas.render.svg.compose.DiagramComposer
import io.github.iw.durableatlas.render.svg.compose.RowSearch
import io.github.iw.durableatlas.render.svg.engine.ElkLayoutEngine
import io.github.iw.durableatlas.render.svg.engine.LayoutEngine
import io.github.iw.durableatlas.render.svg.scene.SceneBuilder
import io.github.iw.durableatlas.render.svg.text.FontMetricsMeasurer
import io.github.iw.durableatlas.render.svg.text.TextFitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How much the search for the rows of a flow hands to the layout engine, and that it finds the
 * breaks it would find with all of the flow placed. These tests count graphs and nodes, never
 * time: a count is the same on every machine.
 */
class RowSearchTest {
    private val wrapWidth = SvgOptions.DEFAULT_WRAP_WIDTH.toDouble()

    /**
     * The most nodes of a graph the search asks for at the default width where the flow is a line of steps with
     * edges to a few steps far along: the steps near a row, or a row. All that is left of a long flow is many more.
     */
    private val nearGraph = 24

    /** [document] arranged, its rows found as they are for every diagram unless [rows] names another way. */
    private fun compose(document: IrDocument, engine: LayoutEngine, rows: RowSearch? = null): PositionedGraph {
        val layout = LayoutBuilder.build(document, RenderOptions())
        val scene = SceneBuilder(layout, FontMetricsMeasurer, RenderOptions.DEFAULT_MAX_LABEL_LENGTH).build()
        val fitter = TextFitter(FontMetricsMeasurer)
        return if (rows == null) DiagramComposer(scene, engine, fitter, wrapWidth).compose() else DiagramComposer(scene, engine, fitter, wrapWidth, rows).compose()
    }

    @Test
    fun `a break that leaves nearly the whole flow in its row is not placed to learn that the row is too wide`() {
        // Every arm of the chain ends the workflow, so a break inside an arm takes two steps out of the row and leaves the rest.
        val engine = CountingEngine(ElkLayoutEngine())
        val graph = compose(StressDocuments.ifChain(24), engine)

        assertEquals(8, graph.rows.size)
        // With every such row placed: 261 graphs with 7383 nodes, 73 of them of more than 40 nodes, the largest of 75.
        // As it is: 164 graphs with 2566 nodes, 12 of more than 40, the largest of 42.
        assertTrue(engine.graphs.sum() <= 4000, "${engine.graphs.sum()} nodes were handed to the layout engine in ${engine.graphs.size} graphs")
        assertTrue(engine.graphs.count { it > 40 } <= 25, "${engine.graphs.count { it > 40 }} graphs of more than 40 nodes were laid out")
        assertTrue(engine.graphs.max() <= 45, "a graph of ${engine.graphs.max()} nodes was laid out")
    }

    @Test
    fun `a group is laid out once, however many rows it is tried in`() {
        val elk = ElkLayoutEngine()
        val engine = CountingEngine(elk)
        val graph = compose(StressDocuments.loops(12, 3), engine)

        assertEquals(12, graph.regions.size)
        // As it was, the algorithm ran 313 times: for 61 rows, and 252 times for the twelve loops in them.
        assertEquals(engine.graphs.size + 12, elk.runs)
    }

    @Test
    fun `a group that comes again with other edges across its border is laid out again`() {
        // The same loops, but each step in them can fail to a step after them: which of these edges a row holds changes what a loop is handed.
        val document = StressDocuments.loops(3, 2).let { plain ->
            plain.copy(edges = plain.edges + io.github.iw.durableatlas.ir.IrEdge("l01-2", "l03", io.github.iw.durableatlas.ir.EdgeKind.EXCEPTION, "Failure"))
        }
        val cached = compose(document, ElkLayoutEngine())
        // What is drawn does not depend on what was kept: an engine that has seen other graphs before draws the same.
        val warm = ElkLayoutEngine().also { compose(StressDocuments.loops(3, 2), it) }
        assertEquals(cached, compose(document, warm))
    }

    @Test
    fun `the search finds the breaks it would find with all that is left of the flow placed`() {
        // Every document the tests of the diagrams draw; and the shapes that take long with everything placed, at a
        // size that still gets through, with shapes that have regions and handlers.
        val documents = SvgDocuments.all().values + listOf(
            StressDocuments.tried(40), StressDocuments.tried(24, clauses = 3), StressDocuments.tried(20, clauses = 4), StressDocuments.ifChain(16),
            StressDocuments.returns(16), StressDocuments.chain(60), StressDocuments.loops(12, 3), SvgDocuments.saga(30),
        )
        var fewer = 0
        for (document in documents) {
            val whole = CountingEngine(ElkLayoutEngine())
            val near = CountingEngine(ElkLayoutEngine())
            assertEquals(compose(document, whole, RowSearch.WHOLE), compose(document, near), document.workflowType)
            assertTrue(near.graphs.sum() <= whole.graphs.sum(), document.workflowType)
            if (near.graphs.sum() < whole.graphs.sum()) fewer++
        }
        // The two are not one and the same for these: for some the search was handed less than all.
        assertTrue(fewer >= 6, "$fewer documents were handed over in smaller graphs")
    }

    @Test
    fun `the search places the steps near each row, never all that is left`() {
        // A try of forty steps. With all that is left placed for every row a break is tried for, the first graph holds 44 nodes.
        val whole = CountingEngine(ElkLayoutEngine())
        compose(StressDocuments.tried(40), whole, RowSearch.WHOLE)
        assertEquals(44, whole.graphs.max())
        assertTrue(whole.graphs.count { it > nearGraph } >= 15, "${whole.graphs.count { it > nearGraph }} graphs of more than $nearGraph nodes")

        // As diagrams are drawn, no graph is larger than a row and a few steps, however long the flow. Placed whole,
        // the longer of these take minutes: an engine that is handed so large a graph ends the test at once.
        for (steps in listOf(40, 120, 240)) {
            val near = CountingEngine(ElkLayoutEngine(), largest = nearGraph)
            val graph = compose(StressDocuments.tried(steps), near)
            assertEquals(steps + 3, graph.nodes.size)
            assertTrue(graph.rows.size >= steps / 10, "${graph.rows.size} rows for $steps steps")
        }
    }

    @Test
    fun `what the search hands the layout engine grows in step with the flow`() {
        fun handed(steps: Int): Int = CountingEngine(ElkLayoutEngine(), largest = nearGraph).also { compose(StressDocuments.tried(steps), it) }.graphs.sum()
        val short = handed(60)
        val long = handed(240)
        // Four times the steps are four times the rows, each found among the steps near it: about four times the nodes.
        assertTrue(long <= 5 * short, "$long nodes were handed over for 240 steps, $short for 60")
    }

    @Test
    fun `plain rows are found by placing the steps near each row, never all that is left`() {
        val engine = CountingEngine(ElkLayoutEngine())
        val graph = compose(StressDocuments.tried(120), engine, RowSearch.PLAIN)

        assertTrue(graph.rows.size in 13..16, "${graph.rows.size} rows")
        // Nothing larger than the steps near a row, and a handful of graphs for each row, where the search takes some eighty.
        assertTrue(engine.graphs.max() <= nearGraph, "a graph of ${engine.graphs.max()} nodes was laid out")
        assertTrue(engine.graphs.size <= 4 * graph.rows.size, "${engine.graphs.size} graphs for ${graph.rows.size} rows")
    }

    @Test
    fun `plain rows hold what the rows of the search hold, and none is wider than the limit`() {
        val document = StressDocuments.tried(30)
        val searched = compose(document, ElkLayoutEngine())
        val plain = compose(document, ElkLayoutEngine(), RowSearch.PLAIN)

        assertEquals(searched.nodes.map { it.id }.sorted(), plain.nodes.map { it.id }.sorted())
        assertEquals(document.nodes.size, plain.nodes.size)
        assertEquals(searched.edges.map { Triple(it.from, it.to, it.kind) }.sortedBy { it.toString() }, plain.edges.map { Triple(it.from, it.to, it.kind) }.sortedBy { it.toString() })
        assertEquals(document.edges.size, plain.edges.sumOf { it.sources.size })
        assertTrue(plain.rows.size > 1)
        assertTrue(plain.rows.all { it.width <= wrapWidth }, plain.rows.map { it.width }.toString())
        // Each row but the last is as full as it gets: the next step would not fit into it.
        assertTrue(plain.rows.dropLast(1).all { it.width > wrapWidth - 2 * Geometry.CARD_MAX_WIDTH }, plain.rows.map { it.width }.toString())
        // No card lies on another.
        val cards = plain.nodes.map { it.box }
        assertTrue(cards.indices.none { a -> (a + 1 until cards.size).any { b -> cards[a].intersects(cards[b]) } })
    }

    @Test
    fun `plain rows break where many bundles of edges cross if a row can end nowhere else`() {
        // Four clauses catch what any step fails with: wherever a row ends, edges to four steps cross the break,
        // which is more than the search lets a break have. It then places the whole flow, and draws it as one row.
        val engine = CountingEngine(ElkLayoutEngine())
        val graph = compose(StressDocuments.tried(40, clauses = 4), engine, RowSearch.PLAIN)

        assertTrue(graph.rows.size >= 5, "${graph.rows.size} rows")
        assertTrue(graph.rows.all { it.width <= wrapWidth })
        assertTrue(engine.graphs.max() <= nearGraph, "a graph of ${engine.graphs.max()} nodes was laid out")
        assertEquals(46, graph.nodes.size)
        assertEquals(40 * 4, graph.edges.count { it.kind == "exception" })
    }

    @Test
    fun `a flow that fits one row is one row either way`() {
        val document = StressDocuments.chain(4)
        assertEquals(compose(document, ElkLayoutEngine()), compose(document, ElkLayoutEngine(), RowSearch.PLAIN))
    }
}
