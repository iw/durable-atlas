package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.history.CloseStatus
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.mapping.Doubt
import io.github.iw.durableatlas.overlay.mapping.EdgeRef
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.mapping.Hits
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Graphs
import io.github.iw.durableatlas.overlay.testing.Histories
import io.github.iw.durableatlas.overlay.testing.Profiles
import io.github.iw.durableatlas.overlay.testing.RegionEndHistories
import io.github.iw.durableatlas.overlay.testing.SvgFile
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.dimmed
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.highlighted
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.title
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Executions that take an edge to the end of a region further out than the one its source lies
 * in: a `continue` of an outer loop, a `break` of the loop that a round ends with, and the like
 * out of a lane and out of a compensation. First each rule on a graph and a history made for it,
 * then what the analyser gives for such code, down to the files that are written.
 */
class RegionEndOverlayTest {
    @TempDir
    lateinit var dir: Path

    private fun overlay(document: IrDocument, events: Histories.() -> Unit): ExecutionTrace =
        ExecutionMapper(document).map(HistoryJson.of(Histories(document.workflowType).apply(events).build(), "test.json"))

    private fun ExecutionTrace.counts(): Map<String, Int> = nodes.mapValues { it.value.count }

    // --- loops ---

    @Test
    fun `an edge to the loop around the loop of its source begins the next round of the outer loop`() {
        // for each order { A; for each item { if (cancelled) continue the orders; B }; C }
        val document = Graphs().node("orders", NodeKind.LOOP).activity("A", parent = "orders")
            .node("items", NodeKind.LOOP, parent = "orders").node("cancelled", NodeKind.BRANCH, parent = "items").activity("B", parent = "items")
            .activity("C", parent = "orders").node("end", NodeKind.END)
            .flow("start", "orders", "end").flow("A", "items", "C")
            .edge("cancelled", "orders", label = "yes").edge("cancelled", "B", label = "no").build()

        val trace = overlay(document) {
            // Two items, then the rest of the round.
            ran("A"); ran("B"); ran("B"); ran("C")
            // One item, and the order is cancelled at the second: C is skipped.
            ran("A"); ran("B")
            // No item.
            ran("A"); ran("C")
            completed()
        }

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(listOf("end"), trace.closedAt)
        assertEquals(mapOf("start" to 1, "orders" to 1, "A" to 3, "items" to 3, "cancelled" to 4, "B" to 3, "C" to 2, "end" to 1), trace.counts())
        // Only the edge to the outer loop leads from B to A without passing C.
        assertEquals(Hits(1), trace.edges[EdgeRef("cancelled", "orders", label = "yes")])
        assertEquals(Hits(3), trace.edges[EdgeRef("cancelled", "B", label = "no")])
        // The loop of items is left for C twice, and C leads back to the head of the orders twice.
        assertEquals(Hits(2), trace.edges[EdgeRef("items", "C")])
        assertEquals(Hits(2), trace.edges[EdgeRef("C", "orders")])
        assertEquals(Hits(3), trace.edges[EdgeRef("orders", "A")])
    }

    @Test
    fun `an edge out of the loop that a round ends with begins the next round as well`() {
        // for each order { A; for each item { B; if (full) break; C } }
        val document = Graphs().node("orders", NodeKind.LOOP).activity("A", parent = "orders")
            .node("items", NodeKind.LOOP, parent = "orders").activity("B", parent = "items").node("full", NodeKind.BRANCH, parent = "items")
            .activity("C", parent = "items").node("end", NodeKind.END)
            .flow("start", "orders", "end").flow("A", "items").flow("B", "full")
            .edge("full", "orders", label = "yes").edge("full", "C", label = "no").build()
        val mapper = ExecutionMapper(document)

        val trace = overlay(document) {
            // The loop of items is left at its second item.
            ran("A"); ran("B"); ran("C"); ran("B")
            // It ends after its one item.
            ran("A"); ran("B"); ran("C")
            completed()
        }

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(mapOf("start" to 1, "orders" to 1, "A" to 2, "items" to 2, "B" to 3, "full" to 3, "C" to 2, "end" to 1), trace.counts())
        assertEquals(Hits(1), trace.edges[EdgeRef("full", "orders", label = "yes")])
        // The loop of items is still the last thing in the round, and leads back to the head of the orders when it is done.
        assertContains(mapper.impliedEdges, EdgeRef("items", "orders"))
        assertEquals(Hits(1), trace.edges[EdgeRef("items", "orders")])
        assertEquals(Hits(2), trace.edges[EdgeRef("C", "items")])
        // The branch says where both of its arms go, so it does not lead back to the head of its own loop.
        assertFalse(EdgeRef("full", "items") in mapper.impliedEdges)
    }

