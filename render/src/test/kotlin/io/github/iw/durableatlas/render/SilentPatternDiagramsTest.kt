package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import io.github.iw.durableatlas.render.svg.ConstructStyles
import io.github.iw.durableatlas.render.svg.StyleKinds
import io.github.iw.durableatlas.render.svg.SvgDocuments
import io.github.iw.durableatlas.render.svg.SvgDom
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.children
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.texts
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.title
import io.github.iw.durableatlas.render.svg.SvgFormat
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Diagrams of the documents the analyser's tests expect for workflows that wait for a lock or
 * a semaphore, set search attributes and memo entries, get the options of their activities
 * from somewhere other than the stub, and run activities by handing the call of a stub to the
 * workflow; and of the hand-written document that holds the same constructs.
 */
class SilentPatternDiagramsTest {
    private val documents: Map<String, IrDocument> =
        SvgDocuments.all().filterKeys { it.startsWith("analyser/silent/") }.mapKeys { it.key.substringAfterLast('/') }

    private val listing: IrDocument = TestDocuments.load(TestDocuments.LISTING)

    private fun svg(document: IrDocument, page: PageOptions = PageOptions()): SvgDom = SvgDom(SvgFormat().prepare(document, page).render())

    private fun IrDocument.kind(kind: NodeKind) = nodes.filter { it.kind == kind }.sortedBy { it.loc?.line }

    @Test
    fun `every document is drawn as a diagram that parses`() {
        assertEquals(10, documents.size)
        for (document in documents.values) MermaidLint.check(MermaidRenderer.render(document))
    }

    // ---- locks and semaphores

    @Test
    fun `a wait for a lock is an await like any other, with its timeout and the handlers that end it`() {
        val guarded = documents.getValue("Guarded")
        val mermaid = MermaidRenderer.render(guarded)
        val waits = guarded.kind(NodeKind.AWAIT).filter { it.reads == listOf("lock") }
        assertEquals(listOf("lock.lock()", "lock.tryLock()"), waits.map { it.label })
        assertContains(mermaid, "${waits[0].id}{{\"await lock\"}}")
        assertContains(mermaid, "${waits[1].id}{{\"await lock<br/>timeout 30s\"}}")
        // Each of the three handlers that let go of the lock wakes both waits, its own among them.
        val handlers = guarded.entryPoints.filter { "lock" in it.writes }
        assertEquals(listOf("signal-release", "update-change", "update-tryChange"), handlers.map { it.id })
        for (handler in handlers) for (wait in waits) assertContains(mermaid, "    ${handler.start} -.->|\"lock\"| ${wait.id}\n")
        assertEquals(1, Regex("-->\\|\"timeout\"\\|").findAll(mermaid).count())

        // The card says what is waited for in the words of the code, and how long at most.
        val dom = svg(guarded)
        assertEquals(listOf("AWAIT", "lock.lock()"), dom.group(waits[0].id)!!.texts())
        assertEquals(listOf("AWAIT · TIMEOUT 30S", "lock.tryLock()"), dom.group(waits[1].id)!!.texts())
        assertEquals(6, dom.edges.count { it.getAttribute("data-kind") == "wakes" })
        // When the time is up the update returns: the way there is drawn, to a pill for the end of the update.
        val timeout = dom.edges.single { it.getAttribute("data-kind") == "timeout" }
        assertEquals(waits[1].id, timeout.getAttribute("data-from"))
        val returns = guarded.edges.single { it.kind == EdgeKind.TIMEOUT }.to
        assertEquals(returns, timeout.getAttribute("data-to"))
        assertEquals(listOf("TERMINAL", "Return"), dom.group(returns)!!.texts())
    }

