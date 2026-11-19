package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.SkippedCall
import io.github.iw.durableatlas.ir.UnknownReasons
import io.github.iw.durableatlas.render.mermaid.MermaidFormat
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import io.github.iw.durableatlas.render.svg.SvgDocuments
import io.github.iw.durableatlas.render.svg.SvgDom
import io.github.iw.durableatlas.render.svg.SvgFormat
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Diagrams of the documents the analyser's tests expect of a degraded model whose sources call
 * into libraries it lacks: the calls such a document leaves out are no part of the drawing, and
 * every page says that there are some.
 */
class SkippedCallDiagramsTest {
    @TempDir
    lateinit var temp: Path

    private val documents: Map<String, IrDocument> =
        SvgDocuments.all().filterKeys { it.startsWith("analyser/calls/") }.mapKeys { it.key.substringAfterLast('/') }

    private val logged: IrDocument = documents.getValue("Logged")

    private fun page(document: IrDocument): String = MermaidFormat.prepare(document, PageOptions()).render()

    private fun svg(document: IrDocument): String = SvgFormat().prepare(document, PageOptions()).render()

    /** Everything an SVG says in words, one entry per text element. */
    private fun words(dom: SvgDom): List<String> = dom.named("text").map { it.textContent }

    @Test
    fun `every document is drawn as a diagram that parses`() {
        assertEquals(listOf("Foreign", "Handed", "Logged", "Looped", "Newer", "Priced", "Served", "Starred", "Supplied"), documents.keys.toList())
        for (document in documents.values) MermaidLint.check(MermaidRenderer.render(document))
    }

    @Test
    fun `the calls a document left out change nothing of the drawing`() {
        assertEquals(6, logged.skippedCalls.size)
        val without = logged.copy(skippedCalls = emptyList())

        assertEquals(MermaidRenderer.render(without), MermaidRenderer.render(logged))
        val drawn = SvgDom(svg(logged))
        val plain = SvgDom(svg(without))
        assertEquals(plain.nodes.map { it.getAttribute("id") }, drawn.nodes.map { it.getAttribute("id") })
        assertEquals(plain.edges.size, drawn.edges.size)
        // Two activities between the start and the end, and no node for any call into the logging library.
        assertEquals(listOf(NodeKind.START, NodeKind.END, NodeKind.ACTIVITY, NodeKind.ACTIVITY), logged.nodes.map { it.kind }.sorted())
    }

    @Test
    fun `the page of a workflow counts the calls that were left out and lists them after the unknown nodes`() {
        val text = page(documents.getValue("Handed"))

        assertContains(text, "| Unknown nodes | 2 |\n| Skipped calls | 5 |\n")
        assertContains(
            text,
            "## Skipped calls\n\n${PageContent.SKIPPED_CALLS}\n\n" +
                "- `app/src/main/kotlin/calls/Handed.kt:29`: `Retry.retrying(3) { chores.one(input) }`\n" +
                "- `app/src/main/kotlin/calls/Handed.kt:30`: `Money.of(2)`\n",
        )
        assertTrue(text.indexOf("## Unknown nodes") in 0 until text.indexOf("## Skipped calls"))
        assertTrue(text.endsWith("- `app/src/main/kotlin/calls/Handed.kt:33`: `tally(input)`\n"))

        // A document that left nothing out says nothing of it.
        val foreign = page(documents.getValue("Foreign"))
        assertFalse("Skipped" in foreign)
        assertContains(foreign, "| Unknown nodes | 6 |\n")
        assertEquals(page(logged.copy(skippedCalls = emptyList())), page(logged).substringBefore("\n## Skipped calls").replace("| Skipped calls | 6 |\n", ""))
    }

    @Test
    fun `a text that would break the list is written as code`() {
        val odd = logged.copy(skippedCalls = listOf(SkippedCall(logged.skippedCalls.first().loc, "log.info(\"a | b\") { `it` }")))

        val line = page(odd).lines().last { it.startsWith("- ") }
        assertContains(line, "a | b")
        assertTrue(line.startsWith("- `app/src/main/kotlin/calls/Logged.kt:27`: "), line)
    }