    @Test
    fun `an edge may leave any number of loops`() {
        // for each batch { P; for each order { Q; for each item { R; if (full) continue the batches; S }; T }; U }
        val document = Graphs().node("batches", NodeKind.LOOP).activity("P", parent = "batches")
            .node("orders", NodeKind.LOOP, parent = "batches").activity("Q", parent = "orders")
            .node("items", NodeKind.LOOP, parent = "orders").activity("R", parent = "items").node("full", NodeKind.BRANCH, parent = "items")
            .activity("S", parent = "items").activity("T", parent = "orders").activity("U", parent = "batches").node("end", NodeKind.END)
            .flow("start", "batches", "end").flow("P", "orders", "U").flow("Q", "items", "T").flow("R", "full")
            .edge("full", "batches", label = "yes").edge("full", "S", label = "no").build()

        val trace = overlay(document) {
            ran("P"); ran("Q"); ran("R"); ran("S"); ran("R")
            ran("P"); ran("Q"); ran("R"); ran("S"); ran("T"); ran("U")
            completed()
        }

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(
            mapOf(
                "start" to 1, "batches" to 1, "P" to 2, "orders" to 2, "Q" to 2, "items" to 2, "R" to 3, "full" to 3, "S" to 2,
                "T" to 1, "U" to 1, "end" to 1,
            ),
            trace.counts(),
        )
        assertEquals(Hits(1), trace.edges[EdgeRef("full", "batches", label = "yes")])
        // The loops between were each left once in the usual way, by the batch that ran to its end.
        assertEquals(Hits(1), trace.edges[EdgeRef("items", "T")])
        assertEquals(Hits(1), trace.edges[EdgeRef("orders", "U")])
    }

    @Test
    fun `a step with a plain edge to the end of a region further out is not taken to end its own round too`() {
        // for each order { P; while (more) { A; break } }
        val document = Graphs().node("orders", NodeKind.LOOP).activity("P", parent = "orders")
            .node("tries", NodeKind.LOOP, parent = "orders").activity("A", parent = "tries").node("end", NodeKind.END)
            .flow("start", "orders", "end").flow("P", "tries").edge("A", "orders").build()
        val mapper = ExecutionMapper(document)

        assertEquals(setOf(EdgeRef("orders", "P"), EdgeRef("tries", "orders"), EdgeRef("tries", "A")), mapper.impliedEdges.toSet())

        val trace = overlay(document) {
            ran("P"); ran("A")
            ran("P"); ran("A")
            completed()
        }

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(mapOf("start" to 1, "orders" to 1, "P" to 2, "tries" to 2, "A" to 2, "end" to 1), trace.counts())
        // Once to the next order, once to the end of the last.
        assertEquals(Hits(2), trace.edges[EdgeRef("A", "orders")])
        assertFalse(EdgeRef("tries", "orders") in trace.edges)
    }

    @Test
    fun `a failure that ends the round of an outer loop is told from the step going round its own`() {
        // for each order { P; try { for each item { A } } catch (e: ActivityFailure) {} }
        val document = Graphs().node("orders", NodeKind.LOOP).activity("P", parent = "orders")
            .node("items", NodeKind.LOOP, parent = "orders").activity("A", parent = "items").node("end", NodeKind.END)
            .flow("start", "orders", "end").flow("P", "items")
            .edge("A", "items").edge("A", "orders", EdgeKind.EXCEPTION, "ActivityFailure").build()
        val mapper = ExecutionMapper(document)

        val trace = overlay(document) {
            ran("P"); ran("A")
            val failing = activity("A")
            activityFailed(failing)
            ran("P"); ran("A")
            completed()
        }

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(mapOf("start" to 1, "orders" to 1, "P" to 2, "items" to 2, "A" to 3, "end" to 1), trace.counts())
        assertEquals(Hits(1), trace.edges[EdgeRef("A", "orders", EdgeKind.EXCEPTION, "ActivityFailure")])
        // The step that went well went round its own loop, and so did the last one before that loop ended.
        assertEquals(Hits(2), trace.edges[EdgeRef("A", "items")])
        assertEquals(Hits(1), trace.edges[EdgeRef("items", "orders")])
        // The way back to the head of its own loop is an edge of the document here, not an implied one.
        assertEquals(setOf(EdgeRef("orders", "P"), EdgeRef("items", "orders"), EdgeRef("items", "A")), mapper.impliedEdges.toSet())
    }

    // --- lanes and compensations ---

