package io.github.iw.durableatlas.render.svg

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.Simplification
import io.github.iw.durableatlas.render.Simplification.Level.COLLAPSED
import io.github.iw.durableatlas.render.Simplification.Level.PLAIN_ROWS
import io.github.iw.durableatlas.render.limit.Deadline
import io.github.iw.durableatlas.render.limit.DrawingRunner
import io.github.iw.durableatlas.render.limit.DrawingTimeoutException
import io.github.iw.durableatlas.render.limit.ManualTicker
import io.github.iw.durableatlas.render.limit.Ticker
import io.github.iw.durableatlas.render.svg.engine.ElkLayoutEngine
import io.github.iw.durableatlas.render.svg.engine.LayoutEngine
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The time a diagram is allowed, and what is drawn when it is not enough. Time is what the
 * test's clock says: the layout engine of these tests lays out as the real one does and moves
 * that clock by twenty minutes for every graph, and a diagram is allowed ten hours, so one that
 * needs more than thirty graphs takes too long, on any machine. Nobody waits: the clock on the
 * wall, which is what the caller of a drawing waits by, never gets near ten hours.
 */
class SvgTimeLimitTest {
    private val ticker = ManualTicker()
    /** The time the formats of these tests allow a diagram, by the test's clock. */
    private val allowed = Duration.ofHours(10)

    /** The time that simpler drawings say was not enough, where a test asks for one itself. */
    private val limit = Duration.ofSeconds(30)

    /**
     * The most nodes a graph holds that the search for the rows of a flow asks for at the default width: the steps
     * near a row, or a row. A graph of all that is left of a long flow holds many more.
     */
    private val nearGraph = 24

    /** How many graphs the engines of [format] were handed. */
    private var graphs = 0

    private fun format(options: SvgOptions = SvgOptions(timeLimit = allowed)): SvgFormat =
        SvgFormat(
            options,
            engines = { deadline ->
                val real = ElkLayoutEngine(deadline)
                LayoutEngine { graph ->
                    graphs++
                    ticker.advance(Duration.ofMinutes(20))
                    real.layout(graph)
                }
            },
            runner = DrawingRunner(ticker),
        )

    private fun simpler(level: Simplification.Level, render: RenderOptions = RenderOptions()) =
        PageOptions(render = render.copy(simplification = Simplification(level, limit)))

    /** A try of forty steps: the search for its rows takes some 340 graphs, plain rows about fifteen. */
    private val long: IrDocument = StressDocuments.tried(40)

    @Test
    fun `a diagram that takes longer than allowed is given up, and drawn in time with plain rows`() {
        val format = format()
        val failure = assertFailsWith<DrawingTimeoutException> { format.prepare(long) }
        assertEquals(allowed, failure.limit)
        assertFalse(failure.running, "the drawing asks the clock before every graph, so it stops itself")
        // It stopped where its time ran out, not where it would have been done.
        assertTrue(graphs in 30..31, "$graphs graphs")

        // The search hands the engine small graphs, which is no help with an engine that takes as long for any.
        assertEquals(listOf(PLAIN_ROWS), format.simplifications(long))
        graphs = 0
        val plain = format.prepare(long, simpler(PLAIN_ROWS))
        assertTrue(graphs < 30, "$graphs graphs")
        assertEquals(Simplification(PLAIN_ROWS, limit), plain.simplification)

        val svg = plain.render()
        val dom = SvgDom(svg)
        assertEquals(long.nodes.size, dom.nodes.size)
        assertEquals(long.edges.size, dom.edges.size)
        assertContains(
            svg,
            "Drawing this diagram with the best breaks between its rows took longer than the limit of 30 s, so each row ends where it is full.",
        )
        assertContains(svg, " collapsedRegions=\"0\" simplified=\"rows\" timeLimit=\"PT30S\">")
        assertEquals(Simplification(PLAIN_ROWS, limit), SvgDiagrams.simplification(svg))
    }

