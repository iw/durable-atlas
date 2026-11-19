package io.github.iw.durableatlas.render

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.Join
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.ir.WaitsFor
import io.github.iw.durableatlas.render.mermaid.MermaidRenderer
import io.github.iw.durableatlas.render.svg.SvgDocuments
import io.github.iw.durableatlas.render.svg.SvgDom
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.texts
import io.github.iw.durableatlas.render.svg.SvgDom.Companion.title
import io.github.iw.durableatlas.render.svg.SvgFormat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Diagrams of the documents the analyser's tests expect for workflows that run races, start
 * steps with calls that give promises, wait for a promise a time at most, wait for room in a
 * queue or on what their own code lets go of, wait for the start of a child inside a region
 * nothing waits for, and name some of what an upsert writes; and of the hand-written document
 * that holds the same constructs.
 */
class OpenDiagramsTest {
    private val documents: Map<String, IrDocument> =
        SvgDocuments.all().filterKeys { it.startsWith("analyser/open/") }.mapKeys { it.key.substringAfterLast('/') }

    private val tender: IrDocument = TestDocuments.load(TestDocuments.TENDER)

    private fun svg(document: IrDocument): SvgDom = SvgDom(SvgFormat().prepare(document, PageOptions()).render())

    private fun IrDocument.kind(kind: NodeKind) = nodes.filter { it.kind == kind }.sortedBy { it.loc?.line }

    private fun IrDocument.step(name: String): IrNode = nodes.single { it.name == name }

    @Test
    fun `every document is drawn as a diagram that parses`() {
        assertEquals(13, documents.size)
        for (document in documents.values + tender) MermaidLint.check(MermaidRenderer.render(document))
    }

    // ---- races

    @Test
    fun `a race is a parallel section that says so in its title and where the flow leaves it`() {
        val fastest = documents.getValue("Fastest")
        val mermaid = MermaidRenderer.render(fastest)
        val (race, joined) = fastest.kind(NodeKind.PARALLEL)
        assertEquals(Join.FIRST, race.join)
        assertContains(mermaid, "subgraph ${race.id}_region [\"parallel#58; first to finish\"]")
        // The lanes end where the first of them takes the flow on: the edges out of the section start there, as at a join.
        assertContains(mermaid, "${race.id}_join((\"first to finish\"))")
        assertContains(mermaid, "    ${race.id}_join --> ${fastest.step("Notify").id}\n")
        for (lane in fastest.nodes.filter { it.parent == race.id }) assertContains(mermaid, "    ${lane.id} --> ${race.id}_join\n")
        // The section whose lanes are all waited for is as it ever was.
        assertContains(mermaid, "subgraph ${joined.id}_region [\"parallel\"]")
        assertContains(mermaid, "${joined.id}_join((\"join\"))")

        val dom = svg(fastest)
        assertEquals(listOf("PARALLEL · FIRST TO FINISH"), dom.group(race.id)!!.texts())
        assertEquals(listOf("PARALLEL"), dom.group(joined.id)!!.texts())
    }

    @Test
    fun `the scope that cancels the losers of a race points at the section`() {
        val winner = documents.getValue("Winner")
        val race = winner.kind(NodeKind.PARALLEL).single()
        val cancel = winner.kind(NodeKind.CANCEL_SCOPE).single()
        val mermaid = MermaidRenderer.render(winner)
        assertContains(mermaid, "    ${race.id}_join --> ${cancel.id}\n")
        assertContains(mermaid, "    ${cancel.id} -.->|\"cancels\"| ${race.id}\n")

        val dom = svg(winner)
        val cancels = dom.edges.single { it.getAttribute("data-kind") == "cancels" }
        assertEquals(cancel.id, cancels.getAttribute("data-from"))
        assertEquals(race.id, cancels.getAttribute("data-to"))
        // In a loop that starts a lane in each round, the section stands inside the loop and says the same.
        val soonest = documents.getValue("Soonest")
        val each = soonest.kind(NodeKind.PARALLEL).single()
        assertEquals(soonest.kind(NodeKind.LOOP).single().id, each.parent)
        assertEquals(listOf("PARALLEL · FIRST TO FINISH"), svg(soonest).group(each.id)!!.texts())
    }

    @Test
    fun `a collapsed race still says what it is`() {
        val collapsed = MermaidRenderer.render(tender, RenderOptions(maxNodes = 5))
        assertContains(collapsed, "n_t02(\"parallel#58; first to finish<br/>3 nodes hidden\")")
        assertContains(collapsed, "n_t09(\"parallel#58; waits for start only<br/>2 nodes hidden\")")
    }

