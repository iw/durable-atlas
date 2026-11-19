package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.WaitsFor
import io.github.iw.durableatlas.render.mermaid.MermaidFormat
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import io.github.iw.durableatlas.render.svg.SvgDocuments
import io.github.iw.durableatlas.render.svg.SvgDom
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.texts
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.title
import io.github.iw.durableatlas.render.svg.SvgFormat
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Diagrams of the documents the analyser's tests expect for workflows that wait on what handlers
 * feed, start work that nothing waits for, cancel scopes and other workflows, chain promises,
 * retry beside the flow, and start children of a workflow type that two interfaces share.
 */
class PatternDiagramsTest {
    @TempDir
    lateinit var temp: Path

    private val documents: Map<String, IrDocument> =
        SvgDocuments.all().filterKeys { it.startsWith("analyser/patterns/") }.mapKeys { it.key.substringAfterLast('/') }

    private fun IrDocument.step(name: String): String = nodes.single { it.name == name }.id

    private fun svg(document: IrDocument, page: PageOptions = PageOptions()): SvgDom = SvgDom(SvgFormat().prepare(document, page).render())

    @Test
    fun `every document is drawn as a diagram that parses`() {
        assertEquals(16, documents.size)
        for (document in documents.values) MermaidLint.check(MermaidRenderer.render(document))
    }

    // ---- waits that handlers feed

    @Test
    fun `a wait on a queue or a promise is an await that the handlers who feed it wake`() {
        val inbox = documents.getValue("Inbox")
        val mermaid = MermaidRenderer.render(inbox)
        val all = inbox.nodes.filter { it.kind == NodeKind.AWAIT }.sortedBy { it.loc?.line }
        assertEquals(listOf("items.take()", "items.poll()", "items.cancellablePoll()", "items.put()"), all.map { it.label })
        // The last is the wait of the signal for room in the queue, which the workflow method makes by taking.
        val (waits, full) = all.partition { it.label != "items.put()" }
        assertContains(mermaid, "${waits[0].id}{{\"await items\"}}")
        assertContains(mermaid, "${waits[1].id}{{\"await items<br/>timeout 5m\"}}")
        for (handler in inbox.entryPoints.drop(1)) for (wait in waits) assertContains(mermaid, "    ${handler.start} -.->|\"items\"| ${wait.id}\n")
        assertContains(mermaid, "    ${inbox.entryPoints.first().start} -.->|\"items\"| ${full.single().id}\n")
        assertEquals(2, Regex("-->\\|\"timeout\"\\|").findAll(mermaid).count())

        // The card says what is waited on in the words of the code, and how long at most.
        val dom = svg(inbox)
        assertEquals(listOf("AWAIT", "items.take()"), dom.group(waits[0].id)!!.texts())
        assertEquals(listOf("AWAIT · TIMEOUT 5M", "items.poll()"), dom.group(waits[1].id)!!.texts())
        assertEquals(7, dom.edges.count { it.getAttribute("data-kind") == "wakes" })
        assertEquals(listOf("AWAIT", "decision.get()"), svg(documents.getValue("Approval")).group(documents.getValue("Approval").nodes.first { it.kind == NodeKind.AWAIT && it.timeout == null }.id)!!.texts())
    }

    // ---- work that nothing waits for

    @Test
    fun `a step that is not waited for stands in the flow and says so, and a region of such steps has no join`() {
        val document = documents.getValue("Unjoined")
        val mermaid = MermaidRenderer.render(document)
        val announce = document.step("Announce")
        val region = document.nodes.single { it.kind == NodeKind.PARALLEL && it.waitsFor == WaitsFor.NOTHING }.id
        val waited = document.nodes.single { it.kind == NodeKind.PARALLEL && it.waitsFor == null }.id

        assertContains(mermaid, "$announce[\"Announce<br/>not awaited\"]")
        assertContains(mermaid, "    $announce --> $region\n")
        // The region is left at its fork, at once; its lane ends at a node that says nothing waits for it.
        assertContains(mermaid, "subgraph ${region}_region [\"parallel#58; not awaited\"]")
        assertContains(mermaid, "${region}_join((\"not awaited\"))")
        assertContains(mermaid, "    $region --> $waited\n")
        assertFalse("${region}_join -->" in mermaid)
        assertContains(mermaid, "    ${document.step("Audit")} --> ${region}_join\n")
        assertContains(mermaid, "    ${document.nodes.single { it.kind == NodeKind.BRANCH }.id} -->|\"yes\"| ${region}_join\n")
        // The region that is waited for is as it ever was: left at its join.
        assertContains(mermaid, "${waited}_join((\"join\"))")
        assertContains(mermaid, "    ${waited}_join --> ${document.step("Undo")}\n")

        val dom = svg(document)
        assertEquals(listOf("ACTIVITY", "Announce", "not awaited"), dom.group(announce)!!.texts())
        assertEquals(listOf("PARALLEL · NOT AWAITED"), dom.group(region)!!.texts())
        assertEquals(listOf("PARALLEL"), dom.group(waited)!!.texts())
        assertEquals("ACTIVITY: Announce not awaited", dom.group(announce)!!.title())
    }