    @Test
    fun `the SVG says how many calls were left out, below the diagram and in what it says of itself`() {
        val text = svg(logged)
        val dom = SvgDom(text)

        assertContains(text, " unknownNodes=\"0\" skippedCalls=\"6\" ")
        assertTrue(words(dom).any { "6 calls that did not resolve are not drawn" in it }, words(dom).toString())
        // The count is no part of the drawing: the body is where it is without the note.
        val without = svg(logged.copy(skippedCalls = emptyList()))
        assertFalse("skippedCalls" in without)
        assertFalse("did not resolve" in without)
        assertEquals(
            Regex("<g class=\"diagram\" transform=\"[^\"]*\">").find(without)?.value,
            Regex("<g class=\"diagram\" transform=\"[^\"]*\">").find(text)?.value,
        )
        // One call reads as one.
        assertTrue(words(SvgDom(svg(logged.copy(skippedCalls = logged.skippedCalls.take(1))))).any { "1 call that did not resolve is not drawn" in it })
    }

    @Test
    fun `a node that is kept for a call that may be a step is drawn as any unknown node`() {
        val foreign = documents.getValue("Foreign")
        val kept = foreign.nodes.filter { it.kind == NodeKind.UNKNOWN }

        assertEquals(6, kept.size)
        assertTrue(kept.all { it.reason == UnknownReasons.UNRESOLVED })
        val mermaid = MermaidRenderer.render(foreign)
        for (node in kept) assertContains(mermaid, "${node.id}[\"unknown#58; unresolved<br/>${node.label}\"]")
        // A stub made to continue as new ends the flow, though nothing says as which workflow type.
        val continued = foreign.nodes.single { it.kind == NodeKind.CONTINUE_AS_NEW }
        assertEquals(null, continued.name)
        assertTrue(foreign.edges.none { it.from == continued.id })
        // The functions the compiler could not choose among are listed with the node.
        val priced = page(documents.getValue("Priced"))
        assertContains(priced, "- `unresolved`: `pick` at `app/src/main/kotlin/calls/Priced.kt:43`; candidates: `calls.Pricing.pick(kotlin.Int,calls.Chores)`, `calls.Pricing.pick(kotlin.String,calls.Chores)`")
    }

    @Test
    fun `the index has a column for the calls that were left out only where there are some`() {
        val ir = Files.createDirectories(temp.resolve("ir"))
        for ((name, document) in documents) Files.writeString(ir.resolve("$name.ir.json"), IrJson.encode(document))
        val summary = DiagramDirectory.render(ir, temp.resolve("out"), DirectoryOptions(formats = listOf(MermaidFormat)))
        val index = Files.readString(temp.resolve("out").resolve("index.md"))

        assertContains(index, "| Workflow | Implementation | Model | Nodes | Unknown | Skipped | Diagram |\n|---|---|---|---:|---:|---:|---|\n")
        assertContains(index, "| `Foreign` | `calls.ForeignImpl` | `degraded` | 9 | 6 | 0 | [mermaid](Foreign.md) |")
        assertContains(index, "| `Logged` | `calls.LoggedImpl` | `degraded` | 4 | 0 | 6 | [mermaid](Logged.md) |")
        assertContains(index, "\nSkipped: 29 calls that did not resolve were taken for calls into libraries that the degraded model lacks, and left out of the diagrams. ")
        assertEquals(listOf(0, 5, 6, 7, 2, 4, 2, 0, 3), summary.diagrams.map { it.skippedCalls })

        // Without a call that was left out, the index is what it always was.
        val plain = Files.createDirectories(temp.resolve("plain"))
        for ((name, document) in documents) Files.writeString(plain.resolve("$name.ir.json"), IrJson.encode(document.copy(skippedCalls = emptyList())))
        DiagramDirectory.render(plain, temp.resolve("plain-out"), DirectoryOptions(formats = listOf(MermaidFormat)))
        val before = Files.readString(temp.resolve("plain-out").resolve("index.md"))
        assertFalse("Skipped" in before)
        assertContains(before, "| Workflow | Implementation | Model | Nodes | Unknown | Diagram |\n|---|---|---|---:|---:|---|\n")
        assertContains(before, "| `Logged` | `calls.LoggedImpl` | `degraded` | 4 | 0 | [mermaid](Logged.md) |")
    }
}
