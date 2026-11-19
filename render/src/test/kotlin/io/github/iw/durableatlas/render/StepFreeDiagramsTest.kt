package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
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
import kotlin.test.assertTrue

/**
 * Diagrams of the documents the analyser's tests expect for workflows that call ordinary code
 * around their steps. A call of a function that is not drawn from the inside is a branch that
 * asks whether the function throws, or an unknown call with a reason of its own: both are drawn
 * like any branch and any unknown call, and the pages say of the unknown call what it names.
 */
class StepFreeDiagramsTest {
    @TempDir
    lateinit var temp: Path

    private val documents: Map<String, IrDocument> =
        SvgDocuments.all().filterKeys { it.startsWith("analyser/stepfree/") }.mapKeys { it.key.substringAfterLast('/') }

    private val unseen: IrDocument = documents.getValue("Unseen")

    private fun page(document: IrDocument): String = MermaidFormat.prepare(document, PageOptions()).render()

    private fun svg(document: IrDocument): String = SvgFormat().prepare(document, PageOptions()).render()

    /** Everything an SVG says in words, one entry per text element. */
    private fun words(dom: SvgDom): List<String> = dom.named("text").map { it.textContent }

    @Test
    fun `every document is drawn as a diagram that parses`() {
        assertEquals(
            listOf("Caught", "Circling", "Deep", "Deep.depth-2", "Escaping", "Forked", "Handing", "Keeping", "Looped", "Made", "Published", "Unseen"),
            documents.keys.sorted(),
        )
        for (document in documents.values) MermaidLint.check(MermaidRenderer.render(document))
    }

    @Test
    fun `a call behind which something cannot be followed is drawn as an unknown call, with its reason and the function called`() {
        val nodes = unseen.nodes.filter { it.reason == UnknownReasons.UNSEEN_CODE }
        assertEquals(listOf("Events.enriched", "UnseenImpl.announce"), nodes.mapNotNull { it.label }.sorted())

        val mermaid = MermaidRenderer.render(unseen)
        for (node in nodes) assertContains(mermaid, "${node.id}[\"unknown#58; unseen-code<br/>${node.label}<br/>1 candidate\"]")
        // The page lists it among the unknown nodes, with what could not be followed.
        val text = page(unseen)
        assertContains(text, "| Unknown nodes | 4 |\n")
        assertContains(
            text,
            "- `unseen-code`: `Events.enriched` at `app/src/main/kotlin/plain/Unseen.kt:25`; candidates: `plain.Enricher.enrich(plain.Event)`\n",
        )
        // The card says the reason and the function, like that of any unknown call.
        val said = words(SvgDom(svg(unseen)))
        assertTrue("unseen-code" in said, said.toString())
        assertTrue("Events.enriched" in said, said.toString())
        assertContains(svg(unseen), " unknownNodes=\"4\" ")
    }

    @Test
    fun `a call that may throw is drawn as a branch that leads to a failure called after what it throws`() {
        val asks = unseen.nodes.filter { it.kind == NodeKind.BRANCH }
        assertEquals(listOf("Builder.build throws?", "Events.build throws?", "UnseenImpl.announce throws?"), asks.mapNotNull { it.label }.sorted())
        val several = unseen.nodes.single { it.kind == NodeKind.FAIL && it.name == null }
        assertEquals("IllegalStateException, UnknownEvent", several.label)

        val mermaid = MermaidRenderer.render(unseen)
        for (node in asks) assertContains(mermaid, "${node.id}{\"${node.label}\"}")
        assertContains(mermaid, "${several.id}([\"fail<br/>IllegalStateException, UnknownEvent\"])")
        // A branch is no unknown node: nothing about it is unresolved.
        val said = words(SvgDom(svg(unseen)))
        assertTrue(said.any { "throws?" in it }, said.toString())
        assertEquals(4, unseen.stats.unknown)
    }

    @Test
    fun `the index counts the unknown calls among them and none of the branches`() {
        val ir = Files.createDirectories(temp.resolve("ir"))
        for ((name, document) in documents) Files.writeString(ir.resolve("$name.ir.json"), IrJson.encode(document))
        DiagramDirectory.render(ir, temp.resolve("out"), DirectoryOptions(formats = listOf(MermaidFormat)))
        val index = Files.readString(temp.resolve("out").resolve("index.md"))

        assertContains(index, "| `Unseen` | `plain.UnseenImpl` | `degraded` | 14 | 4 | [mermaid](Unseen.md) |")
        // Four branches and four failures, and nothing unknown.
        assertContains(index, "| `Keeping` | `plain.KeepingImpl` | `degraded` | 16 | 0 | [mermaid](Keeping.md) |")
    }
}
