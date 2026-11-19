package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.render.Simplification.Level.PLAIN_ROWS
import io.github.iw.durableatlas.render.limit.DrawingTimeoutException
import io.github.iw.durableatlas.render.limit.ManualPulse
import io.github.iw.durableatlas.render.limit.ManualTicker
import io.github.iw.durableatlas.render.mermaid.MermaidFormat
import io.github.iw.durableatlas.render.svg.SvgFormat
import io.github.iw.durableatlas.render.svg.SvgOptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a directory of diagrams is told while it is drawn, when its files appear, and what becomes of a diagram that takes too long. */
class DiagramDirectoryProgressTest {
    @TempDir
    lateinit var temp: Path

    private val out: Path
        get() = temp.resolve("out")

    private val limit = Duration.ofSeconds(30)
    private val ticker = ManualTicker()
    private val pulse = ManualPulse()
    private val heard = ArrayList<String>()

    private val progress = object : DrawingProgress {
        override fun stillDrawing(step: DrawingStep, elapsed: Duration) {
            heard += "${step.number}/${step.count} ${step.name}: still drawing after ${elapsed.seconds} s"
        }

        override fun timedOut(step: DrawingStep, format: String, limit: Duration, running: Boolean, next: Simplification.Level?) {
            heard += "${step.number}/${step.count} ${step.name}: $format timed out, next ${next?.key}"
        }

        override fun drawn(step: DrawingStep, elapsed: Duration, missing: List<MissingDiagram>, simplified: List<SimplifiedDiagram>) {
            heard += "${step.number}/${step.count} ${step.name}: ${step.nodes} nodes, ${elapsed.toMillis()} ms" +
                missing.joinToString("") { ", no ${it.format}" } + simplified.joinToString("") { ", ${it.format} ${it.simplification.level.key}" }
        }
    }

    private fun render(formats: List<DiagramFormat>): RenderSummary =
        DiagramDirectory.render(TestDocuments.directory, out, DirectoryOptions(formats = formats, progress = progress), DrawingWatch(progress, pulse, ticker))

    /**
     * A format with a time limit that some workflows do not keep.
     *
     * @param slow for a workflow type, the ways of drawing it that take too long; null stands for drawing it as asked
     * @param onPrepare called with the workflow type before each diagram is prepared
     */
    private inner class Timed(
        private val slow: Map<String, Set<Simplification.Level?>>,
        private val onPrepare: (String) -> Unit = {},
    ) : DiagramFormat {
        override val name = "svg"
        override val extension = "svg"

        override fun prepare(document: IrDocument, options: PageOptions): PreparedDiagram {
            val simplification = options.render.simplification
            if (simplification == null) onPrepare(document.workflowType)
            ticker.advance(Duration.ofMillis(100))
            if (simplification?.level in slow[document.workflowType].orEmpty()) throw DrawingTimeoutException(limit)
            return object : PreparedDiagram {
                override val limit = MermaidFormat.prepare(document, options).limit
                override val simplification = simplification

                override fun render(overlay: DiagramOverlay): String =
                    "${document.workflowType} ${simplification?.level?.key ?: "as asked"}${overlay.notices.joinToString("") { "\n$it" }}\n"
            }
        }

        override fun simplifications(document: IrDocument, options: PageOptions) = listOf(PLAIN_ROWS)
    }

    private fun written(): List<String> = Files.list(out).use { stream -> stream.map { it.name }.sorted().toList() }

    private fun read(file: String): String = Files.readAllBytes(out.resolve(file)).toString(Charsets.UTF_8)

    @Test
    fun `every workflow is reported as it is drawn, in the order of the summary, with its size and the time it took`() {
        val summary = render(listOf(Timed(emptyMap()), MermaidFormat))
        assertEquals(
            listOf(
                "1/4 FulfilmentWorkflow: 34 nodes, 100 ms",
                "2/4 InvoiceWorkflow: 4 nodes, 100 ms",
                "3/4 odd-workflow: 22 nodes, 100 ms",
                "4/4 OrderWorkflow: 23 nodes, 100 ms",
            ),
            heard,
        )
        assertEquals(listOf(34, 4, 22, 23), summary.diagrams.map { it.nodes })
        assertEquals(4, pulse.watched)
        assertTrue(summary.diagrams.all { it.missing.isEmpty() && it.simplified.isEmpty() })
    }