    @Test
    fun `a child that is only waited for until it has started says so, and that it outlives its parent`() {
        val document = documents.getValue("Detach")
        val children = document.nodes.filter { it.kind == NodeKind.CHILD_WORKFLOW }.sortedBy { it.loc?.line }
        val mermaid = MermaidRenderer.render(document)
        assertContains(mermaid, "${children[0].id}[[\"Courier<br/>waits for start only<br/>parent close#58; abandon\"]]")
        assertContains(mermaid, "${children[1].id}[[\"Courier<br/>not awaited\"]]")
        assertContains(mermaid, "${children[2].id}[[\"Courier\"]]")

        val dom = svg(document)
        assertEquals(listOf("CHILD WORKFLOW · ABANDON", "Courier", "waits for start only"), dom.group(children[0].id)!!.texts())
        assertEquals(listOf("CHILD WORKFLOW", "Courier", "not awaited"), dom.group(children[1].id)!!.texts())
        assertEquals(listOf("CHILD WORKFLOW", "Courier"), dom.group(children[2].id)!!.texts())
    }

    // ---- cancellation

    @Test
    fun `the place where a scope is cancelled is a step with a dotted edge to each thing the scope holds`() {
        val later = documents.getValue("CancelLater")
        val cancel = later.nodes.single { it.kind == NodeKind.CANCEL_SCOPE }.id
        val mermaid = MermaidRenderer.render(later)
        assertContains(mermaid, "$cancel[\"cancel scope#58; scope\"]")
        assertContains(mermaid, "    $cancel -.->|\"cancels\"| ${later.step("Prepare")}\n")
        assertContains(mermaid, "    $cancel --> ${later.step("Report")}\n")
        // The edge is no way the flow takes: the step it leads to is still entered from the start alone.
        assertEquals(1, mermaid.lines().count { it.trim().endsWith("--> ${later.step("Prepare")}") })

        val dom = svg(later)
        assertEquals(listOf("CANCEL SCOPE", "scope"), dom.group(cancel)!!.texts())
        val edge = dom.edges.single { it.getAttribute("data-kind") == "cancels" }
        assertEquals(cancel to later.step("Prepare"), edge.getAttribute("data-from") to edge.getAttribute("data-to"))
        assertEquals("scope cancels Prepare not awaited", edge.title())

        // A scope that holds a loop cancels the loop: the edge ends at the head of the loop, or at its box.
        val race = documents.getValue("Race")
        val loop = race.nodes.single { it.kind == NodeKind.LOOP }.id
        assertContains(MermaidRenderer.render(race), "    ${race.nodes.single { it.kind == NodeKind.CANCEL_SCOPE }.id} -.->|\"cancels\"| $loop\n")
        assertEquals(loop, svg(race).edges.single { it.getAttribute("data-kind") == "cancels" }.getAttribute("data-to"))

        // From a handler the edges lead into the main flow, and say so once.
        val stoppable = documents.getValue("Stoppable")
        val stopped = svg(stoppable)
        assertEquals(3, stopped.edges.count { it.getAttribute("data-kind") == "cancels" })
        assertEquals(1, stopped.edgeLabels.count { it.getAttribute("data-kind") == "cancels" })
        assertEquals(3, Regex("-\\.->\\|\"cancels\"\\|").findAll(MermaidRenderer.render(stoppable)).count())
    }

    @Test
    fun `asking another workflow to cancel is a step that names the workflow type where it is known`() {
        val document = documents.getValue("CallOff")
        val steps = document.nodes.filter { it.kind == NodeKind.CANCEL_EXTERNAL }.sortedBy { it.loc?.line }
        val mermaid = MermaidRenderer.render(document)
        assertContains(mermaid, "${steps[0].id}[\"cancel external\"]")
        assertContains(mermaid, "${steps[1].id}[\"cancel external#58; Carrier\"]")
        val dom = svg(document)
        assertEquals(listOf("CANCEL EXTERNAL", "another workflow"), dom.group(steps[0].id)!!.texts())
        assertEquals(listOf("CANCEL EXTERNAL", "Carrier"), dom.group(steps[1].id)!!.texts())
    }

    // ---- what is chained to a promise