    @Test
    fun `a wait for a semaphore is drawn where it stands, also inside a loop`() {
        val pool = documents.getValue("Pool")
        val waits = pool.kind(NodeKind.AWAIT)
        val dom = svg(pool)
        assertEquals(
            listOf(listOf("AWAIT", "permits.acquire()"), listOf("AWAIT · TIMEOUT 1M", "permits.tryAcquire()"), listOf("AWAIT", "permits.acquire()")),
            waits.map { dom.group(it.id)!!.texts() },
        )
        // The first of them is a step of each round.
        assertEquals(pool.kind(NodeKind.LOOP).single().id, waits[0].parent)
        assertEquals(3, dom.edges.count { it.getAttribute("data-kind") == "wakes" })
        // In the workflow method the way taken when the time is up leads to a step, and is drawn.
        val timeout = dom.edges.single { it.getAttribute("data-kind") == "timeout" }
        assertEquals(waits[1].id, timeout.getAttribute("data-from"))
        assertEquals(pool.nodes.single { it.name == "Notify" && it.loc?.line == 41 }.id, timeout.getAttribute("data-to"))
    }

    // ---- search attributes and the memo

    @Test
    fun `an upsert is a cylinder in Mermaid that names what it writes, where the document does`() {
        val mermaid = MermaidRenderer.render(listing)
        assertContains(mermaid, "    n_l01[(\"upsert search attributes#58; Stage, Seller\")]\n")
        assertContains(mermaid, "    n_l02[(\"upsert memo#58; note\")]\n")
        // One that does not say what it writes is still a step.
        assertContains(mermaid, "    n_l06[(\"upsert search attributes\")]\n")
        assertContains(mermaid, "    n_l07[(\"upsert memo\")]\n")
        // No other kind of node has that shape.
        assertEquals(listing.nodes.count { it.kind.isUpsert }, Regex("\\[\\(\"").findAll(mermaid).count())

        val labels = documents.getValue("Labels")
        val drawn = MermaidRenderer.render(labels)
        val steps = labels.nodes.filter { it.kind.isUpsert }
        assertEquals(8, steps.size)
        assertEquals(8, Regex("\\[\\(\"upsert (search attributes|memo)").findAll(drawn).count())
        assertContains(drawn, "[(\"upsert search attributes#58; Stage, Attempts\")]")
        assertContains(drawn, "[(\"upsert memo#58; note, by\")]")
    }

    @Test
    fun `an upsert is a card of its own in SVG, with its kind, what it writes and a legend entry`() {
        val dom = svg(listing)
        assertEquals(listOf("UPSERT SEARCH ATTRIBUTES", "Stage, Seller"), dom.group("l01")!!.texts())
        assertEquals(listOf("UPSERT MEMO", "note"), dom.group("l02")!!.texts())
        // Without the names of what is written, the kind is all the card says.
        assertEquals(listOf("UPSERT SEARCH ATTRIBUTES"), dom.group("l06")!!.texts())
        assertEquals(listOf("UPSERT MEMO"), dom.group("l07")!!.texts())
        assertEquals("UPSERT SEARCH ATTRIBUTES: Stage, Seller", dom.group("l01")!!.title())
        assertEquals("upsertSearchAttributes", dom.group("l01")!!.getAttribute("data-kind"))
        assertEquals("upsertMemo", dom.group("l02")!!.getAttribute("data-kind"))

        // The legend explains both, each with its own icon.
        val entries = dom.legendEntries.associate { it.getAttribute("data-kind") to it.texts().single() }
        assertEquals("Upsert search attributes", entries["upsertSearchAttributes"])
        assertEquals("Upsert memo", entries["upsertMemo"])
        val icons = dom.named("symbol").associate { it.getAttribute("id") to it.children().joinToString { child -> child.getAttribute("d") } }
        assertTrue(icons.getValue("atlas-icon-upsertSearchAttributes") != icons.getValue("atlas-icon-upsertMemo"))

        // A diagram without one explains neither.
        val plain = svg(documents.getValue("Guarded")).legendEntries.map { it.getAttribute("data-kind") }
        assertFalse("upsertSearchAttributes" in plain)
        assertFalse("upsertMemo" in plain)
    }

