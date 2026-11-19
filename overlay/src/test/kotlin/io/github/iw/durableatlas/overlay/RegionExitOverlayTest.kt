package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrNode
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.diagram.OverlayOptions
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.mapping.Doubt
import io.github.iw.durableatlas.overlay.mapping.EdgeRef
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.mapping.FlowGraph
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Profiles
import io.github.iw.durableatlas.overlay.testing.RegionExitHistories
import io.github.iw.durableatlas.render.DiagramFormats
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Executions of workflows whose last step in a round leaves the loop, goes round, or can do
 * either: what the analyser gives for such code, with a history for every way through it. A
 * step that a `break`, a `return` or a `throw` follows has one way on, and the graph has no
 * other for it; a step that can fail into something outside the loop still goes round.
 */
class RegionExitOverlayTest {
    @TempDir
    lateinit var dir: Path

    private class Overlaid(val document: IrDocument, val trace: ExecutionTrace, private val names: Profiles) {
        val edges: List<String> get() = names.edges(trace.edges).lines()
    }

    private fun overlay(workflowType: String, way: String): Overlaid {
        val document = Fixtures.regionExit(workflowType)
        val mapper = ExecutionMapper(document)
        val history = RegionExitHistories.all.getValue(workflowType).getValue(way)
        return Overlaid(document, mapper.map(HistoryJson.of(history, "$workflowType.$way.json")), Profiles(mapper))
    }

    private fun IrDocument.step(name: String): IrNode = nodes.single { it.name == name }

    @Test
    fun `every way through is laid over its graph without an unmapped event`() {
        assertEquals(Fixtures.regionExitIr.toFile().list()!!.map { it.removeSuffix(".ir.json") }.toSet(), RegionExitHistories.all.keys)
        val doubts = LinkedHashMap<String, List<Doubt>>()
        for ((workflowType, histories) in RegionExitHistories.all) {
            for (way in histories.keys) {
                val trace = overlay(workflowType, way).trace
                assertEquals(emptyList(), trace.unmapped, "$workflowType, $way")
                assertTrue(trace.closedAt.isNotEmpty(), "$workflowType, $way ends at no node")
                if (trace.ambiguous.isNotEmpty()) doubts["$workflowType.$way"] = trace.ambiguous.map { it.doubt }
            }
        }
        // What stays open is what no history can show: which arm of a branch ended a round when both lead to the
        // same place without an event, and whether a loop was left by a `break` or had run out.
        assertEquals(
            mapOf(
                "BranchBreak.two-rounds" to listOf(Doubt.PATHS),
                "BranchContinue.three-rounds" to listOf(Doubt.PATHS, Doubt.PATHS, Doubt.PATHS),
                "WhenArms.worked-then-last" to listOf(Doubt.PATHS),
                "WhenArms.skipped-then-quit" to listOf(Doubt.PATHS),
            ),
            doubts,
        )
    }

    @Test
    fun `a step that a break follows is left on its one edge, and the round before it ended at the branch`() {
        val found = overlay("NotifyBreak", "found")
        assertEquals(
            listOf(
                "activity CleanUp -> end ×1",
                "activity Prepare -> branch id == \"last\" ×2",
                "activity Report -> activity CleanUp ×1",
                "branch id == \"last\" -> activity Report 'yes' ×1",
                "branch id == \"last\" -> loop for each id 'no' ×1",
                "loop for each id -> activity Prepare ×2",
                "start -> loop for each id ×1",
            ),
            found.edges,
        )
        assertEquals(emptyList(), found.trace.ambiguous)

        // Without the break the loop runs out, and is left from its head.
        val notFound = overlay("NotifyBreak", "not-found")
        assertTrue("branch id == \"last\" -> loop for each id 'no' ×2" in notFound.edges)
        assertTrue("loop for each id -> activity CleanUp ×1" in notFound.edges)
        assertEquals(emptyList(), notFound.trace.ambiguous)
    }

    @Test
    fun `a step that a return or a throw follows ends the workflow from inside the loop`() {
        val returned = overlay("StepReturn", "one-id")
        assertEquals(listOf("activity Work -> end ×1", "loop for each id -> activity Work ×1", "start -> loop for each id ×1"), returned.edges)
        assertEquals(emptyList(), returned.trace.ambiguous)

        val thrown = overlay("NotifyThrow", "bad")
        assertTrue("activity Report -> fail IllegalStateException ×1" in thrown.edges)
        assertEquals(listOf(thrown.document.nodes.single { it.kind == NodeKind.FAIL }.id), thrown.trace.closedAt)
        assertEquals(emptyList(), thrown.trace.ambiguous)
    }

    @Test
    fun `a step whose failure is caught and passed over goes round on its own edge when it does not fail`() {
        val fine = overlay("CaughtFailure", "fine")
        assertEquals(
            listOf(
                "activity Prepare -> loop for each id@27 ×2",
                "activity Work -> loop for each id@20 ×2",
                "loop for each id@20 -> activity Work ×2",
                "loop for each id@20 -> loop for each id@27 ×1",
                "loop for each id@27 -> activity Prepare ×2",
                "loop for each id@27 -> end ×1",
                "start -> loop for each id@20 ×1",
            ),
            fine.edges,
        )
        assertEquals(emptyList(), fine.trace.ambiguous)

        // One round of each loop fails: the first goes on with the next round, the second is left for the clean-up.
        val failing = overlay("CaughtFailure", "failing")
        assertEquals(
            listOf(
                "activity CleanUp -> end ×1",
                "activity Prepare -> activity CleanUp exception 'ActivityFailure' ×1",
                "activity Prepare -> loop for each id@27 ×1",
                "activity Work -> loop for each id@20 exception 'ActivityFailure' ×1",
                "activity Work -> loop for each id@20 ×1",
                "loop for each id@20 -> activity Work ×2",
                "loop for each id@20 -> loop for each id@27 ×1",
                "loop for each id@27 -> activity Prepare ×2",
                "start -> loop for each id@20 ×1",
            ),
            failing.edges,
        )
        assertEquals(emptyList(), failing.trace.ambiguous)
    }

