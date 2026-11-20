package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.IrFormatException
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.Request
import io.github.iw.durableatlas.ir.RequestKind
import io.github.iw.durableatlas.ir.SourceLocation
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.ir.testing.JsonSchemaChecker
import io.github.iw.durableatlas.overlay.coverage.CoverageJson
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.diagram.OverlayOptions
import io.github.iw.durableatlas.overlay.diagram.OverlaySummary
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.history.HistoryFormatException
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.SvgFile
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.badge
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.dimmed
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.highlighted
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.title
import io.github.iw.durableatlas.render.DiagramFormats
import io.github.iw.durableatlas.render.DiagramLink
import io.github.iw.durableatlas.render.PageOptions
import io.github.iw.durableatlas.render.svg.SvgFormat
import io.github.iw.durableatlas.render.svg.SvgOptions
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OverlayDirectoryTest {
    @TempDir
    lateinit var dir: Path

    private val out: Path get() = dir.resolve("out")

    private fun demo(target: Path = out, options: OverlayOptions = OverlayOptions()): OverlaySummary =
        OverlayDirectory.write(Fixtures.demoIr, Fixtures.demoHistories, target, options)

    private fun read(file: String, directory: Path = out): String = Files.readString(directory.resolve(file))

    /** Every file under [directory], by its path inside it. */
    private fun contents(directory: Path): Map<String, String> =
        Files.walk(directory).use { files ->
            files.filter { it.isRegularFile() }.toList().associate { it.relativeTo(directory).toString() to Files.readString(it) }
        }.toSortedMap()

    @Test
    fun `coverage and one diagram per execution are written in every format, with an index`() {
        val summary = demo()

        val paths = listOf("cancelled", "continue-as-new", "paid", "promotion", "timeout")
        val expected = listOf("index.md", "OrderWorkflow.svg", "OrderWorkflow.coverage.json", "OrderWorkflow.coverage.svg", "OrderWorkflow.coverage.md") +
            paths.flatMap { listOf("executions/OrderWorkflow.$it.svg", "executions/OrderWorkflow.$it.md") }
        assertEquals(expected.sorted(), contents(out).keys.sorted())

        assertEquals("index.md", summary.index)
        assertEquals(emptyList(), summary.unmatched)
        assertEquals(emptyList(), summary.skipped)
        val workflow = summary.workflows.single()
        assertEquals("OrderWorkflow", workflow.workflowType)
        assertEquals("OrderWorkflow.ir.json", workflow.irFile)
        assertEquals("OrderWorkflow.coverage.json", workflow.coverageFile)
        assertEquals("OrderWorkflow.svg", workflow.diagramFile)
        assertEquals(listOf(DiagramLink("svg", "OrderWorkflow.coverage.svg"), DiagramLink("mermaid", "OrderWorkflow.coverage.md")), workflow.files)
        assertEquals(paths.map { "OrderWorkflow.$it.json" }, workflow.executions.map { it.history })
        assertEquals(
            listOf(CloseStatus.FAILED, CloseStatus.CONTINUED_AS_NEW, CloseStatus.COMPLETED, CloseStatus.COMPLETED, CloseStatus.FAILED),
            workflow.executions.map { it.status },
        )
        assertEquals(
            listOf(DiagramLink("svg", "executions/OrderWorkflow.paid.svg"), DiagramLink("mermaid", "executions/OrderWorkflow.paid.md")),
            workflow.executions[2].files,
        )
        assertEquals(listOf(0, 0, 0, 1, 0), workflow.executions.map { it.events.unmapped })

        // The coverage file is what the summary holds.
        val json = read("OrderWorkflow.coverage.json")
        JsonSchemaChecker.forSchema(CoverageJson.schema()).assertValid(json)
        assertEquals(workflow.coverage, CoverageJson.decode(json))
        assertEquals(5, workflow.coverage.executions)

        GoldenFiles.assertMatches("demo/index.md", read("index.md"))
    }

    @Test
    fun `the coverage diagram greys what never ran and counts the rest`() {
        // Without the order that continues as new, one node was never run.
        val histories = Files.createDirectories(dir.resolve("histories"))
        for (path in listOf("paid", "cancelled", "timeout")) {
            Files.copy(Fixtures.demoHistories.resolve("OrderWorkflow.$path.json"), histories.resolve("OrderWorkflow.$path.json"))
        }
        OverlayDirectory.write(Fixtures.demoIr, histories, out)
        val svg = SvgFile(read("OrderWorkflow.coverage.svg"))

        val never = svg.node("n20-continue-as-new")
        assertEquals("0.35", never.getAttribute("opacity"))
        assertEquals("TERMINAL: Continue-as-new — never run", never.title())
        assertNull(never.badge())

        assertEquals("×3", svg.node("n00-start").badge())
        assertEquals("", svg.node("n00-start").getAttribute("opacity"))
        assertEquals("×2", svg.node("n08-compensation").badge())
        // One order was paid and had two shipments.
        assertEquals("×1", svg.node("n12-shipments").badge())
        assertEquals("×2", svg.node("n13-shipment-workflow").badge())
        assertEquals("CHILD WORKFLOW: ShipmentWorkflow — ×2", svg.node("n13-shipment-workflow").title())
        // Handlers count their calls. A query leaves no event, so its card says nothing.
        assertEquals("×1", svg.node("signal-approvePayment").badge())
        assertEquals("×1", svg.node("update-changeAddress").badge())
        val query = svg.node("query-getStatus")
        assertNull(query.badge())
        assertFalse(query.dimmed)

        assertTrue(svg.edge("n02-fraud-check-version", "n04-parallel").dimmed)
        assertFalse(svg.edge("n02-fraud-check-version", "n03-fraud-check").dimmed)
        assertTrue(svg.edge("n19-continue-as-new-suggested", "n20-continue-as-new").dimmed)
        assertFalse(svg.edge("n07-await-payment", "n08-compensation", "timeout").dimmed)

        assertEquals("OrderWorkflow: coverage · com.example.orders.OrderWorkflowImpl", svg.title)
        assertEquals(
            listOf(
                "OrderWorkflow: coverage",
                "Hit counts from 3 executions. What is grey was never run.",
                "Executions: 3",
                "Nodes hit: 29 of 30",
                "Events mapped: 53",
                "Unmapped events: 0",
                "Uncertain matches: 0",
            ),
            svg.notes,
        )

        val mermaid = read("OrderWorkflow.coverage.md")
        assertContains(mermaid, "# OrderWorkflow: coverage")
        assertContains(mermaid, "n_n13_shipment_workflow[[\"ShipmentWorkflow<br/>×2\"]]")
        assertContains(mermaid, "n_n20_continue_as_new([\"continueAsNew<br/>OrderWorkflow<br/>never run\"])")
        assertContains(mermaid, "n_n07_await_payment -->|\"paid · ×1\"| n_n12_shipments")
        assertContains(mermaid, "class n_n20_continue_as_new dimmed")
        assertContains(mermaid, "| Nodes hit | 29 of 30 |")
        assertContains(mermaid, "[All workflows](index.md)")
    }

    @Test
    fun `the diagram of an execution makes its path stand out and greys everything else`() {
        demo()
        val svg = SvgFile(read("executions/OrderWorkflow.cancelled.svg"))

        val onPath = listOf(
            "n00-start", "n01-validate-order", "n02-fraud-check-version", "n03-fraud-check", "n04-parallel", "n05-reserve-stock",
            "n06-authorise-payment", "n07-await-payment", "n08-compensation", "n09-release-stock", "n10-void-payment",
            "n11-order-cancelled", "signal-cancelOrder",
        )
        val offPath = listOf(
            "n12-shipments", "n13-shipment-workflow", "n14-shipment-ready", "n15-invoice-number", "n16-award-points",
            "n17-discount-policy", "n18-returns-window", "n19-continue-as-new-suggested", "n20-continue-as-new", "n21-end",
            "signal-approvePayment", "query-getStatus", "update-changeAddress",
        )
        for (id in onPath) assertTrue(svg.node(id).highlighted, id)
        for (id in offPath) {
            assertTrue(svg.node(id).dimmed, id)
            assertEquals("0.35", svg.node(id).getAttribute("opacity"), id)
        }
        assertTrue(svg.edge("n07-await-payment", "n08-compensation").highlighted)
        assertTrue(svg.edge("n07-await-payment", "n08-compensation", "timeout").dimmed)
        assertTrue(svg.edge("n07-await-payment", "n12-shipments").dimmed)
        assertTrue(svg.edge("signal-cancelOrder", "n07-await-payment", "wakes").highlighted)
        assertTrue(svg.edge("signal-approvePayment", "n07-await-payment", "wakes").dimmed)
        assertEquals("OrderWorkflow: OrderWorkflow.cancelled · com.example.orders.OrderWorkflowImpl", svg.title)
        assertContains(svg.notes, "Status: failed")
        assertContains(svg.notes, "History: OrderWorkflow.cancelled.json")

        // What ran more than once says how often.
        val paid = SvgFile(read("executions/OrderWorkflow.paid.svg"))
        assertEquals("×2", paid.node("n13-shipment-workflow").badge())
        assertNull(paid.node("n15-invoice-number").badge())
        assertTrue(paid.node("n12-shipments").highlighted)

        // An event without a node is named on the diagram of its execution.
        val promotion = SvgFile(read("executions/OrderWorkflow.promotion.svg"))
        assertContains(
            promotion.notes.joinToString(" "),
            "Event 68, ActivityTaskScheduled LookUpPromotion, has no node in this diagram. It may lie behind the unknown call n17-discount-policy.",
        )

        val mermaid = read("executions/OrderWorkflow.cancelled.md")
        assertContains(mermaid, "# OrderWorkflow: OrderWorkflow.cancelled")
        assertContains(mermaid, "[All workflows](../index.md)")
        assertContains(mermaid, "| Status | failed |")
        assertTrue(Regex("class [^\n]*n_n11_order_cancelled[^\n]* highlighted").containsMatchIn(mermaid))
        assertTrue(Regex("class [^\n]*n_n21_end[^\n]* dimmed").containsMatchIn(mermaid))
    }

    @Test
    fun `every SVG overlay is arranged exactly as the plain diagram`() {
        demo()
        val plain = SvgFile(read("OrderWorkflow.svg"))

        // The plain diagram is what the renderer draws for the document.
        assertEquals(SvgFormat().prepare(Fixtures.demo, PageOptions(indexFile = "index.md")).render(), plain.text)
        val overlays = contents(out).filterKeys { it.endsWith(".svg") && it != "OrderWorkflow.svg" }
        assertEquals(6, overlays.size)
        for ((file, text) in overlays) {
            assertEquals(plain.layout, SvgFile(text).layout, file)
        }
    }

    @Test
    fun `a plain diagram that is already there is kept, and decides the arrangement`() {
        // The diagram as `render` writes it with other settings: one long row.
        Files.createDirectories(out)
        val wide = SvgFormat(SvgOptions(wrapWidth = 0)).prepare(Fixtures.demo, PageOptions()).render()
        Files.writeString(out.resolve("OrderWorkflow.svg"), wide)

        demo()

        assertEquals(wide, read("OrderWorkflow.svg"))
        assertEquals(SvgFile(wide).layout, SvgFile(read("OrderWorkflow.coverage.svg")).layout)
        assertEquals(SvgFile(wide).layout, SvgFile(read("executions/OrderWorkflow.paid.svg")).layout)
        val fresh = dir.resolve("fresh")
        demo(fresh)
        assertTrue(SvgFile(wide).layout != SvgFile(read("OrderWorkflow.coverage.svg", fresh)).layout)

        // A file that is no diagram of this document is drawn again.
        Files.writeString(out.resolve("OrderWorkflow.svg"), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>")
        demo()
        assertEquals(read("OrderWorkflow.svg", fresh), read("OrderWorkflow.svg"))
        assertEquals(read("OrderWorkflow.coverage.svg", fresh), read("OrderWorkflow.coverage.svg"))
    }

    @Test
    fun `the requests of an activity are drawn on its overlays as on the plain diagram, and left out where asked`() {
        val request = Request(
            kind = RequestKind.HTTP,
            method = "POST",
            target = "{services.stock.url}/reservations",
            keys = listOf("services.stock.url"),
            loc = SourceLocation("demo/src/main/kotlin/Stock.kt", 12),
        )
        val requesting = Fixtures.demo.copy(nodes = Fixtures.demo.nodes.map { if (it.name == "ReserveStock") it.copy(requests = listOf(request)) else it })
        val ir = Files.createDirectories(dir.resolve("ir"))
        Files.writeString(ir.resolve("OrderWorkflow.ir.json"), IrJson.encode(requesting))

        val drawn = dir.resolve("drawn")
        OverlayDirectory.write(ir, Fixtures.demoHistories, drawn, OverlayOptions())
        val plain = SvgFile(read("OrderWorkflow.svg", drawn))
        assertEquals(SvgFormat().prepare(requesting, PageOptions(indexFile = "index.md")).render(), plain.text)
        assertContains(plain.text, "POST {services.st")
        for ((file, text) in contents(drawn).filterKeys { it.endsWith(".svg") && it != "OrderWorkflow.svg" }) {
            assertEquals(plain.layout, SvgFile(text).layout, file)
            assertContains(text, "POST {services.st", message = file)
        }

        // Without them, every file is what the document without requests gives.
        val left = dir.resolve("left")
        OverlayDirectory.write(ir, Fixtures.demoHistories, left, OverlayOptions(requests = false))
        demo()
        assertEquals(contents(out), contents(left))
    }

    @Test
    fun `two runs write the same bytes`() {
        demo()
        val first = contents(out)
        // Into the same directory, where the plain diagram is found, and into another.
        demo()
        assertEquals(first, contents(out))
        val other = dir.resolve("other")
        demo(other)
        assertEquals(first, contents(other))
        assertTrue(first.values.all { it.endsWith("\n") && "\r" !in it })
    }

    @Test
    fun `every history of the sample repository is laid over the document of its workflow type`() {
        val summary = OverlayDirectory.write(Fixtures.sampleIr, Fixtures.sampleHistories, out)

        assertEquals(30, summary.workflows.size)
        assertEquals(summary.workflows.map { it.workflowType }.sorted(), summary.workflows.map { it.workflowType })
        assertEquals(32, summary.workflows.sumOf { it.executions.size })
        assertEquals(emptyList(), summary.unmatched)
        assertEquals(emptyList(), summary.skipped)
        assertEquals(3, summary.workflows.single { it.workflowType == "OrderWorkflow" }.executions.size)
        assertEquals(
            listOf("ComputedActivityNameWorkflow", "DeepHelperChainWorkflow", "DynamicDispatchWorkflow"),
            summary.workflows.filter { it.coverage.events.unmapped > 0 }.map { it.workflowType },
        )
        val files = contents(out)
        // Per workflow type the plain diagram, the coverage as JSON, SVG and Mermaid; per history two diagrams; the index.
        assertEquals(30 * 4 + 32 * 2 + 1, files.size)
        val schema = JsonSchemaChecker.forSchema(CoverageJson.schema())
        files.filterKeys { it.endsWith(".coverage.json") }.values.forEach(schema::assertValid)
        // A child workflow is linked to its own coverage, from the coverage and from the executions of its parent.
        assertContains(files.getValue("OrderWorkflow.coverage.md"), "- `Shipment`: [Shipment.coverage.md](Shipment.coverage.md)")
        assertContains(files.getValue("executions/OrderWorkflow.md"), "- `Shipment`: [../Shipment.coverage.md](../Shipment.coverage.md)")
        assertContains(files.getValue("OrderWorkflow.coverage.svg"), "xlink:href=\"Shipment.coverage.svg\"")
        assertContains(files.getValue("executions/OrderWorkflow.svg"), "xlink:href=\"../Shipment.coverage.svg\"")
        assertContains(files.getValue("OrderWorkflow.svg"), "xlink:href=\"Shipment.svg\"")

        val index = files.getValue("index.md")
        assertContains(index, "32 executions of 30 workflow types.")
        assertContains(index, "- `DynamicDispatchWorkflow.json`, event 5: `ActivityTaskScheduled` `NotifySms`; may lie behind ")
    }

    @Test
    fun `a single history can be laid over, and only its workflow type is written`() {
        val summary = OverlayDirectory.write(Fixtures.sampleIr, Fixtures.sampleHistories.resolve("OrderWorkflow.payment-declined.json"), out)

        assertEquals(listOf("OrderWorkflow"), summary.workflows.map { it.workflowType })
        assertEquals(
            listOf(
                "OrderWorkflow.coverage.json", "OrderWorkflow.coverage.md", "OrderWorkflow.coverage.svg", "OrderWorkflow.svg",
                "executions/OrderWorkflow.payment-declined.md", "executions/OrderWorkflow.payment-declined.svg", "index.md",
            ),
            contents(out).keys.toList(),
        )
        // The child workflow has no history here, so there is nothing to link.
        assertContains(read("OrderWorkflow.coverage.md"), "- `Shipment`: no diagram")
    }

    @Test
    fun `only the formats asked for are written`() {
        val mermaid = dir.resolve("mermaid")
        val summary = demo(mermaid, OverlayOptions(formats = listOf(DiagramFormats.MERMAID)))
        assertTrue(contents(mermaid).keys.none { it.endsWith(".svg") })
        assertNull(summary.workflows.single().diagramFile)
        assertEquals(1 + 1 + 1 + 5, contents(mermaid).size)

        // HTML pages hold the SVG diagram, arranged as the plain one.
        val pages = dir.resolve("pages")
        demo(pages, OverlayOptions(formats = DiagramFormats.configured(listOf(DiagramFormats.SVG), SvgOptions(), html = true)))
        val page = read("executions/OrderWorkflow.paid.html", pages)
        assertTrue(page.startsWith("<!doctype html>"))
        assertContains(page, "<a href=\"../index.md\">All workflows</a>")
        assertEquals(SvgFile(read("OrderWorkflow.svg", pages)).layout, SvgFile(page.substringAfter("<main>\n").substringBefore("</main>")).layout)
        assertTrue(pages.resolve("OrderWorkflow.coverage.html").exists())

        // Without SVG among the formats the pages are still drawn, and no plain diagram is left behind.
        val only = dir.resolve("only-pages")
        demo(only, OverlayOptions(formats = DiagramFormats.configured(listOf(DiagramFormats.MERMAID), SvgOptions(), html = true)))
        assertFalse(only.resolve("OrderWorkflow.svg").exists())
        assertEquals(read("OrderWorkflow.coverage.html", pages), read("OrderWorkflow.coverage.html", only))
    }

    @Test
    fun `the node limit is passed on`() {
        demo(options = OverlayOptions(formats = listOf(DiagramFormats.MERMAID), maxNodes = 10))

        assertContains(read("OrderWorkflow.coverage.md"), "This workflow has 32 nodes, more than the limit of 10")
        // A region drawn as one node takes the count of the region.
        assertContains(read("OrderWorkflow.coverage.md"), "×3")
    }

    @Test
    fun `a history whose workflow type has no document is reported and left out`() {
        val histories = Files.createDirectories(dir.resolve("histories"))
        Files.copy(Fixtures.demoHistories.resolve("OrderWorkflow.paid.json"), histories.resolve("OrderWorkflow.paid.json"))
        Files.copy(Fixtures.sampleHistories.resolve("TimerWorkflow.json"), histories.resolve("TimerWorkflow.json"))
        Files.writeString(histories.resolve("notes.json"), "{\"about\": \"these histories\"}")
        Files.writeString(histories.resolve("list.json"), "[1, 2]")
        Files.writeString(histories.resolve("readme.txt"), "not JSON at all")

        val summary = OverlayDirectory.write(Fixtures.demoIr, histories, out)

        assertEquals(listOf("OrderWorkflow"), summary.workflows.map { it.workflowType })
        val unmatched = summary.unmatched.single()
        assertEquals("TimerWorkflow.json", unmatched.file)
        assertEquals("TimerWorkflow", unmatched.workflowType)
        assertEquals("no workflow document for this workflow type", unmatched.reason)
        assertEquals(listOf("list.json", "notes.json"), summary.skipped)
        assertTrue(contents(out).keys.none { "Timer" in it })
        assertContains(read("index.md"), "## Histories without a diagram")
        assertContains(read("index.md"), "- `TimerWorkflow.json`, workflow type `TimerWorkflow`: no workflow document for this workflow type")
    }

    @Test
    fun `histories that fit no document give an empty index`() {
        val summary = OverlayDirectory.write(Fixtures.demoIr, Fixtures.sampleHistories.resolve("TimerWorkflow.json"), out)

        assertEquals(emptyList(), summary.workflows)
        assertEquals(1, summary.unmatched.size)
        assertEquals(listOf("index.md"), contents(out).keys.toList())
        assertContains(read("index.md"), "0 executions of 0 workflow types.")
    }

    @Test
    fun `two documents of one workflow type leave its histories out`() {
        val ir = Files.createDirectories(dir.resolve("ir"))
        val document = Files.readString(Fixtures.demoIr.resolve("OrderWorkflow.ir.json"))
        Files.writeString(ir.resolve("OrderWorkflow.ir.json"), document)
        Files.writeString(ir.resolve("OrderWorkflow-2.ir.json"), document)
        Files.writeString(ir.resolve("inventory.json"), "{\"workflows\": []}")

        val summary = OverlayDirectory.write(ir, Fixtures.demoHistories.resolve("OrderWorkflow.paid.json"), out)

        assertEquals(emptyList(), summary.workflows)
        assertEquals(
            "several workflow documents for this workflow type: OrderWorkflow-2.ir.json, OrderWorkflow.ir.json",
            summary.unmatched.single().reason,
        )
    }

    @Test
    fun `a history or a document that cannot be read is a failure, and nothing is written`() {
        val histories = Files.createDirectories(dir.resolve("histories"))
        Files.copy(Fixtures.demoHistories.resolve("OrderWorkflow.paid.json"), histories.resolve("OrderWorkflow.paid.json"))
        Files.writeString(histories.resolve("broken.json"), "{\"events\": [{\"eventId\": \"one\"}]}")

        val history = assertFailsWith<HistoryFormatException> { OverlayDirectory.write(Fixtures.demoIr, histories, out) }
        assertTrue(history.message.orEmpty().startsWith("broken.json is not a workflow event history"), history.message)
        assertFalse(out.exists())

        // A file given by name has to be a history.
        assertFailsWith<HistoryFormatException> { OverlayDirectory.write(Fixtures.demoIr, Fixtures.demoIr.resolve("OrderWorkflow.ir.json"), out) }

        val ir = Files.createDirectories(dir.resolve("ir"))
        Files.writeString(ir.resolve("Broken.ir.json"), "{ \"schemaVersion\": 1 }")
        val document = assertFailsWith<IrFormatException> { OverlayDirectory.write(ir, Fixtures.demoHistories, out) }
        assertTrue(document.message.orEmpty().startsWith("Broken.ir.json: "), document.message)

        val inconsistent = Files.readString(Fixtures.demoIr.resolve("OrderWorkflow.ir.json")).replace("\"to\": \"n21-end\"", "\"to\": \"nowhere\"")
        Files.writeString(ir.resolve("Broken.ir.json"), inconsistent)
        val error = assertFailsWith<IllegalArgumentException> { OverlayDirectory.write(ir, Fixtures.demoHistories, out) }
        assertTrue(error.message.orEmpty().startsWith("Broken.ir.json: "), error.message)
        assertFalse(out.exists())
    }
}
