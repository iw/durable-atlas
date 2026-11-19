package io.github.iw.durableatlas.cli

import io.github.iw.durableatlas.render.DrawingStep
import io.github.iw.durableatlas.render.MissingDiagram
import io.github.iw.durableatlas.render.SimplifiedDiagram
import io.github.iw.durableatlas.render.Simplification
import io.github.iw.durableatlas.render.Simplification.Level.COLLAPSED
import io.github.iw.durableatlas.render.Simplification.Level.PLAIN_ROWS
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The lines that say how the drawing gets on, and the lines and keys of a summary that say what it could not do in time. */
class ProgressLinesTest {
    private val lines = ArrayList<String>()
    private val progress = ProgressLines(lines::add)
    private val limit = Duration.ofSeconds(30)
    private val order = DrawingStep(number = 12, count = 64, name = "OrderWorkflow", workflowType = "OrderWorkflow", nodes = 23)

    @Test
    fun `a workflow that is drawn is one line with its place in the run, its size and the time it took`() {
        progress.drawn(order, Duration.ofMillis(412), emptyList(), emptyList())
        progress.drawn(DrawingStep(1, 1, "Tiny", "Tiny", nodes = 1), Duration.ofMillis(3), emptyList(), emptyList())
        progress.drawn(order.copy(executions = 5), Duration.ofSeconds(75), emptyList(), emptyList())
        progress.drawn(order.copy(name = "4f2a9c1/OrderWorkflow", executions = 1), Duration.ofMillis(950), emptyList(), emptyList())
        assertEquals(
            listOf(
                "12/64 OrderWorkflow: 23 nodes, 0.4 s",
                "1/1 Tiny: 1 node, 0.0 s",
                "12/64 OrderWorkflow: 23 nodes, 5 executions, 75.0 s",
                "12/64 4f2a9c1/OrderWorkflow: 23 nodes, 1 execution, 1.0 s",
            ),
            lines,
        )
        assertTrue(lines.all { it.length < 80 })
    }

    @Test
    fun `a workflow that takes long is named while it is drawn`() {
        progress.stillDrawing(order, Duration.ofMillis(5004))
        progress.stillDrawing(order.copy(executions = 2), Duration.ofSeconds(15))
        assertEquals(
            listOf(
                "12/64 OrderWorkflow: still drawing after 5 s (23 nodes)",
                "12/64 OrderWorkflow: still drawing after 15 s (23 nodes, 2 executions)",
            ),
            lines,
        )
    }

    @Test
    fun `a drawing that is given up is said to be, with the time it had and what follows`() {
        progress.timedOut(order, "svg", limit, running = false, next = PLAIN_ROWS)
        progress.timedOut(order, "svg", limit, running = true, next = COLLAPSED)
        progress.timedOut(order, "svg", Duration.ofMillis(500), running = false, next = null)
        progress.drawn(order, Duration.ofMillis(31_260), emptyList(), listOf(SimplifiedDiagram("svg", Simplification(PLAIN_ROWS, limit))))
        progress.drawn(order, Duration.ofMillis(62_000), emptyList(), listOf(SimplifiedDiagram("svg", Simplification(COLLAPSED, limit))))
        progress.drawn(order, Duration.ofMillis(92_000), listOf(MissingDiagram("svg", limit), MissingDiagram("html", limit)), emptyList())
        assertEquals(
            listOf(
                "12/64 OrderWorkflow: svg not drawn within 30 s; drawing it with plain rows",
                "12/64 OrderWorkflow: svg not drawn within 30 s; its layout did not stop and runs on in the background; drawing it with regions as single nodes",
                "12/64 OrderWorkflow: svg not drawn within 0.5 s; giving it up",
                "12/64 OrderWorkflow: 23 nodes, 31.3 s, svg with plain rows",
                "12/64 OrderWorkflow: 23 nodes, 62.0 s, svg with regions as single nodes",
                "12/64 OrderWorkflow: 23 nodes, 92.0 s, no svg, no html",
            ),
            lines,
        )
    }

    @Test
    fun `a summary for people names the workflows without a diagram and those with a simpler one`() {
        val rows = Simplification(PLAIN_ROWS, limit)
        val regions = Simplification(COLLAPSED, limit)
        assertEquals(emptyList(), Summaries.shortfallLines(listOf(Summaries.Shortfall("Fine", emptyList(), emptyList()))))
        assertEquals(
            listOf(
                "2 workflow(s) without svg diagram, which was not drawn within 30 s: Huge, Vast",
                "1 workflow(s) without html diagram, which was not drawn within 30 s: Vast",
                "2 workflow(s) with a simpler svg diagram, because the one asked for was not drawn within 30 s: Long (plain rows), Deep (regions as single nodes)",
            ),
            Summaries.shortfallLines(
                listOf(
                    Summaries.Shortfall("Fine", emptyList(), emptyList()),
                    Summaries.Shortfall("Huge", listOf(MissingDiagram("svg", limit)), emptyList()),
                    Summaries.Shortfall("Long", emptyList(), listOf(SimplifiedDiagram("svg", rows))),
                    Summaries.Shortfall("Vast", listOf(MissingDiagram("svg", limit), MissingDiagram("html", limit)), emptyList()),
                    Summaries.Shortfall("Deep", emptyList(), listOf(SimplifiedDiagram("svg", regions))),
                ),
            ),
        )
    }

    @Test
    fun `the formats of a run are named with how many workflows have a diagram in them, where some have none`() {
        val formats = listOf("svg", "html", "mermaid")
        val fine = Summaries.Shortfall("Fine", emptyList(), listOf(SimplifiedDiagram("svg", Simplification(PLAIN_ROWS, limit))))
        // A simpler diagram is a diagram: the sentence is the one of a run in which nothing took long.
        assertEquals("svg, html, mermaid", Summaries.formatsDrawn(formats, listOf(fine, fine)))
        assertEquals("svg, html, mermaid", Summaries.formatsDrawn(formats, emptyList()))
        val huge = Summaries.Shortfall("Huge", listOf(MissingDiagram("svg", limit)), emptyList())
        val vast = Summaries.Shortfall("Vast", listOf(MissingDiagram("svg", limit), MissingDiagram("html", limit)), emptyList())
        assertEquals("svg (1 of 3), html (2 of 3), mermaid", Summaries.formatsDrawn(formats, listOf(fine, huge, vast)))
        assertEquals("svg (0 of 1), mermaid", Summaries.formatsDrawn(listOf("svg", "mermaid"), listOf(huge)))
    }

    @Test
    fun `a summary for a machine lists what is missing or simpler, and says nothing of a workflow drawn as asked`() {
        fun keys(missing: List<MissingDiagram>, simplified: List<SimplifiedDiagram>): String =
            kotlinx.serialization.json.buildJsonObject { Summaries.shortfall(this, missing, simplified) }.toString()

        assertEquals("{}", keys(emptyList(), emptyList()))
        assertEquals("""{"notDrawn":[{"format":"svg","limitSeconds":30.0}]}""", keys(listOf(MissingDiagram("svg", limit)), emptyList()))
        assertEquals(
            """{"simplified":[{"format":"svg","how":"rows","limitSeconds":0.5},{"format":"html","how":"regions","limitSeconds":0.5}]}""",
            keys(
                emptyList(),
                listOf(
                    SimplifiedDiagram("svg", Simplification(PLAIN_ROWS, Duration.ofMillis(500))),
                    SimplifiedDiagram("html", Simplification(COLLAPSED, Duration.ofMillis(500))),
                ),
            ),
        )
    }
}