    @Test
    fun `an await goes round when it is answered and leaves the loop when it times out`() {
        val answered = overlay("AwaitTimeout", "answered")
        assertTrue("await answered -> loop for each id 'answered' ×3" in answered.edges)
        assertTrue("signal-answer -> await answered wakes ×1" in answered.edges)
        assertTrue("loop for each id -> activity Report ×1" in answered.edges)

        val timedOut = overlay("AwaitTimeout", "timed-out")
        assertTrue("await answered -> activity Report timeout ×1" in timedOut.edges)
        assertFalse(timedOut.edges.any { it.startsWith("await answered -> loop") })
    }

    @Test
    fun `a break beside a lane leaves the parallel region without joining it`() {
        val joined = overlay("LaneBreak", "joined")
        assertTrue("activity Check -> parallel ×2" in joined.edges)
        assertTrue("branch id == \"last\" -> parallel 'no' ×2" in joined.edges)
        assertTrue("parallel -> loop for each id ×2" in joined.edges)

        val broke = overlay("LaneBreak", "broke")
        assertEquals(
            listOf(
                "activity CleanUp -> end ×1",
                "activity Prepare -> branch id == \"last\" ×1",
                "activity Report -> activity CleanUp ×1",
                "branch id == \"last\" -> activity Report 'yes' ×1",
                "loop for each id -> parallel ×1",
                "parallel -> activity Check ×1",
                "parallel -> activity Prepare ×1",
                "start -> loop for each id ×1",
            ),
            broke.edges,
        )
        assertEquals(emptyList(), broke.trace.ambiguous)
    }

    @Test
    fun `the coverage lists no edge that no execution could take, and the diagram draws none`() {
        val histories = Files.createDirectories(dir.resolve("histories"))
        for ((workflowType, ways) in RegionExitHistories.all) {
            for ((way, history) in ways) Files.writeString(histories.resolve("$workflowType.$way.json"), HistoryJson.encode(history))
        }
        val out = dir.resolve("out")
        val summary = OverlayDirectory.write(Fixtures.regionExitIr, histories, out, OverlayOptions(formats = listOf(DiagramFormats.MERMAID)))

        assertEquals(RegionExitHistories.all.keys.sorted(), summary.workflows.map { it.workflowType })
        assertEquals(emptyList(), summary.unmatched)
        for (workflow in summary.workflows) {
            assertEquals(RegionExitHistories.all.getValue(workflow.workflowType).size, workflow.executions.size, workflow.workflowType)
            assertEquals(emptyList(), workflow.coverage.unmapped, workflow.workflowType)
            assertTrue(workflow.executions.all { it.events.unmapped == 0 }, workflow.workflowType)
        }

        // The step with a `break`, a `return` or a `throw` after it, and the region it would be taken to the end of.
        val leaving = listOf("NotifyBreak" to "Report", "StepReturn" to "Work", "NotifyThrow" to "Report", "LaneBreak" to "Report")
        for ((workflowType, name) in leaving) {
            val document = Fixtures.regionExit(workflowType)
            val step = document.step(name)
            val region = step.parent!!
            assertFalse(EdgeRef(step.id, region) in FlowGraph(document).implied, workflowType)

            val workflow = summary.workflows.single { it.workflowType == workflowType }
            val listed = workflow.coverage.edges.filter { it.from == step.id }
            assertEquals(1, listed.size, workflowType)
            assertEquals(EdgeKind.FLOW, listed.single().kind, workflowType)
            assertFalse(listed.single().implied, workflowType)
            assertTrue(document.nodes.single { it.id == listed.single().to }.parent == null, workflowType)
            // With every way through run, nothing is left that was never taken.
            assertEquals(emptyList(), workflow.coverage.neverHit, workflowType)
            assertEquals(emptyList(), workflow.coverage.edges.filter { it.recorded && it.hits == 0 }, workflowType)

            val page = Files.readString(out.resolve(workflow.files.single().file))
            val drawn = page.lines().map { it.trim() }.filter { it.startsWith("${step.id} -->") }
            assertEquals(1, drawn.size, workflowType)
            assertFalse(drawn.single().endsWith(" $region") || drawn.single().endsWith(" ${region}_join"), workflowType)
        }

        // Where a step can go round, the edge is there and counted: implied beside a failure that is handled
        // outside the loop, written beside one that is passed over inside it.
        val caught = Fixtures.regionExit("CaughtFailure")
        val coverage = summary.workflows.single { it.workflowType == "CaughtFailure" }.coverage
        val prepare = caught.step("Prepare")
        val work = caught.step("Work")
        val rounds = coverage.edges.filter { it.kind == EdgeKind.FLOW && (it.from == prepare.id || it.from == work.id) }
        assertEquals(listOf(work.id to false, prepare.id to true), rounds.map { it.from to it.implied })
        assertEquals(listOf(3, 3), rounds.map { it.hits })
        val page = Files.readString(out.resolve("CaughtFailure.coverage.md"))
        assertTrue("    ${work.id} -->|\"repeat · ×3\"| ${work.parent}\n" in page)
        assertTrue("    ${work.id} -->|\"ActivityFailure · ×1\"| ${work.parent}\n" in page)
        assertTrue("    ${prepare.id} -->|\"repeat · ×3\"| ${prepare.parent}\n" in page)
    }
}