    @Test
    fun `an edge out of a loop to the end of its lane lets the parallel region end`() {
        // One lane tries until it is done and then leaves the lane, past X; the other runs M. Then D.
        val document = Graphs().node("both", NodeKind.PARALLEL).node("tries", NodeKind.LOOP, parent = "both")
            .activity("A", parent = "tries").node("done", NodeKind.BRANCH, parent = "tries")
            .activity("X", parent = "both").activity("M", parent = "both").activity("D").node("end", NodeKind.END)
            .flow("start", "both", "D", "end").flow("A", "done").flow("tries", "X")
            .edge("done", "both", label = "yes").edge("done", "tries", label = "no").build()

        val trace = overlay(document) {
            val first = activity("A")
            val mail = activity("M")
            activityCompleted(first)
            ran("A")
            activityCompleted(mail)
            ran("D")
            completed()
        }

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        assertEquals(mapOf("start" to 1, "both" to 1, "tries" to 1, "A" to 2, "M" to 1, "done" to 2, "D" to 1, "end" to 1), trace.counts())
        // X did not run, so the lane was left from inside its loop.
        assertEquals(Hits(1), trace.edges[EdgeRef("done", "both", label = "yes")])
        assertEquals(Hits(1), trace.edges[EdgeRef("done", "tries", label = "no")])
        // The other lane ended as lanes do.
        assertEquals(Hits(1), trace.edges[EdgeRef("M", "both")])
        assertEquals(Hits(1), trace.edges[EdgeRef("both", "D")])
    }

    @Test
    fun `an edge out of a loop to the end of a compensation is counted where the history cannot rule it out`() {
        // The compensation is one loop: for each payment { if (settled) break; Refund }.
        val document = Graphs().activity("Pay").node("undo", NodeKind.COMPENSATION).node("refunds", NodeKind.LOOP, parent = "undo")
            .node("settled", NodeKind.BRANCH, parent = "refunds").activity("Refund", parent = "refunds")
            .node("failed", NodeKind.FAIL, name = "PaymentFailed").node("end", NodeKind.END)
            .flow("start", "Pay", "end").edge("Pay", "undo", EdgeKind.EXCEPTION, "ActivityFailure").flow("undo", "failed")
            .edge("settled", "undo", label = "yes").edge("settled", "Refund", label = "no").build()

        val trace = overlay(document) {
            val pay = activity("Pay")
            activityFailed(pay)
            ran("Refund"); ran("Refund")
            failed("PaymentFailed")
        }

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(CloseStatus.FAILED, trace.status)
        assertEquals(listOf("failed"), trace.closedAt)
        assertEquals(mapOf("start" to 1, "Pay" to 1, "undo" to 1, "refunds" to 1, "settled" to 3, "Refund" to 2, "failed" to 1), trace.counts())
        // After the last refund the loop was left, or simply ended: both lead to the end of the compensation without an event.
        assertEquals(Hits(1, uncertain = 1), trace.edges[EdgeRef("settled", "undo", label = "yes")])
        val doubt = trace.ambiguous.single()
        assertEquals(Doubt.PATHS, doubt.doubt)
        assertContains(doubt.edges, EdgeRef("settled", "undo", label = "yes"))
    }

    // --- what the analyser gives ---

    @Test
    fun `continue of an outer loop is the one way the analysed graph has past the rest of the round`() {
        val document = Fixtures.regionEnd("OuterContinue")
        val names = Profiles(ExecutionMapper(document))

        val trace = ExecutionMapper(document).map(HistoryJson.of(RegionEndHistories.outerContinueSkipping(), "test.json"))

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(emptyList(), trace.ambiguous)
        // The start, nine activities and the end.
        assertEquals(11, trace.mapped)
        assertEquals(
            """
            start ×1
            loop for each id ×1
            activity Prepare ×3
            loop for each part ×3
            branch part.isEmpty() ×5
            activity Work ×4
            activity Report ×2
            end ×1
            """.trimIndent(),
            names.nodes(trace.nodes),
        )
        assertEquals(
            """
            activity Prepare -> loop for each part ×3
            activity Report -> loop for each id ×2
            activity Work -> loop for each part ×4
            branch part.isEmpty() -> activity Work 'no' ×4
            branch part.isEmpty() -> loop for each id 'yes' ×1
            loop for each id -> activity Prepare ×3
            loop for each id -> end ×1
            loop for each part -> activity Report ×2
            loop for each part -> branch part.isEmpty() ×5
            start -> loop for each id ×1
            """.trimIndent(),
            names.edges(trace.edges),
        )
    }