    @Test
    fun `a diagram drawn as asked has its rows found with the steps near each placed, and says nothing of how`() {
        // A try of 120 steps. Placing all that is left of it for every row takes minutes; an engine that is handed
        // so large a graph ends the test at once.
        val document = StressDocuments.tried(120)
        val engines = ArrayList<CountingEngine>()
        val format = SvgFormat(
            SvgOptions(timeLimit = null),
            engines = { deadline -> CountingEngine(ElkLayoutEngine(deadline), largest = nearGraph).also { engines += it } },
            runner = DrawingRunner(),
        )
        val drawn = format.prepare(document)

        assertNull(drawn.simplification)
        assertEquals(1, engines.size)
        assertTrue(engines.single().graphs.size > 100, "${engines.single().graphs.size} graphs")
        val svg = drawn.render()
        assertEquals(document.nodes.size, SvgDom(svg).nodes.size)
        assertFalse("simplified=" in svg || "timeLimit=" in svg || "took longer" in svg || "steps near" in svg)
        // No simpler way stands for it: what was the first of them is the way every diagram is drawn.
        assertEquals(listOf("rows", "regions"), Simplification.Level.entries.map { it.key })
        assertNull(Simplification.Level.ofKey("near"))
    }

    @Test
    fun `a file that an earlier version drew with this search after its own had run out of time is a diagram drawn as asked`() {
        val format = SvgFormat(SvgOptions(timeLimit = null))

        // As that version wrote it: with a remark below the drawing and two attributes that name what was done.
        val document = StressDocuments.tried(12)
        val earlier = Files.readString(Path.of("src", "test", "resources", "earlier", "TriedWorkflow.near.svg"))
        assertContains(earlier, " collapsedRegions=\"0\" simplified=\"near\" timeLimit=\"PT30S\">")
        assertContains(earlier, "so each was chosen with the steps near it in view, not all that follow it.")
        assertNull(SvgDiagrams.simplification(earlier))
        val again = format.restyle(document, earlier)
        assertNull(again.simplification)
        assertEquals(0, again.limit.collapsedRegions)
        val redrawn = again.render()
        // Everything is where the file has it, and nothing is said of time: that remark was about a search no diagram is drawn with any more.
        assertEquals(SvgDiagrams.positionedGraph(earlier), SvgDiagrams.positionedGraph(redrawn))
        assertFalse("simplified=" in redrawn || "timeLimit=" in redrawn || "took longer" in redrawn)
        assertEquals(document.nodes.size, SvgDom(redrawn).nodes.size)
        // Asked for a simpler drawing, the file still decides: it is drawn as it stands.
        assertEquals(redrawn, format.restyle(document, earlier, simpler(PLAIN_ROWS)).render())

        // The same for what this version draws, had it been marked so: it is redrawn as the diagram it is.
        for (drawn in listOf(long, SvgDocuments.demo(), StressDocuments.loops(8, 3))) {
            val plain = format.prepare(drawn).render()
            val marked = plain.replace(" collapsedRegions=\"0\">", " collapsedRegions=\"0\" simplified=\"near\" timeLimit=\"PT30S\">")
            assertTrue(marked != plain, drawn.workflowType)
            assertNull(SvgDiagrams.simplification(marked))
            assertEquals(plain, format.restyle(drawn, marked).render(), drawn.workflowType)
        }
    }

