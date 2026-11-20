package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.Detail
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Diagrams of the overviews the analyser's tests expect, beside those of the full documents of
 * the same sources. An overview has no node for a call behind which a step is only possible:
 * its diagram is drawn from what is left, and every page says how many calls were left out and
 * why. A document that left nothing out is drawn as it always was, whatever it says it is.
 */
class OverviewDiagramsTest {
    @TempDir
    lateinit var temp: Path

    private val all: Map<String, IrDocument> = SvgDocuments.all()

    /** The overviews, by the directory and name of each, as in `shop/Wrapped`. */
    private val overviews: Map<String, IrDocument> = all.filterKeys { it.startsWith("overview/") }.mapKeys { it.key.removePrefix("overview/") }

    private fun overview(name: String): IrDocument = overviews.getValue(name)

    /** The full document of the same workflow. */
    private fun full(name: String): IrDocument = all.getValue("analyser/$name")

    private fun page(document: IrDocument): String = MermaidFormat.prepare(document, PageOptions()).render()

    private fun svg(document: IrDocument): String = SvgFormat().prepare(document, PageOptions()).render()

    /** Everything an SVG says in words, one entry per text element. */
    private fun words(svg: String): String = SvgDom(svg).named("text").joinToString(" ") { it.textContent }

    @Test
    fun `every overview is drawn as a diagram that parses, with fewer nodes than the full one or the same`() {
        assertEquals(28, overviews.size)
        for ((name, document) in overviews) {
            assertEquals(Detail.OVERVIEW, document.detail, name)
            MermaidLint.check(MermaidRenderer.render(document))
            assertTrue(document.nodes.size <= full(name).nodes.size, name)
            assertEquals(null, full(name).detail, name)
        }
        // Two of them leave nothing out: only a node that could not be read is told what was called.
        assertEquals(
            listOf("DynamicDispatchWorkflow", "shop/Kept"),
            overviews.filterValues { it.leftOutCalls.isEmpty() }.keys.toList(),
        )
    }

    @Test
    fun `the SVG of an overview says how many calls it left out and why, below the diagram and in what it says of itself`() {
        val fulfilment = overview("shop/Fulfilment")
        val text = svg(fulfilment)

        assertEquals(31, fulfilment.leftOutCalls.size)
        assertContains(text, " unknownNodes=\"2\" skippedCalls=\"19\" leftOutCalls=\"31\" ")
        assertContains(
            words(text),
            "Overview: 31 calls that could not be read are not drawn: 15 are not modelled, and the code handed to them is drawn as run once where they stand; " +
                "16 lead to code that is not in the analysed sources. An analysis with --detail full draws each as an unknown node.",
        )
        // The full diagram says nothing of the kind, and has a card for each of those calls.
        val before = svg(full("shop/Fulfilment"))
        assertFalse("leftOutCalls" in before)
        assertFalse("Overview:" in words(before))
        assertEquals(33, SvgDom(before).nodes.count { it.getAttribute("data-kind") == "unknown" })
        assertEquals(2, SvgDom(text).nodes.count { it.getAttribute("data-kind") == "unknown" })
    }

    @Test
    fun `the note says of one call and of calls of one kind what holds for them`() {
        assertContains(
            words(svg(overview("shop/Wrapped"))),
            "Overview: 1 call that could not be read is not drawn: it is not modelled, and the code handed to it is drawn as run once where it stands. " +
                "An analysis with --detail full draws it as an unknown node.",
        )
        assertContains(
            words(svg(overview("shop/Helped"))),
            "Overview: 3 calls that could not be read are not drawn: they lead to code that is not in the analysed sources. ",
        )
        assertContains(
            words(svg(overview("shop/Nested"))),
            "Overview: 3 calls that could not be read are not drawn: they are not modelled, and the code handed to them is drawn as run once where they stand. ",
        )
        val direct = overview("shop/Direct")
        assertEquals(listOf(UnknownReasons.DYNAMIC_DISPATCH, UnknownReasons.DYNAMIC_DISPATCH), direct.leftOutCalls.map { it.reason })
        assertContains(words(svg(direct)), "2 calls that could not be read are not drawn: they lead to code that is not in the analysed sources. ")
    }

    @Test
    fun `the page of an overview counts the calls it left out and lists them last, each with its reason, what was called and its place`() {
        val text = page(overview("shop/Blocked"))

        assertContains(text, "| Unknown nodes | 0 |\n| Skipped calls | 2 |\n| Calls left out | 6 |\n")
        assertContains(
            text,
            "## Calls left out\n\n${PageContent.LEFT_OUT_CALLS}\n\n" +
                "- `unsupported`: `withRequestContext` at `app/src/main/kotlin/shop/Blocked.kt:37`\n" +
                "- `unseen-code`: `Helper.remember` at `app/src/main/kotlin/shop/Blocked.kt:45`; candidates: `shop.OrderRepository.save(shop.Order)`\n",
        )
        assertTrue(text.indexOf("## Skipped calls") in 0 until text.indexOf("## Calls left out"))
        assertTrue(text.endsWith("- `unseen-code`: `Helper.describe` at `app/src/main/kotlin/shop/Blocked.kt:58`; candidates: `shop.OrderMapper.toText(shop.Order)`\n"))
        // Nothing is left to list as unknown, and the full page has no such section.
        assertFalse("## Unknown nodes" in text)
        val before = page(full("shop/Blocked"))
        assertFalse("left out" in before)
        assertContains(before, "| Unknown nodes | 6 |\n| Skipped calls | 2 |\n")
    }