    @Test
    fun `what is chained to a promise follows it in its lane, and both ways into what runs either way are drawn`() {
        val document = documents.getValue("Chains")
        val mermaid = MermaidRenderer.render(document)
        assertFalse("unknown" in mermaid)
        val branch = document.nodes.single { it.kind == NodeKind.BRANCH }.id
        val composed = document.nodes.single { it.name == "Prepare" && document.edges.any { edge -> edge.to == it.id && edge.kind == EdgeKind.FLOW } }.id
        assertContains(mermaid, "    $composed --> $branch\n")
        assertContains(mermaid, "    $composed -->|\"RuntimeException\"| $branch\n")

        // Side by side in the drawing: neither line hides the other.
        val dom = svg(document)
        val pair = dom.edges.filter { it.getAttribute("data-from") == composed && it.getAttribute("data-to") == branch }
        assertEquals(setOf("flow", "exception"), pair.map { it.getAttribute("data-kind") }.toSet())
        val lines = pair.map { (it.getElementsByTagName("path").item(0) as Element).getAttribute("d") }
        assertEquals(2, lines.toSet().size, lines.toString())
        // They leave the step apart and stay apart: no stretch of the one lies on the other.
        assertEquals(2, lines.map { it.substringAfter('M').substringBefore('H') }.toSet().size, lines.toString())
        assertTrue(lines.all { Regex("M[0-9.]+ [0-9.]+H[0-9.]+").matches(it) }, lines.toString())
    }

    // ---- children of a type that two interfaces share

    @Test
    fun `a child that names the interface of its stub links the diagram of that interface, where its type has two`() {
        val ir = temp.resolve("ir")
        val out = temp.resolve("out")
        Files.createDirectories(ir)
        for ((name, document) in documents) Files.writeString(ir.resolve("$name.ir.json"), IrJson.encode(document))
        DiagramDirectory.render(ir, out, DirectoryOptions(formats = listOf(SvgFormat(), MermaidFormat)))

        val notices = documents.getValue("Notices")
        val children = notices.nodes.filter { it.kind == NodeKind.CHILD_WORKFLOW }.sortedBy { it.loc?.line }
        assertEquals(listOf("patterns.Notify", "patterns.Remind", null), children.map { it.interfaceName })
        val dom = SvgDom(Files.readString(out.resolve("Notices.svg")))
        fun link(id: String): String? {
            val parent = dom.group(id)!!.parentNode as Element
            return if (parent.localName == "a") parent.getAttributeNS("http://www.w3.org/1999/xlink", "href") else null
        }
        assertEquals("Notice--patterns.NotifyImpl.svg", link(children[0].id))
        assertEquals("Notice--patterns.RemindImpl.svg", link(children[1].id))
        assertEquals("Courier.svg", link(children[2].id))
        assertEquals("CHILD WORKFLOW: Notice", dom.group(children[0].id)!!.title())

        // The page lists each of them with the diagram of its own interface.
        assertContains(
            Files.readString(out.resolve("Notices.md")),
            "## Child workflows\n\n" +
                "- `Courier`: [Courier.md](Courier.md)\n" +
                "- `Notice` (`patterns.Notify`): [Notice--patterns.NotifyImpl.md](Notice--patterns.NotifyImpl.md)\n" +
                "- `Notice` (`patterns.Remind`): [Notice--patterns.RemindImpl.md](Notice--patterns.RemindImpl.md)\n",
        )

        // A document that names no interface is linked by its type as before: one diagram, or none of several.
        val plain = notices.copy(nodes = notices.nodes.map { it.copy(interfaceName = null) })
        val page = PageOptions(
            diagramFiles = mapOf("Notice" to listOf("a.svg", "b.svg"), "Courier" to listOf("Courier.svg")),
            interfaceFiles = mapOf("patterns.Notify" to listOf("a.svg"), "patterns.Remind" to listOf("b.svg")),
        )
        val unnamed = svg(plain, page)
        assertNull((unnamed.group(children[0].id)!!.parentNode as Element).takeIf { it.localName == "a" })
        assertEquals("CHILD WORKFLOW: Notice (2 diagrams of this workflow type, none linked)", unnamed.group(children[0].id)!!.title())
        // An interface that two classes implement still leaves two diagrams, and no link.
        val twice = svg(notices, page.copy(interfaceFiles = mapOf("patterns.Notify" to listOf("a.svg", "c.svg"))))
        assertEquals("CHILD WORKFLOW: Notice (2 diagrams of implementations of patterns.Notify, none linked)", twice.group(children[0].id)!!.title())
        assertTrue((twice.group(children[1].id)!!.parentNode as Element).localName != "a")
    }
}