    @Test
    fun `a workflow that takes long is said to be still drawing, by name`() {
        render(listOf(Timed(emptyMap()) { type -> if (type == "InvoiceWorkflow") listOf(5L, 15L).forEach { pulse.beat(Duration.ofSeconds(it)) } }))
        assertEquals(
            listOf(
                "1/4 FulfilmentWorkflow: 34 nodes, 100 ms",
                "2/4 InvoiceWorkflow: still drawing after 5 s",
                "2/4 InvoiceWorkflow: still drawing after 15 s",
                "2/4 InvoiceWorkflow: 4 nodes, 100 ms",
            ),
            heard.take(4),
        )
    }

    @Test
    fun `the files of a workflow are there, whole, before the next is drawn, and the index comes last`() {
        val seen = LinkedHashMap<String, List<String>>()
        render(listOf(Timed(emptyMap()) { type -> seen[type] = if (Files.exists(out)) written() else emptyList() }, MermaidFormat))

        assertEquals(emptyList(), seen.getValue("FulfilmentWorkflow"))
        assertEquals(listOf("FulfilmentWorkflow.md", "FulfilmentWorkflow.svg"), seen.getValue("InvoiceWorkflow"))
        assertEquals(
            listOf("FulfilmentWorkflow.md", "FulfilmentWorkflow.svg", "InvoiceWorkflow.md", "InvoiceWorkflow.svg", "odd-workflow.md", "odd-workflow.svg"),
            seen.getValue("OrderWorkflow"),
        )
        // Nothing is left of the files that were written beside them first.
        assertEquals(9, written().size)
        assertTrue(written().none { it.startsWith(".") || it.endsWith(".part") }, written().toString())
        assertEquals("index.md", written().single { it.startsWith("index") })
    }

    @Test
    fun `a file is written beside its place first and takes it whole`() {
        val file = out.resolve("deep").resolve("Workflow.svg")
        assertEquals(out.resolve("deep").resolve(".Workflow.svg.part"), DiagramFiles.partOf(file))
        DiagramFiles.write(file, "first\n")
        assertEquals("first\n", Files.readString(file))
        // What an interrupted run left beside it is replaced, and what was there is replaced whole.
        Files.writeString(DiagramFiles.partOf(file), "half of a fi")
        DiagramFiles.write(file, "second\n")
        assertEquals("second\n", Files.readString(file))
        assertEquals(listOf("Workflow.svg"), Files.list(file.parent).use { files -> files.map { it.name }.toList() })
    }

    @Test
    fun `a run that fails on its way leaves the workflows it got to, complete, and no index`() {
        val failing = Timed(emptyMap()) { type -> if (type.startsWith("Odd")) throw IllegalStateException("broken") }
        assertFailsWith<IllegalStateException> { render(listOf(failing, MermaidFormat)) }
        assertEquals(listOf("FulfilmentWorkflow.md", "FulfilmentWorkflow.svg", "InvoiceWorkflow.md", "InvoiceWorkflow.svg"), written())
        GoldenFiles.assertMatches("site/InvoiceWorkflow.md", read("InvoiceWorkflow.md"))
    }