    @Test
    fun `where a jump and the end of a loop lead to the same place without an event, each is counted and the doubt is reported`() {
        // for (id) { prepare; for (part) { if (part == "x") break; if (part == "y") break; work } }, then
        // for (id) { while (check) { report; break } }.
        val document = Fixtures.regionEnd("LastLoopBreak")
        val names = Profiles(ExecutionMapper(document))

        val trace = overlay(document) {
            ran("Prepare"); ran("Work")
            ran("Prepare"); ran("Work"); ran("Work")
            ran("Check"); ran("Report")
            ran("Check"); ran("Report")
            completed()
        }

        assertEquals(emptyList(), trace.unmapped)
        assertEquals(11, trace.mapped)
        assertEquals(setOf(Doubt.PATHS), trace.ambiguous.map { it.doubt }.toSet())
        val edges = names.edges(trace.edges).lines()
        // Twice the first loop of parts was left with nothing to say how: by either `break`, or at its end.
        assertContains(edges, "branch part == \"x\" -> loop for each id@18 'yes' ×2 (2?)")
        assertContains(edges, "branch part == \"y\" -> loop for each id@18 'yes' ×2 (2?)")
        assertContains(edges, "loop for each part -> loop for each id@18 ×2 (2?)")
        // The `break` after the report is the only way on from there: to the next round, and to the end of the last.
        assertContains(edges, "activity Report -> loop for each id@26 ×2")
        assertContains(edges, "activity Check -> activity Report ×2")
    }

    @Test
    fun `the edge that was taken is counted in the coverage file and drawn as taken in every diagram`() {
        val ir = Files.createDirectories(dir.resolve("ir"))
        val histories = Files.createDirectories(dir.resolve("histories"))
        val out = dir.resolve("out")
        val document = Fixtures.regionEnd("OuterContinue")
        Files.writeString(ir.resolve("OuterContinue.ir.json"), IrJson.encode(document))
        Files.writeString(histories.resolve("skipped.json"), HistoryJson.encode(RegionEndHistories.outerContinueSkipping()))
        // An execution that never skips the rest of a round.
        Files.writeString(histories.resolve("plain.json"), HistoryJson.encode(RegionEndHistories.outerContinuePlain()))

        val summary = OverlayDirectory.write(ir, histories, out)

        val nodes = document.nodes.associateBy { it.id }
        val jump = document.edges.single { it.label == "yes" }
        val branch = jump.from
        val outer = jump.to
        // The edge leads past the loop the branch is in, to the loop around that one.
        assertEquals(outer, nodes.getValue(nodes.getValue(branch).parent!!).parent)

        val workflow = summary.workflows.single()
        assertEquals(listOf(0, 0), workflow.executions.map { it.events.unmapped })
        val counted = workflow.coverage.edges.single { it.from == branch && it.to == outer }
        assertEquals("yes", counted.label)
        assertEquals(1, counted.hits)
        assertEquals(0, counted.uncertain)
        assertEquals(1, counted.executions)
        assertFalse(counted.implied)
        assertEquals(emptyList(), workflow.coverage.unmapped)
        assertEquals(emptyList(), workflow.coverage.neverHit)

        val coverage = SvgFile(Files.readString(out.resolve("OuterContinue.coverage.svg")))
        assertFalse(coverage.edge(branch, outer).dimmed)
        assertEquals("part.isEmpty() → LOOP · FOR EACH ID: yes — ×1", coverage.edge(branch, outer).title())
        // The execution that took the edge shows it on its path, the other one greys it.
        val skipped = SvgFile(Files.readString(out.resolve("executions/skipped.svg")))
        assertTrue(skipped.edge(branch, outer).highlighted)
        val straight = SvgFile(Files.readString(out.resolve("executions/plain.svg")))
        assertTrue(straight.edge(branch, outer).dimmed)
        assertTrue(straight.node(branch).highlighted)
        // Nothing moved: every overlay is the plain diagram, restyled.
        val drawn = SvgFile(Files.readString(out.resolve("OuterContinue.svg")))
        listOf(coverage, skipped, straight).forEach { assertEquals(drawn.layout, it.layout) }

        val mermaid = Files.readString(out.resolve("OuterContinue.coverage.md"))
        assertContains(mermaid, "    $branch -->|\"yes · ×1\"| $outer\n")
        val path = Files.readString(out.resolve("executions/skipped.md"))
        val edgeLines = path.lines().map { it.trim() }.filter { "-->" in it }
        val position = edgeLines.indexOfFirst { it.startsWith("$branch -->|\"yes") && it.endsWith(" $outer") }
        assertTrue(position >= 0, "the edge is not in the diagram")
        val highlighted = path.lines().single { it.trim().startsWith("linkStyle") && it.trim().endsWith("stroke:#1d4ed8,stroke-width:3px") }
        assertContains(highlighted.trim().removePrefix("linkStyle ").substringBefore(' ').split(','), position.toString())
    }
}