    @Test
    fun `a call that is kept is drawn as any unknown node, with what was called on it`() {
        val kept = overview("shop/Kept")
        val told = kept.nodes.single { it.reason == UnknownReasons.DYNAMIC_DISPATCH }

        assertEquals("Notifier.send", told.label)
        assertContains(MermaidRenderer.render(kept), "${told.id}[\"unknown#58; dynamic-dispatch<br/>Notifier.send<br/>2 candidates\"]")
        assertContains(MermaidRenderer.render(full("shop/Kept")), "${told.id}[\"unknown#58; dynamic-dispatch<br/>2 candidates\"]")
        // The card says why the call could not be read, and what was called.
        val card = SvgDom(svg(kept)).nodes.single { it.getAttribute("id").endsWith(told.id) }
        assertContains(card.textContent, "dynamic-dispatch")
        assertContains(card.textContent, "Notifier.send")
        assertContains(page(kept), "- `dynamic-dispatch`: `Notifier.send` at `app/src/main/kotlin/shop/Kept.kt:41`; candidates: `shop.EmailNotifier`, `shop.SmsNotifier`")
        assertEquals(kept.nodes.filter { it.kind == NodeKind.UNKNOWN }.map { it.id }, full("shop/Kept").nodes.filter { it.kind == NodeKind.UNKNOWN }.map { it.id })
    }

    @Test
    fun `a document that left nothing out is drawn as it always was, whatever it says it is`() {
        for (name in listOf("analyser/shop/Delegated", "analyser/shop/Forever", "analyser/TimerWorkflow", "analyser/calls/Logged", "demo/OrderWorkflow")) {
            val document = all.getValue(name)
            for (said in listOf(Detail.OVERVIEW, Detail.FULL)) {
                assertEquals(svg(document), svg(document.copy(detail = said)), "$name as $said")
                assertEquals(page(document), page(document.copy(detail = said)), "$name as $said")
            }
        }
    }

    @Test
    fun `the calls an overview left out are no part of the drawing`() {
        val looped = overview("shop/Looped")
        val without = looped.copy(leftOutCalls = emptyList())

        assertEquals(MermaidRenderer.render(without), MermaidRenderer.render(looped))
        val drawn = svg(looped)
        val plain = svg(without)
        assertEquals(SvgDom(plain).nodes.map { it.getAttribute("id") }, SvgDom(drawn).nodes.map { it.getAttribute("id") })
        // The note stands below the drawing: the body is where it is without it.
        assertEquals(
            Regex("<g class=\"diagram\" transform=\"[^\"]*\">").find(plain)?.value,
            Regex("<g class=\"diagram\" transform=\"[^\"]*\">").find(drawn)?.value,
        )
        assertFalse("Overview:" in words(plain))
    }

    @Test
    fun `the index has a column for the calls that were left out only where there are some`() {
        val ir = Files.createDirectories(temp.resolve("ir"))
        val shop = overviews.filterKeys { it.startsWith("shop/") }.mapKeys { it.key.removePrefix("shop/") }
        for ((name, document) in shop) Files.writeString(ir.resolve("$name.ir.json"), IrJson.encode(document))
        val summary = DiagramDirectory.render(ir, temp.resolve("out"), DirectoryOptions(formats = listOf(MermaidFormat)))
        val index = Files.readString(temp.resolve("out").resolve("index.md"))

        assertContains(index, "| Workflow | Implementation | Model | Nodes | Unknown | Skipped | Left out | Diagram |\n|---|---|---|---:|---:|---:|---:|---|\n")
        assertContains(index, "| `Fulfilment` | `shop.FulfilmentImpl` | `degraded` | 23 | 2 | 19 | 31 | [mermaid](Fulfilment.md) |")
        assertContains(index, "| `Kept` | `shop.KeptImpl` | `degraded` | 9 | 5 | 0 | 0 | [mermaid](Kept.md) |")
        val total = shop.values.sumOf { it.leftOutCalls.size }
        assertContains(index, "\nLeft out: the diagrams are overviews, and $total calls that could not be read have no node in them: ")
        assertEquals(shop.values.map { it.leftOutCalls.size }, summary.diagrams.map { it.leftOutCalls })

        // The full documents of the same workflows have no such column.
        val plain = Files.createDirectories(temp.resolve("plain"))
        for (name in shop.keys) Files.writeString(plain.resolve("$name.ir.json"), IrJson.encode(full("shop/$name")))
        DiagramDirectory.render(plain, temp.resolve("plain-out"), DirectoryOptions(formats = listOf(MermaidFormat)))
        val before = Files.readString(temp.resolve("plain-out").resolve("index.md"))
        assertFalse("Left out" in before)
        assertContains(before, "| Workflow | Implementation | Model | Nodes | Unknown | Skipped | Diagram |\n|---|---|---|---:|---:|---:|---|\n")
        assertContains(before, "| `Fulfilment` | `shop.FulfilmentImpl` | `degraded` | 56 | 33 | 19 | [mermaid](Fulfilment.md) |")
    }
}