    @Test
    fun `the upserts have a colour no other construct has, on which white text can be read`() {
        val search = ConstructStyles.of(StyleKinds.of(NodeKind.UPSERT_SEARCH_ATTRIBUTES))
        val memo = ConstructStyles.of(StyleKinds.of(NodeKind.UPSERT_MEMO))
        assertEquals(search.colour, memo.colour)
        assertEquals("card", search.shape)
        assertEquals("card", memo.shape)
        val others = ConstructStyles.all.filter { it.kind != search.kind && it.kind != memo.kind }.map { it.colour.uppercase() }
        assertFalse(search.colour.uppercase() in others)
        // White on the colour of every construct, as the kind labels of pills and the icons of tiles are drawn.
        for (style in ConstructStyles.all) assertTrue(contrastWithWhite(style.colour) >= 4.5, "${style.kind}: ${contrastWithWhite(style.colour)}")
    }

    /** The contrast ratio of white against [colour], as WCAG 2 defines it. */
    private fun contrastWithWhite(colour: String): Double {
        val channels = colour.removePrefix("#").chunked(2).map { it.toInt(16) / 255.0 }
        val (r, g, b) = channels.map { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 1.05 / (0.2126 * r + 0.7152 * g + 0.0722 * b + 0.05)
    }

    // ---- options, direct activities and retries

    @Test
    fun `where the options of an activity come from changes nothing in how it is drawn`() {
        val registered = documents.getValue("Registered")
        val stripped = registered.copy(nodes = registered.nodes.map { it.copy(timeout = null, taskQueue = null) })
        assertEquals(MermaidRenderer.render(stripped), MermaidRenderer.render(registered))
        assertEquals(svg(stripped).nodes.map { it.texts() }, svg(registered).nodes.map { it.texts() })
    }

    @Test
    fun `an activity that is handed to the workflow is drawn like one that is called, in a lane where it is started beside the flow`() {
        val direct = documents.getValue("Direct")
        val mermaid = MermaidRenderer.render(direct)
        val lanes = direct.kind(NodeKind.PARALLEL).single()
        assertEquals(listOf("Store", "Notify"), direct.nodes.filter { it.parent == lanes.id }.sortedBy { it.loc?.line }.map { it.name })
        assertContains(mermaid, "[\"Notify (local)\"]")
        val alone = direct.nodes.single { it.waitsFor != null }
        assertContains(mermaid, "${alone.id}[\"Notify<br/>not awaited\"]")
        assertEquals(listOf("ACTIVITY", "Notify", "not awaited"), svg(direct).group(alone.id)!!.texts())
    }

    @Test
    fun `that a loop is a retry is said by its label, and the mark changes nothing in either diagram`() {
        val loop = listing.kind(NodeKind.LOOP).single()
        assertTrue(loop.retry)
        val unmarked = listing.copy(nodes = listing.nodes.map { it.copy(retry = false) })
        assertEquals(MermaidRenderer.render(unmarked), MermaidRenderer.render(listing))
        assertEquals(SvgFormat().prepare(unmarked, PageOptions()).render(), SvgFormat().prepare(listing, PageOptions()).render())
        assertContains(MermaidRenderer.render(listing), "subgraph n_l04_region [\"retry on failure, up to 3 attempts\"]")
        assertEquals(listOf("LOOP · RETRY ON FAILURE, UP TO 3 ATTEMPTS"), svg(listing).group(loop.id)!!.texts())
    }

    @Test
    fun `the hand-written document draws every edge it holds`() {
        val dom = svg(listing)
        assertEquals(listing.edges.count { it.kind == EdgeKind.WAKES }, dom.edges.count { it.getAttribute("data-kind") == "wakes" })
        assertEquals(listing.edges.count { it.kind == EdgeKind.TIMEOUT }, dom.edges.count { it.getAttribute("data-kind") == "timeout" })
    }
}