    @Test
    fun `a diagram that takes too long is drawn more simply, and the summary and the index say so`() {
        val summary = render(listOf(Timed(mapOf("InvoiceWorkflow" to setOf(null))), MermaidFormat))

        assertEquals("InvoiceWorkflow rows\n", read("InvoiceWorkflow.svg"))
        assertEquals("OrderWorkflow as asked\n", read("OrderWorkflow.svg"))
        assertEquals(
            listOf(
                "1/4 FulfilmentWorkflow: 34 nodes, 100 ms",
                "2/4 InvoiceWorkflow: svg timed out, next rows",
                "2/4 InvoiceWorkflow: 4 nodes, 200 ms, svg rows",
                "3/4 odd-workflow: 22 nodes, 100 ms",
                "4/4 OrderWorkflow: 23 nodes, 100 ms",
            ),
            heard,
        )
        val invoice = summary.diagrams[1]
        assertEquals(listOf(SimplifiedDiagram("svg", Simplification(PLAIN_ROWS, limit))), invoice.simplified)
        assertEquals(emptyList(), invoice.missing)
        assertEquals(listOf(DiagramLink("svg", "InvoiceWorkflow.svg"), DiagramLink("mermaid", "InvoiceWorkflow.md")), invoice.files)
        assertTrue(summary.diagrams.filter { it !== invoice }.all { it.simplified.isEmpty() })

        // Every Mermaid page is what it is without a limit: the workflow has all its files.
        for (file in written().filter { it.endsWith(".md") && it != "index.md" }) GoldenFiles.assertMatches("site/$file", read(file))
        val index = read("index.md")
        assertContains(index, "| [svg](InvoiceWorkflow.svg) · [mermaid](InvoiceWorkflow.md) |\n")
        assertContains(
            index,
            "\n\nDrawn more simply: the SVG diagram of 1 workflow is drawn with each row ended where it is full, because drawing them as the others are " +
                "took longer than the limit of 30 s: `InvoiceWorkflow`. Nothing in them is wrong; each says what was done.\n\n",
        )
        assertFalse("Not drawn" in index)
    }

    @Test
    fun `a diagram that no way draws in time is not written, and everything else says that it is missing and why`() {
        Files.createDirectories(out)
        // What an earlier run drew of the same workflows.
        Files.writeString(out.resolve("InvoiceWorkflow.svg"), "drawn last week\n")
        Files.writeString(out.resolve("OrderWorkflow.svg"), "drawn last week\n")
        Files.writeString(out.resolve("notes.txt"), "kept\n")

        val summary = render(listOf(Timed(mapOf("InvoiceWorkflow" to setOf(null, PLAIN_ROWS))), MermaidFormat))

        // The file of the earlier run would pass for this run's, so it is gone; nothing else is touched.
        assertEquals(
            listOf(
                "FulfilmentWorkflow.md", "FulfilmentWorkflow.svg", "InvoiceWorkflow.md", "OrderWorkflow.md", "OrderWorkflow.svg",
                "index.md", "notes.txt", "odd-workflow.md", "odd-workflow.svg",
            ),
            written(),
        )
        assertEquals("OrderWorkflow as asked\n", read("OrderWorkflow.svg"))
        assertEquals(
            listOf(
                "2/4 InvoiceWorkflow: svg timed out, next rows",
                "2/4 InvoiceWorkflow: svg timed out, next null",
                "2/4 InvoiceWorkflow: 4 nodes, 200 ms, no svg",
            ),
            heard.filter { "Invoice" in it },
        )

        val invoice = summary.diagrams[1]
        assertEquals(listOf(MissingDiagram("svg", limit, tried = listOf(PLAIN_ROWS))), invoice.missing)
        assertEquals(listOf(DiagramLink("mermaid", "InvoiceWorkflow.md")), invoice.files)
        assertTrue(summary.diagrams.filter { it !== invoice }.all { it.missing.isEmpty() && it.files.size == 2 })

        // The page of the workflow says that its SVG diagram is missing; the pages of the others say nothing new.
        val notice = "> This workflow has no SVG diagram: drawing one took longer than the limit of 30 s, also when it was drawn more simply."
        val page = read("InvoiceWorkflow.md")
        assertContains(page, "\n\n$notice\n\n")
        GoldenFiles.assertMatches("site/InvoiceWorkflow.md", page.replace("$notice\n\n", ""))
        for (file in listOf("FulfilmentWorkflow.md", "OrderWorkflow.md", "odd-workflow.md")) GoldenFiles.assertMatches("site/$file", read(file))

        val index = read("index.md")
        assertContains(index, "| [mermaid](InvoiceWorkflow.md) · no svg |\n")
        assertContains(index, "| [svg](OrderWorkflow.svg) · [mermaid](OrderWorkflow.md) |\n")
        assertContains(
            index,
            "\n\nNot drawn: 1 workflow has no SVG diagram, because drawing one took longer than the limit of 30 s, also when it was drawn more simply: " +
                "`InvoiceWorkflow`. Their other files are complete.\n\n",
        )
    }