    // ---- promises of calls that start a step

    @Test
    fun `a timer and a condition that run beside the flow are drawn in lanes, and one nobody waits for says so`() {
        val pauses = documents.getValue("Pauses")
        val mermaid = MermaidRenderer.render(pauses)
        val timers = pauses.kind(NodeKind.TIMER)
        assertEquals(listOf("PT5M", "PT1M", "PT30S", "PT24H"), timers.map { it.duration })
        // The first two stand in sections, the third in the flow, and the last is started and left.
        assertEquals(listOf(true, true, false, false), timers.map { it.parent != null })
        assertContains(mermaid, "${timers[3].id}{{\"timer 1d<br/>not awaited\"}}")
        val waits = pauses.kind(NodeKind.AWAIT)
        assertContains(mermaid, "    ${pauses.entryPoints[1].start} -.->|\"released\"| ${waits[0].id}\n")

        val dom = svg(pauses)
        assertEquals(listOf("TIMER · 1D", "not awaited"), dom.group(timers[3].id)!!.texts())
        assertEquals(listOf("AWAIT · TIMEOUT 1H", "released"), dom.group(waits[1].id)!!.texts())
        assertEquals(2, dom.edges.count { it.getAttribute("data-kind") == "wakes" })
    }

    // ---- a promise waited for a time at most

    @Test
    fun `the timeout of a wait for a promise leads to the clause that catches it`() {
        val patience = documents.getValue("Patience")
        val waits = patience.kind(NodeKind.AWAIT)
        val timeouts = patience.edges.filter { it.kind == EdgeKind.TIMEOUT }
        assertEquals(listOf(waits[0].id, waits[2].id), timeouts.map { it.from })
        val mermaid = MermaidRenderer.render(patience)
        for (edge in timeouts) assertContains(mermaid, "    ${edge.from} -->|\"timeout\"| ${edge.to}\n")
        assertContains(mermaid, "${waits[0].id}{{\"await approval<br/>timeout 30s\"}}")

        val dom = svg(patience)
        assertEquals(listOf("AWAIT · TIMEOUT 30S", "approval.get()"), dom.group(waits[0].id)!!.texts())
        assertEquals(timeouts.map { it.from to it.to }.toSet(), dom.edges.filter { it.getAttribute("data-kind") == "timeout" }.map { it.getAttribute("data-from") to it.getAttribute("data-to") }.toSet())
    }

    // ---- more waits

    @Test
    fun `a wait for room in a queue is an await like any other`() {
        val buffer = documents.getValue("Buffer")
        val dom = svg(buffer)
        val waits = buffer.kind(NodeKind.AWAIT)
        assertEquals(listOf("AWAIT", "items.put()"), dom.group(waits[0].id)!!.texts())
        assertEquals(listOf("AWAIT · TIMEOUT 10S", "items.offer()"), dom.group(waits[1].id)!!.texts())
        // The signal that takes something out wakes all five; the one that waits itself stands in the handlers' band.
        assertEquals(5, dom.edges.count { it.getAttribute("data-kind") == "wakes" })
        assertEquals(listOf("AWAIT", "items.put()"), dom.group(waits[4].id)!!.texts())
    }

    @Test
    fun `what the workflow method wakes is drawn from its start, with the properties it is about`() {
        val permits = documents.getValue("Permits")
        val own = permits.edges.filter { it.kind == EdgeKind.WAKES && it.from == "main" }
        assertEquals(listOf("permits", "permits", "ready", "lock"), own.sortedBy { permits.nodes.single { node -> node.id == it.to }.loc?.line }.map { it.label })
        val mermaid = MermaidRenderer.render(permits)
        val start = permits.entryPoints.first().start
        for (edge in own) assertContains(mermaid, "    $start -.->|\"${edge.label}\"| ${edge.to}\n")

        val dom = svg(permits)
        val drawn = dom.edges.filter { it.getAttribute("data-kind") == "wakes" }
        assertEquals(permits.edges.count { it.kind == EdgeKind.WAKES }, drawn.size)
        assertEquals(own.map { it.to }.toSet(), drawn.filter { it.getAttribute("data-from") == start }.map { it.getAttribute("data-to") }.toSet())
        // What the edge is about is in its title, since every such edge is labelled `wakes`.
        assertTrue(drawn.filter { it.getAttribute("data-from") == start }.all { it.title().orEmpty().startsWith("Start wakes ") })
        assertTrue(drawn.any { it.title().orEmpty().endsWith("(permits)") })
    }

    // ---- start-only waits inside a region

