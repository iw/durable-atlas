package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.diagram.OverlayOptions
import io.github.iw.durableatlas.overlay.diagram.OverlaySummary
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.SvgFile
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.badge
import io.github.iw.durableatlas.render.DiagramFormats
import io.github.iw.durableatlas.render.DiagramLink
import io.github.iw.durableatlas.render.DrawingProgress
import io.github.iw.durableatlas.render.DrawingStep
import io.github.iw.durableatlas.render.MissingDiagram
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.RenderOptions
import io.github.iw.durableatlas.render.SimplifiedDiagram
import io.github.iw.durableatlas.render.Simplification
import io.github.iw.durableatlas.render.Simplification.Level.COLLAPSED
import io.github.iw.durableatlas.render.Simplification.Level.PLAIN_ROWS
import io.github.iw.durableatlas.render.svg.SvgFormat
import io.github.iw.durableatlas.render.svg.SvgOptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What an overlay is told while it is drawn, and what becomes of it where the plain diagram takes too long or was drawn more simply. */
class OverlayTimeLimitTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("out")
    private val limit = Duration.ofSeconds(30)
    private val heard = ArrayList<String>()

    private val progress = object : DrawingProgress {
        override fun stillDrawing(step: DrawingStep, elapsed: Duration) {
            heard += "${step.name}: still drawing"
        }

        override fun timedOut(step: DrawingStep, format: String, limit: Duration, running: Boolean, next: Simplification.Level?) {
            heard += "${step.number}/${step.count} ${step.name}: $format timed out, next ${next?.key}"
        }

        override fun drawn(step: DrawingStep, elapsed: Duration, missing: List<MissingDiagram>, simplified: List<SimplifiedDiagram>) {
            heard += "${step.number}/${step.count} ${step.name}: ${step.nodes} nodes, ${step.executions} executions" +
                missing.joinToString("") { ", no ${it.format}" } + simplified.joinToString("") { ", ${it.format} ${it.simplification.level.key}" }
        }
    }

    /** No machine arranges a diagram in a billionth of a second, and every arrangement asks the clock before it lays anything out. */
    private val noTime = SvgOptions(timeLimit = Duration.ofNanos(1))

    private fun demo(options: OverlayOptions = OverlayOptions(progress = progress), target: Path = out): OverlaySummary =
        OverlayDirectory.write(Fixtures.demoIr, Fixtures.demoHistories, target, options)

    private fun read(file: String, directory: Path = out): String = Files.readString(directory.resolve(file))

    private fun contents(directory: Path = out): Map<String, String> =
        Files.walk(directory).use { files ->
            files.filter { it.isRegularFile() }.toList().associate { it.relativeTo(directory).toString() to Files.readString(it) }
        }.toSortedMap()

    @Test
    fun `every workflow type is reported when its overlays are drawn, with its size and its executions`() {
        val summary = demo()
        assertEquals(listOf("1/1 OrderWorkflow: 32 nodes, 5 executions"), heard)
        assertEquals(emptyList(), summary.workflows.single().missing)
        assertEquals(emptyList(), summary.workflows.single().simplified)
        // Nothing is left of the files that were written beside the others first.
        assertTrue(contents().keys.none { it.substringAfterLast('/').startsWith(".") }, contents().keys.toString())
    }

    @Test
    fun `a plain diagram that takes too long leaves no SVG overlay, and everything else is written and says so`() {
        Files.createDirectories(out.resolve("executions"))
        // What an earlier run drew, for another document: nothing an overlay can be drawn on.
        Files.writeString(out.resolve("OrderWorkflow.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
        Files.writeString(out.resolve("OrderWorkflow.coverage.svg"), "drawn last week\n")
        Files.writeString(out.resolve("executions/OrderWorkflow.paid.svg"), "drawn last week\n")
        Files.writeString(out.resolve("notes.txt"), "kept\n")

        val summary = demo(OverlayOptions(formats = DiagramFormats.configured(DiagramFormats.all, noTime), progress = progress))

        val paths = listOf("cancelled", "continue-as-new", "paid", "promotion", "timeout")
        assertEquals(
            (listOf("index.md", "notes.txt", "OrderWorkflow.coverage.json", "OrderWorkflow.coverage.md") + paths.map { "executions/OrderWorkflow.$it.md" }).sorted(),
            contents().keys.sorted(),
        )
        assertEquals(
            listOf(
                "1/1 OrderWorkflow: svg timed out, next rows",
                "1/1 OrderWorkflow: svg timed out, next regions",
                "1/1 OrderWorkflow: svg timed out, next null",
                "1/1 OrderWorkflow: 32 nodes, 5 executions, no svg",
            ),
            heard,
        )
        val workflow = summary.workflows.single()
        assertEquals(listOf(MissingDiagram("svg", Duration.ofNanos(1), listOf(PLAIN_ROWS, COLLAPSED))), workflow.missing)
        assertNull(workflow.diagramFile)
        assertEquals(listOf(DiagramLink("mermaid", "OrderWorkflow.coverage.md")), workflow.files)
        assertEquals(listOf(DiagramLink("mermaid", "executions/OrderWorkflow.paid.md")), workflow.executions[2].files)
        assertEquals(5, workflow.coverage.executions)

        // The Mermaid overlays are those of a run without SVG, but for the line that says what is missing.
        val mermaid = dir.resolve("mermaid")
        demo(OverlayOptions(formats = listOf(DiagramFormats.MERMAID)), mermaid)
        val notice = "> This workflow has no SVG diagram: drawing one took longer than the limit of 0.000000001 s, also when it was drawn more simply."
        for (file in listOf("OrderWorkflow.coverage.md", "executions/OrderWorkflow.paid.md")) {
            assertContains(read(file), "\n$notice\n")
            assertEquals(read(file, mermaid), read(file).replace("$notice\n\n", ""), file)
        }
        assertEquals(read("OrderWorkflow.coverage.json", mermaid), read("OrderWorkflow.coverage.json"))

        val index = read("index.md")
        assertContains(index, "| [mermaid](OrderWorkflow.coverage.md) · [json](OrderWorkflow.coverage.json) | – |\n")
        assertContains(index, "| `OrderWorkflow.paid.json` | – | completed | 22 | 0 | 0 | [mermaid](executions/OrderWorkflow.paid.md) |\n")
        assertContains(
            index,
            "\n\nNot drawn: 1 workflow has no SVG diagram, because drawing one took longer than the limit of 0.000000001 s, also when it was drawn more simply: " +
                "`OrderWorkflow`. Their other files are complete.\n\n",
        )
    }

    @Test
    fun `pages are missing with the diagram they hold`() {
        val formats = DiagramFormats.configured(listOf(DiagramFormats.MERMAID), noTime, html = true)
        val summary = demo(OverlayOptions(formats = formats, progress = progress))
        assertTrue(contents().keys.none { it.endsWith(".html") || it.endsWith(".svg") })
        assertEquals(listOf(MissingDiagram("html", Duration.ofNanos(1), listOf(PLAIN_ROWS, COLLAPSED))), summary.workflows.single().missing)
        assertEquals("1/1 OrderWorkflow: 32 nodes, 5 executions, no svg", heard.last())
    }

    @Test
    fun `a plain diagram that is already there needs no time, and an index without a gap is the index as it always was`() {
        val fresh = dir.resolve("fresh")
        demo(target = fresh)
        Files.createDirectories(out)
        Files.copy(fresh.resolve("OrderWorkflow.svg"), out.resolve("OrderWorkflow.svg"))
        heard.clear()

        val summary = demo(OverlayOptions(formats = DiagramFormats.configured(DiagramFormats.all, noTime), progress = progress))

        assertEquals(listOf("1/1 OrderWorkflow: 32 nodes, 5 executions"), heard)
        assertEquals(emptyList(), summary.workflows.single().missing)
        assertEquals(contents(fresh), contents())
        GoldenFiles.assertMatches("demo/index.md", read("index.md"))
    }

    @Test
    fun `overlays on a plain diagram that was drawn more simply are as simple, and say what it says`() {
        for (level in listOf(PLAIN_ROWS, COLLAPSED)) {
            val target = dir.resolve(level.key)
            Files.createDirectories(target)
            // As a run leaves it whose time for the full diagram ran out.
            val plain = SvgFormat().prepare(Fixtures.demo, PageOptions(render = RenderOptions(simplification = Simplification(level, limit)))).render()
            Files.writeString(target.resolve("OrderWorkflow.svg"), plain)
            heard.clear()

            val summary = demo(target = target)

            assertEquals(plain, read("OrderWorkflow.svg", target))
            val workflow = summary.workflows.single()
            assertEquals(listOf(SimplifiedDiagram("svg", Simplification(level, limit))), workflow.simplified)
            assertEquals(emptyList(), workflow.missing)
            // It was not drawn more simply now, so the run has nothing to report of it.
            assertEquals(listOf("1/1 OrderWorkflow: 32 nodes, 5 executions"), heard)

            val coverage = SvgFile(read("OrderWorkflow.coverage.svg", target))
            val execution = SvgFile(read("executions/OrderWorkflow.paid.svg", target))
            assertEquals(SvgFile(plain).layout, coverage.layout)
            assertEquals(SvgFile(plain).layout, execution.layout)
            val said = when (level) {
                PLAIN_ROWS -> "Drawing this diagram with the best breaks between its rows took longer than the limit of 30 s, so each row ends where it is full."
                COLLAPSED -> "Drawing this diagram in full took longer than the limit of 30 s, so its 3 regions are drawn as single nodes (26 nodes shown) and each row ends"
            }
            for (file in listOf(coverage, execution)) assertContains(file.notes.joinToString(" "), said)
            assertContains(
                read("index.md", target),
                "Drawn more simply: the SVG diagram of 1 workflow is drawn with " + when (level) {
                    PLAIN_ROWS -> "each row ended where it is full,"
                    COLLAPSED -> "regions as single nodes and each row ended where it is full,"
                },
            )
            // The Mermaid overlays are drawn in full all the same.
            assertFalse("took longer" in read("OrderWorkflow.coverage.md", target))
        }
        // A region that is one node counts what ran in it, as it does over the node limit.
        assertEquals("×3", SvgFile(read("OrderWorkflow.coverage.svg", dir.resolve(COLLAPSED.key))).node("n12-shipments").badge())
    }

    @Test
    fun `overlays on a plain diagram that an earlier version marked as drawn with a quicker search are plain overlays`() {
        val fresh = dir.resolve("fresh")
        demo(target = fresh)
        Files.createDirectories(out)
        // As a run of that version left it whose time for the diagram ran out: the rows were then found the way they
        // are found for every diagram now, and the file said so.
        val plain = read("OrderWorkflow.svg", fresh)
        val marked = plain.replace(" collapsedRegions=\"0\">", " collapsedRegions=\"0\" simplified=\"near\" timeLimit=\"PT30S\">")
        assertTrue(marked != plain)
        Files.writeString(out.resolve("OrderWorkflow.svg"), marked)
        heard.clear()

        val summary = demo()

        // The file is kept and decides the arrangement; nothing is drawn more simply, and nothing says so.
        assertEquals(marked, read("OrderWorkflow.svg"))
        assertEquals(emptyList(), summary.workflows.single().simplified)
        assertEquals(emptyList(), summary.workflows.single().missing)
        assertEquals(listOf("1/1 OrderWorkflow: 32 nodes, 5 executions"), heard)
        assertEquals(contents(fresh) - "OrderWorkflow.svg", contents() - "OrderWorkflow.svg")
        assertFalse("Drawn more simply" in read("index.md"))
        assertFalse("simplified=" in read("OrderWorkflow.coverage.svg") || "took longer" in read("OrderWorkflow.coverage.svg"))
    }
}