    @Test
    fun `a diagram that is drawn within its time is the one that is drawn without a limit, and says nothing of time`() {
        val unlimited = SvgFormat(SvgOptions(timeLimit = null))
        val limited = SvgFormat(SvgOptions(timeLimit = Duration.ofMinutes(10)))
        for (document in listOf(SvgDocuments.demo(), SvgDocuments.regionEnds(), SvgDocuments.handlerSteps(), StressDocuments.loops(4, 2), StressDocuments.tried(12))) {
            val svg = limited.prepare(document).render()
            assertEquals(unlimited.prepare(document).render(), svg, document.workflowType)
            assertFalse("simplified=" in svg || "timeLimit=" in svg || "took longer" in svg, document.workflowType)
            assertNull(SvgDiagrams.simplification(svg))
            assertNull(limited.prepare(document).simplification)
        }
        assertEquals(SvgOptions.DEFAULT_TIME_LIMIT, SvgOptions().timeLimit)
        assertEquals(Duration.ofSeconds(30), SvgOptions.DEFAULT_TIME_LIMIT)
        assertFailsWith<IllegalArgumentException> { SvgOptions(timeLimit = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { SvgOptions(timeLimit = Duration.ofSeconds(-1)) }
    }

    @Test
    fun `what was given up or drawn is kept for the page that holds the diagram`() {
        val format = format()
        val page = SvgHtmlFormat(format)
        assertFailsWith<DrawingTimeoutException> { format.prepare(long) }
        val plain = format.prepare(long, simpler(PLAIN_ROWS)).render()
        val spent = graphs

        // The page gives up at once what its diagram was given up for, and holds the diagram that was drawn.
        assertEquals(listOf(PLAIN_ROWS), page.simplifications(long))
        assertFailsWith<DrawingTimeoutException> { page.prepare(long) }
        val held = page.prepare(long, simpler(PLAIN_ROWS))
        assertEquals(spent, graphs, "the diagram was arranged again for its page")
        assertEquals(Simplification(PLAIN_ROWS, limit), held.simplification)
        assertContains(held.render(), plain)

        // Another document takes its place; the first is arranged anew when it comes again.
        format.prepare(StressDocuments.chain(3))
        val other = graphs
        format.prepare(long, simpler(PLAIN_ROWS))
        assertTrue(graphs > other)
    }

    @Test
    fun `a format says which simpler ways make a difference for a document`() {
        val format = format()
        val loops = StressDocuments.loops(8, 3)
        assertEquals(listOf(PLAIN_ROWS, COLLAPSED), format.simplifications(loops))
        // Without regions there is nothing to draw as a single node.
        assertEquals(listOf(PLAIN_ROWS), format.simplifications(StressDocuments.chain(5)))
        // Over the node limit the regions are single nodes as it is.
        assertEquals(listOf(PLAIN_ROWS), format.simplifications(loops, PageOptions(render = RenderOptions(maxNodes = 10))))
        // Without rows there are no breaks to look for.
        assertEquals(listOf(COLLAPSED), format(SvgOptions(wrapWidth = 0, timeLimit = allowed)).simplifications(loops))
        // What is drawn more simply already is not made simpler by asking again.
        assertEquals(emptyList(), format.simplifications(loops, simpler(PLAIN_ROWS)))
        assertEquals(emptyList(), SvgFormat(SvgOptions(wrapWidth = 0)).simplifications(StressDocuments.chain(5)))
    }

    @Test
    fun `with regions as single nodes the diagram is the one of a document over the node limit, and says why`() {
        val loops = StressDocuments.loops(8, 3)
        val format = SvgFormat(SvgOptions(timeLimit = null))
        val collapsed = format.prepare(loops, simpler(COLLAPSED))
        assertEquals(8, collapsed.limit.collapsedRegions)
        assertEquals(10, collapsed.limit.drawn)
        assertFalse(collapsed.limit.exceeded)

        val svg = collapsed.render()
        val dom = SvgDom(svg)
        assertEquals(10, dom.nodes.size)
        assertEquals(0, dom.regions.size)
        assertContains(
            svg,
            "Drawing this diagram in full took longer than the limit of 30 s, so its 8 regions are drawn as single nodes (10 nodes shown)",
        )
        assertContains(svg, "and each row ends where it is full. Regions are loops, parallel sections and compensations.")
        assertContains(svg, " collapsedRegions=\"8\" simplified=\"regions\" timeLimit=\"PT30S\">")

        // The same cards, in the same places, as with a node limit that the document is over.
        val limited = format.prepare(loops, simpler(PLAIN_ROWS, RenderOptions(maxNodes = 10)))
        assertEquals(SvgDiagrams.positionedGraph(limited.render()), SvgDiagrams.positionedGraph(svg))
        // That one says both: why its regions are single nodes, and why its rows end where they do.
        assertContains(limited.render(), "This workflow has 34 nodes, more than the limit of 10, so its 8 regions are drawn as single nodes (10 nodes shown).")
        assertContains(limited.render(), "Drawing this diagram with the best breaks between its rows took longer than the limit of 30 s")
    }

    @Test
    fun `a simpler diagram is redrawn as it is, with what it says of itself`() {
        val format = SvgFormat(SvgOptions(timeLimit = null))
        for (level in listOf(PLAIN_ROWS, COLLAPSED)) {
            val document = StressDocuments.loops(8, 3)
            val drawn = format.prepare(document, simpler(level)).render()
            // The file says how it was drawn; whoever redraws it does not have to.
            val again = format.restyle(document, drawn)
            assertEquals(Simplification(level, limit), again.simplification)
            assertEquals(drawn, again.render(), level.name)
            assertEquals(if (level == COLLAPSED) 8 else 0, again.limit.collapsedRegions)
        }
        // A file that says nothing of the kind is redrawn in full, whatever it is asked with.
        val document = StressDocuments.loops(2, 2)
        val full = format.prepare(document).render()
        assertEquals(full, format.restyle(document, full, simpler(COLLAPSED)).render())
        // Something else in that place of a file is no simplification this tool knows.
        assertNull(SvgDiagrams.simplification(full.replace(" collapsedRegions=\"0\">", " collapsedRegions=\"0\" simplified=\"sketch\" timeLimit=\"PT30S\">")))
        assertNull(SvgDiagrams.simplification(full.replace(" collapsedRegions=\"0\">", " collapsedRegions=\"0\" simplified=\"rows\" timeLimit=\"soon\">")))
        assertNull(SvgDiagrams.simplification("<svg/>"))
    }

    /** A clock that stands still for its first [quiet] readings and then says that a day has passed. */
    private class Sudden(private val quiet: Int) : Ticker {
        var readings = 0

        override fun nanos(): Long = if (++readings <= quiet) 0 else Duration.ofDays(1).toNanos()
    }

    @Test
    fun `the layout library is told to stop, stops between two of its phases, and what it leaves half done is not drawn`() {
        val document = StressDocuments.chain(6)

        // Left alone, the library asks before each of its phases whether to go on: a few dozen times for one graph.
        val patient = Sudden(Int.MAX_VALUE)
        SvgFormat(SvgOptions(timeLimit = allowed), ::ElkLayoutEngine, DrawingRunner(patient)).prepare(document)
        assertTrue(patient.readings > 25, "${patient.readings} readings")

        // The time runs out after the library has begun: the drawing asks three times before that.
        val sudden = Sudden(quiet = 5)
        val failure = assertFailsWith<DrawingTimeoutException> {
            SvgFormat(SvgOptions(timeLimit = allowed), ::ElkLayoutEngine, DrawingRunner(sudden)).prepare(document)
        }
        assertFalse(failure.running)
        // It was told at its next question and went no further: whoever had called it asked once more on the way out,
        // and the drawing ended there.
        assertTrue(sudden.readings in 6..16, "${sudden.readings} readings")
    }

    @Test
    fun `a drawing that is deep in a step that asks nothing is left behind, and the caller goes on`() {
        val begun = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stuck = SvgFormat(
            SvgOptions(timeLimit = Duration.ofMillis(1)),
            // The drawing sticks where it gets its engine, which is before it asks the clock for the first time:
            // however slow the machine, it is stuck when the caller's wait ends, or has not begun yet.
            engines = { deadline ->
                begun.countDown()
                while (true) {
                    try {
                        if (release.await(1, TimeUnit.DAYS)) break
                    } catch (_: InterruptedException) {
                        continue
                    }
                }
                LayoutEngine {
                    deadline.check()
                    error("the drawing went on after it was told to stop")
                }
            },
            // The clock of the drawing stands still: it is the caller's wait that ends.
            runner = DrawingRunner(ticker, grace = Duration.ofMillis(1)),
        )
        val failure = assertFailsWith<DrawingTimeoutException> { stuck.prepare(StressDocuments.chain(3)) }
        assertTrue(failure.running)
        assertEquals(Duration.ofMillis(1), failure.limit)
        // The drawing is still there. Let go, it finds at its next question that it was told to stop, and ends.
        assertTrue(begun.await(1, TimeUnit.MINUTES))
        release.countDown()
    }
}