    @Test
    fun `a region that is left once a child in it has started says so, and so does the child`() {
        val begun = documents.getValue("Begun")
        val (started, plain) = begun.kind(NodeKind.PARALLEL)
        assertEquals(listOf(WaitsFor.START, WaitsFor.NOTHING), listOf(started.waitsFor, plain.waitsFor))
        val child = begun.kind(NodeKind.CHILD_WORKFLOW).first()
        val mermaid = MermaidRenderer.render(begun)
        assertContains(mermaid, "subgraph ${started.id}_region [\"parallel#58; waits for start only\"]")
        assertContains(mermaid, "${started.id}_join((\"waits for start only\"))")
        assertContains(mermaid, "${child.id}[[\"Errand<br/>waits for start only<br/>parent close#58; abandon\"]]")
        // Like every region that is not waited for, it is left at its fork; its lane goes on to the notice and ends.
        assertContains(mermaid, "    ${started.id} --> ${begun.step("Store").id}\n")
        assertFalse("${started.id}_join -->" in mermaid)
        assertContains(mermaid, "    ${child.id} --> ${begun.nodes.single { it.name == "Notify" }.id}\n")

        val dom = svg(begun)
        assertEquals(listOf("PARALLEL · WAITS FOR START ONLY"), dom.group(started.id)!!.texts())
        assertEquals(listOf("PARALLEL · NOT AWAITED"), dom.group(plain.id)!!.texts())
        assertEquals(listOf("CHILD WORKFLOW · ABANDON", "Errand", "waits for start only"), dom.group(child.id)!!.texts())
    }

    // ---- upserts

    @Test
    fun `an upsert that names some of what it writes shows those and a sign that there is more`() {
        val tags = documents.getValue("Tags")
        val mermaid = MermaidRenderer.render(tags)
        assertContains(mermaid, "[(\"upsert search attributes#58; Stage, …\")]")
        assertContains(mermaid, "[(\"upsert search attributes#58; Stage, Owner, …\")]")
        assertContains(mermaid, "[(\"upsert memo#58; note, by, …\")]")
        // The whole list has no such sign, and neither has a node that names nothing.
        assertContains(mermaid, "[(\"upsert search attributes#58; Stage, Rank\")]")
        assertContains(mermaid, "[(\"upsert memo\")]")

        val dom = svg(tags)
        val steps = tags.nodes.filter { it.kind.isUpsert }.sortedBy { it.loc?.line }
        assertEquals(
            listOf(
                listOf("UPSERT SEARCH ATTRIBUTES", "Stage, …"),
                listOf("UPSERT SEARCH ATTRIBUTES", "Stage, Owner, …"),
                listOf("UPSERT MEMO", "note, by, …"),
                listOf("UPSERT MEMO"),
                listOf("UPSERT SEARCH ATTRIBUTES", "Stage, Rank"),
            ),
            steps.map { dom.group(it.id)!!.texts() },
        )
        assertEquals("UPSERT SEARCH ATTRIBUTES: Stage, …", dom.group(steps[0].id)!!.title())
    }

    // ---- the hand-written document

    @Test
    fun `the hand-written document shows all of it in SVG`() {
        val dom = svg(tender)
        assertEquals(listOf("UPSERT SEARCH ATTRIBUTES", "Stage, …"), dom.group("t01")!!.texts())
        assertEquals(listOf("PARALLEL · FIRST TO FINISH"), dom.group("t02")!!.texts())
        assertEquals(listOf("PARALLEL · WAITS FOR START ONLY"), dom.group("t09")!!.texts())
        assertEquals(listOf("CHILD WORKFLOW · ABANDON", "ContractWorkflow", "waits for start only"), dom.group("t11")!!.texts())
        assertEquals(listOf("UPSERT MEMO", "winner, note, …"), dom.group("t12")!!.texts())
        assertEquals(listOf("t06" to "t02"), dom.edges.filter { it.getAttribute("data-kind") == "cancels" }.map { it.getAttribute("data-from") to it.getAttribute("data-to") })
        assertEquals(listOf("t07" to "t08"), dom.edges.filter { it.getAttribute("data-kind") == "timeout" }.map { it.getAttribute("data-from") to it.getAttribute("data-to") })
        // The race is left on one edge, which starts at the section.
        assertEquals(listOf("t06"), dom.edges.filter { it.getAttribute("data-from") == "t02" && it.getAttribute("data-kind") == "flow" && it.getAttribute("data-to") !in setOf("t03", "t04", "t05") }.map { it.getAttribute("data-to") })
    }
}