    @Test
    fun `an index without a diagram that was given up or drawn more simply is the index as it always was`() {
        render(listOf(MermaidFormat))
        GoldenFiles.assertMatches("site/index.md", read("index.md"))
        assertEquals(emptyList(), IndexPage.gaps(emptyList()))
        assertEquals(emptyList(), IndexPage.simpler(emptyList()))
        assertEquals(
            listOf(
                "Not drawn: 2 workflows have no SVG diagram, because drawing one took longer than the limit of 0.5 s, also when it was drawn more simply: `A`, `B`. " +
                    "Their other files are complete.",
                "Not drawn: 1 workflow has no SVG diagram, because drawing one took longer than the limit of 0.5 s: `C`. Their other files are complete.",
            ),
            IndexPage.gaps(
                listOf(
                    "A" to MissingDiagram("svg", Duration.ofMillis(500), listOf(PLAIN_ROWS)),
                    "B" to MissingDiagram("svg", Duration.ofMillis(500), listOf(PLAIN_ROWS)),
                    "C" to MissingDiagram("svg", Duration.ofMillis(500)),
                ),
            ),
        )
        assertContains(
            IndexPage.simpler(listOf("A", "B").map { it to SimplifiedDiagram("svg", Simplification(Simplification.Level.COLLAPSED, limit)) }).single(),
            "the SVG diagrams of 2 workflows are drawn with regions as single nodes and each row ended where it is full,",
        )
    }

    @Test
    fun `the real formats with next to no time write every Mermaid page and no SVG diagram`() {
        // No machine draws a diagram in a billionth of a second, and every drawing asks the clock before it lays anything out.
        val formats = DiagramFormats.configured(DiagramFormats.all, SvgOptions(timeLimit = Duration.ofNanos(1)), html = true)
        val summary = DiagramDirectory.render(TestDocuments.directory, out, DirectoryOptions(formats = formats, progress = progress))

        assertEquals(listOf("FulfilmentWorkflow.md", "InvoiceWorkflow.md", "OrderWorkflow.md", "index.md", "odd-workflow.md"), written())
        for (diagram in summary.diagrams) {
            assertEquals(listOf("svg", "html"), diagram.missing.map { it.format })
            assertEquals(listOf("mermaid"), diagram.files.map { it.format })
            assertEquals(0, diagram.collapsedRegions)
        }
        // A workflow with regions is tried with them as single nodes too, one without is not; the page gives up with its diagram.
        assertEquals(
            listOf(
                "1/4 FulfilmentWorkflow: svg timed out, next rows",
                "1/4 FulfilmentWorkflow: svg timed out, next regions",
                "1/4 FulfilmentWorkflow: svg timed out, next null",
                "1/4 FulfilmentWorkflow: html timed out, next rows",
                "1/4 FulfilmentWorkflow: html timed out, next regions",
                "1/4 FulfilmentWorkflow: html timed out, next null",
                "2/4 InvoiceWorkflow: svg timed out, next rows",
                "2/4 InvoiceWorkflow: svg timed out, next null",
                "2/4 InvoiceWorkflow: html timed out, next rows",
                "2/4 InvoiceWorkflow: html timed out, next null",
            ),
            heard.filter { "timed out" in it }.take(10),
        )
        assertContains(read("OrderWorkflow.md"), "> This workflow has no SVG diagram: drawing one took longer than the limit of 0.000000001 s, also when it was drawn more simply.")
        assertContains(read("OrderWorkflow.md"), "> This workflow has no HTML diagram: drawing one took longer than the limit of 0.000000001 s")
        assertContains(read("index.md"), "| [mermaid](OrderWorkflow.md) · no svg · no html |\n")
    }

    @Test
    fun `pages that hold the SVG diagrams are drawn from the arrangement of the files`() {
        val svgOnly = DiagramFormats.configured(listOf(DiagramFormats.SVG), SvgOptions())
        val withPages = DiagramFormats.configured(listOf(DiagramFormats.SVG), SvgOptions(), html = true)
        assertEquals(listOf("svg", "html"), withPages.map { it.name })
        // One format arranges for both: the page asks the format of the files.
        val svg = withPages[0] as SvgFormat
        val document = TestDocuments.load(TestDocuments.ORDER)
        val file = svg.prepare(document).render()
        assertContains(withPages[1].prepare(document).render(), file)
        assertEquals(svgOnly.single().prepare(document).render(), file)
    }
}
