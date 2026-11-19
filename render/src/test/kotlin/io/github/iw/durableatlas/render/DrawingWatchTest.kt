package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.render.Simplification.Level.COLLAPSED
import io.github.iw.durableatlas.render.Simplification.Level.PLAIN_ROWS
import io.github.iw.durableatlas.render.limit.DrawingTimeoutException
import io.github.iw.durableatlas.render.limit.ManualPulse
import io.github.iw.durableatlas.render.limit.ManualTicker
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DrawingWatchTest {
    private val limit = Duration.ofSeconds(30)
    private val document = TestDocuments.load(TestDocuments.INVOICE)
    private val step = DrawingStep(number = 2, count = 7, name = "InvoiceWorkflow", workflowType = "InvoiceWorkflow", nodes = 4)
    private val ticker = ManualTicker()
    private val pulse = ManualPulse()
    private val heard = ArrayList<String>()

    private val progress = object : DrawingProgress {
        override fun stillDrawing(step: DrawingStep, elapsed: Duration) {
            heard += "still ${step.name} $elapsed"
        }

        override fun timedOut(step: DrawingStep, format: String, limit: Duration, running: Boolean, next: Simplification.Level?) {
            heard += "timed out ${step.name} $format $limit running=$running next=$next"
        }

        override fun drawn(step: DrawingStep, elapsed: Duration, missing: List<MissingDiagram>, simplified: List<SimplifiedDiagram>) {
            heard += "drawn ${step.number}/${step.count} ${step.name} $elapsed missing=${missing.map { it.format }} simplified=${simplified.map { "${it.format}:${it.simplification.level}" }}"
        }
    }

    private val watch = DrawingWatch(progress, pulse, ticker)

    /**
     * A format that takes too long for every way of drawing in [slow], and says of the others that it drew them.
     *
     * @param ways the simpler ways it knows
     */
    private class Slow(
        private val slow: Set<Simplification.Level?>,
        private val ways: List<Simplification.Level>,
        private val limit: Duration,
        private val running: Boolean = false,
    ) : DiagramFormat {
        val asked = ArrayList<Simplification?>()
        override val name = "svg"
        override val extension = "svg"

        override fun prepare(document: IrDocument, options: PageOptions): PreparedDiagram {
            asked += options.render.simplification
            if (options.render.simplification?.level in slow) throw DrawingTimeoutException(limit, running)
            return object : PreparedDiagram {
                override val limit = MermaidRendererAccess.limit(document, options)
                override val simplification = options.render.simplification

                override fun render(overlay: DiagramOverlay) = "drawn ${simplification?.level ?: "as asked"}\n"
            }
        }

        override fun simplifications(document: IrDocument, options: PageOptions) = ways
    }

    @Test
    fun `a workflow that is drawn as asked is reported once, with the time it took`() {
        val format = Slow(emptySet(), listOf(PLAIN_ROWS, COLLAPSED), limit)
        val text = watch.draw(step) { attempt ->
            ticker.advance(Duration.ofMillis(400))
            val diagram = attempt.prepare(format, document, PageOptions())
            assertEquals(emptyList(), attempt.missing)
            assertEquals(emptyList(), attempt.simplified)
            diagram!!.render()
        }
        assertEquals("drawn as asked\n", text)
        assertEquals(listOf<Simplification?>(null), format.asked)
        assertEquals(listOf("drawn 2/7 InvoiceWorkflow PT0.4S missing=[] simplified=[]"), heard)
        assertEquals(1, pulse.watched)
    }

    @Test
    fun `while a workflow is being drawn the pulse says so`() {
        watch.draw(step) {
            pulse.beat(Duration.ofSeconds(5))
            pulse.beat(Duration.ofSeconds(15))
            ticker.advance(Duration.ofSeconds(16))
        }
        assertEquals(
            listOf(
                "still InvoiceWorkflow PT5S",
                "still InvoiceWorkflow PT15S",
                "drawn 2/7 InvoiceWorkflow PT16S missing=[] simplified=[]",
            ),
            heard,
        )
    }

    @Test
    fun `a diagram that takes too long is drawn in the first simpler way that does not, with the time the first was given`() {
        val format = Slow(setOf(null, PLAIN_ROWS), listOf(PLAIN_ROWS, COLLAPSED), limit, running = true)
        val diagram = watch.draw(step) { attempt ->
            val diagram = attempt.prepare(format, document, PageOptions(render = RenderOptions(maxNodes = 99)))
            assertEquals(listOf(SimplifiedDiagram("svg", Simplification(COLLAPSED, limit))), attempt.simplified)
            assertEquals(emptyList(), attempt.missing)
            diagram
        }
        assertEquals("drawn COLLAPSED\n", diagram!!.render())
        assertEquals(listOf(null, Simplification(PLAIN_ROWS, limit), Simplification(COLLAPSED, limit)), format.asked)
        assertEquals(
            listOf(
                "timed out InvoiceWorkflow svg PT30S running=true next=PLAIN_ROWS",
                "timed out InvoiceWorkflow svg PT30S running=true next=COLLAPSED",
                "drawn 2/7 InvoiceWorkflow PT0S missing=[] simplified=[svg:COLLAPSED]",
            ),
            heard,
        )
    }

    @Test
    fun `a diagram that no way draws in time is missing`() {
        val format = Slow(setOf(null, PLAIN_ROWS), listOf(PLAIN_ROWS), limit)
        val diagram = watch.draw(step) { attempt ->
            val diagram = attempt.prepare(format, document, PageOptions())
            assertEquals(listOf(MissingDiagram("svg", limit, tried = listOf(PLAIN_ROWS))), attempt.missing)
            diagram
        }
        assertNull(diagram)
        assertEquals(
            listOf(
                "timed out InvoiceWorkflow svg PT30S running=false next=PLAIN_ROWS",
                "timed out InvoiceWorkflow svg PT30S running=false next=null",
                "drawn 2/7 InvoiceWorkflow PT0S missing=[svg] simplified=[]",
            ),
            heard,
        )

        // A format that knows no simpler way gives up at once, and nothing simpler is said to have been tried.
        heard.clear()
        assertNull(
            watch.draw(step) { attempt ->
                attempt.prepare(Slow(setOf(null), emptyList(), limit), document, PageOptions()).also {
                    assertEquals(listOf(MissingDiagram("svg", limit)), attempt.missing)
                }
            },
        )
        assertEquals("This workflow has no SVG diagram: drawing one took longer than the limit of 30 s.", Missing.notice(MissingDiagram("svg", limit)))
        assertEquals(
            "This workflow has no SVG diagram: drawing one took longer than the limit of 30 s, also when it was drawn more simply.",
            Missing.notice(MissingDiagram("svg", limit, listOf(PLAIN_ROWS, COLLAPSED))),
        )
        assertEquals(listOf("timed out InvoiceWorkflow svg PT30S running=false next=null", "drawn 2/7 InvoiceWorkflow PT0S missing=[svg] simplified=[]"), heard)
    }

    @Test
    fun `what a drawing fails with for another reason is the caller's to see, and nothing is reported as drawn`() {
        val broken = object : DiagramFormat {
            override val name = "svg"
            override val extension = "svg"

            override fun prepare(document: IrDocument, options: PageOptions): PreparedDiagram = throw IllegalArgumentException("inconsistent")
        }
        assertFailsWith<IllegalArgumentException> { watch.draw(step) { it.prepare(broken, document, PageOptions()) } }
        assertEquals(emptyList(), heard)
    }
}

/** The node limit of a document as any format reports it. */
private object MermaidRendererAccess {
    fun limit(document: IrDocument, options: PageOptions): NodeLimit = io.github.iw.durableatlas.render.mermaid.MermaidFormat.prepare(document, options).limit
}
