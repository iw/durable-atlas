package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EntryPoint
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.diagram.OverlayOptions
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.mapping.Doubt
import io.github.iw.durableatlas.overlay.mapping.ExecutionMapper
import io.github.iw.durableatlas.overlay.mapping.ExecutionTrace
import io.github.iw.durableatlas.overlay.testing.ConstructHistories
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Histories
import io.github.iw.durableatlas.overlay.testing.Profiles
import io.github.iw.durableatlas.render.DiagramFormats
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executions of workflows that take steps while their object is made, register handlers as
 * they run, test what an await waited for, compensate side by side and retry what fails: what
 * the analyser gives for such code, with a history for every way through it.
 */
class ConstructOverlayTest {
    @TempDir
    lateinit var dir: Path

    private class Overlaid(val document: IrDocument, val trace: ExecutionTrace, private val names: Profiles) {
        val edges: List<String> get() = names.edges(trace.edges).lines()
        val nodes: List<String> get() = names.nodes(trace.nodes).lines()
    }

    private fun overlay(workflowType: String, way: String): Overlaid {
        val document = Fixtures.construct(workflowType)
        val mapper = ExecutionMapper(document)
        val history = ConstructHistories.all.getValue(workflowType).getValue(way)
        return Overlaid(document, mapper.map(HistoryJson.of(history, "$workflowType.$way.json")), Profiles(mapper))
    }

    @Test
    fun `every way through is laid over its graph without an unmapped event or a doubt`() {
        assertEquals(Fixtures.constructIr.toFile().list()!!.map { it.removeSuffix(".ir.json") }.toSet(), ConstructHistories.all.keys)
        val doubts = LinkedHashMap<String, List<Doubt>>()
        for ((workflowType, histories) in ConstructHistories.all) {
            for (way in histories.keys) {
                val trace = overlay(workflowType, way).trace
                assertEquals(emptyList(), trace.unmapped, "$workflowType, $way")
                assertTrue(trace.closedAt.isNotEmpty(), "$workflowType, $way ends at no node")
                if (trace.ambiguous.isNotEmpty()) doubts["$workflowType.$way"] = trace.ambiguous.map { it.doubt }
            }
        }
        assertEquals(emptyMap(), doubts)
    }

    @Test
    fun `the steps of the making come first, and a return from the constructor leads to the workflow method`() {
        assertEquals(
            listOf(
                "activity Check -> branch !checked ×1",
                "activity Prepare -> activity Check ×1",
                "activity Report -> activity Work ×1",
                "activity Work -> end ×1",
                "branch !checked -> activity Report 'no' ×1",
                "start -> activity Prepare ×1",
            ),
            overlay("InitSteps", "reported").edges,
        )
        assertTrue("branch !checked -> activity Work 'yes' ×1" in overlay("InitSteps", "returned").edges)

        // A lazy value that nothing asked for is passed by.
        assertEquals(
            listOf(
                "activity Prepare -> unknown lazy ×1",
                "activity Work -> end ×1",
                "start -> activity Prepare ×1",
                "timer -> activity Work ×1",
                "unknown lazy -> timer ×1",
            ),
            overlay("BaseSteps", "not-asked").edges,
        )
    }

    @Test
    fun `a signal or an update without a handler of its name goes to the handler for every name`() {
        val noted = overlay("dynamic-Fallback", "noted")
        assertEquals(
            listOf(
                "activity Handle -> end ×1",
                "await note != null -> activity Handle ×1",
                "dynamic-signal -> await note != null wakes ×1",
                "start -> await note != null ×1",
                "start of dynamic-signal -> end of dynamic-signal ×1",
            ),
            noted.edges,
        )
        assertEquals(setOf("main", "dynamic-signal"), noted.trace.entryPoints.keys)
        val signal = noted.trace.matches.single { it.eventType == "WorkflowExecutionSignaled" }
        // The match keeps the name the signal came with.
        assertEquals("anything", signal.name)
        assertEquals(listOf("dynamic-signal"), signal.nodes)

        // An update that has a handler of its name goes there, with its validator; any other to the one for every name.
        val resumed = overlay("Listeners", "paused-and-resumed")
        assertEquals(setOf("main", "signal-pause", "validator-resume", "update-resume"), resumed.trace.entryPoints.keys)
        assertTrue("start of update-resume -> activity Prepare ×1" in resumed.edges)
        val other = overlay("Listeners", "another-update")
        assertEquals(setOf("main", "dynamic-update"), other.trace.entryPoints.keys)
        assertTrue("start of dynamic-update -> activity Report ×1" in other.edges)

        // Without such a handler the event has no node, as before.
        val document = Fixtures.construct("QueueLoop")
        assertTrue(document.entryPoints.none { it.name == EntryPoint.ANY_NAME })
        val stray = Histories("QueueLoop").apply { signal("unheard"); signal("finish"); completed() }.build()
        val trace = ExecutionMapper(document).map(HistoryJson.of(stray, "QueueLoop.stray.json"))
        assertEquals(listOf("unheard"), trace.unmapped.map { it.name })
    }

    @Test
    fun `the edges out of an await are taken as the test after it sends the flow`() {
        assertEquals(
            listOf(
                "activity Work -> loop while true ×2",
                "await not queue.isEmpty() or done -> activity Work 'not queue.isEmpty()' ×2",
                "await not queue.isEmpty() or done -> end 'queue.isEmpty() and done' ×1",
                "loop while true -> await not queue.isEmpty() or done ×3",
                "signal-add -> await not queue.isEmpty() or done wakes ×2",
                "signal-finish -> await not queue.isEmpty() or done wakes ×1",
                "start -> loop while true ×1",
                "start of signal-add -> end of signal-add ×2",
                "start of signal-finish -> end of signal-finish ×1",
            ),
            overlay("QueueLoop", "two-then-finish").edges,
        )
        assertTrue("await approved or stopped -> activity CleanUp 'stopped' ×1" in overlay("FirstWins", "stopped").edges)
        assertTrue("await approved or stopped -> activity Work 'approved' ×1" in overlay("FirstWins", "approved").edges)
        assertTrue("await approved or stopped -> end timeout ×1" in overlay("FirstWins", "timed-out").edges)
        assertTrue("await approved or stopped -> activity CleanUp 'stopped and not approved' ×1" in overlay("WhenReasons", "stopped").edges)
        assertTrue("await approved or stopped -> activity Work 'approved' ×1" in overlay("WhenReasons", "approved").edges)
    }

    @Test
    fun `a saga that compensates side by side runs the lanes of what was registered`() {
        val all = overlay("SideBySide", "all-undone")
        assertEquals(
            listOf(
                "activity Check -> compensation exception 'ActivityFailure' ×1",
                "activity CleanUp -> parallel ×1",
                "activity Prepare -> activity Work ×1",
                "activity Release -> parallel ×1",
                "activity Report -> activity Undo ×1",
                "activity Undo -> parallel ×1",
                "activity Work -> activity Check ×1",
                "compensation -> fail ActivityFailure ×1",
                "compensation -> parallel ×1",
                "parallel -> activity CleanUp ×1",
                "parallel -> activity Release ×1",
                "parallel -> activity Report ×1",
                "start -> activity Prepare ×1",
            ),
            all.edges,
        )
        assertEquals(listOf(all.document.nodes.single { it.kind == NodeKind.FAIL }.id), all.trace.closedAt)

        // Only the first compensation was registered when the first step failed: the other lanes did not run.
        val first = overlay("SideBySide", "first-undone")
        assertEquals(
            listOf(
                "activity CleanUp -> parallel ×1",
                "activity Prepare -> compensation exception 'ActivityFailure' ×1",
                "compensation -> fail ActivityFailure ×1",
                "compensation -> parallel ×1",
                "parallel -> activity CleanUp ×1",
                "start -> activity Prepare ×1",
            ),
            first.edges,
        )
        val ran = first.document.nodes.filter { it.kind == NodeKind.ACTIVITY && it.id in first.trace.nodes }
        assertEquals(setOf("Prepare", "CleanUp"), ran.mapNotNull { it.name }.toSet())
    }

    @Test
    fun `what is retried goes round once for each attempt`() {
        val atOnce = overlay("Retries", "at-once")
        assertTrue("loop retry on failure, up to 3 attempts, for up to 10m -> activity Prepare ×1" in atOnce.edges)
        assertTrue("activity Work -> loop retry on failure, up to 3 attempts, for up to 10m ×1" in atOnce.edges)

        assertEquals(
            listOf(
                "activity Check -> loop retry on failure ×2",
                "activity Prepare -> activity Work ×2",
                "activity Work -> loop retry on failure, up to 3 attempts, for up to 10m ×2",
                "branch fine -> end 'no' ×1",
                "loop retry on failure -> activity Check ×2",
                "loop retry on failure -> branch fine ×1",
                "loop retry on failure, up to 3 attempts, for up to 10m -> activity Prepare ×2",
                "loop retry on failure, up to 3 attempts, for up to 10m -> loop retry on failure ×1",
                "start -> loop retry on failure, up to 3 attempts, for up to 10m ×1",
            ),
            overlay("Retries", "retried").edges,
        )
    }

    @Test
    fun `the coverage of these workflows is written and drawn`() {
        val histories = Files.createDirectories(dir.resolve("histories"))
        for ((workflowType, ways) in ConstructHistories.all) {
            for ((way, history) in ways) Files.writeString(histories.resolve("$workflowType.$way.json"), HistoryJson.encode(history))
        }
        val out = dir.resolve("out")
        val summary = OverlayDirectory.write(Fixtures.constructIr, histories, out, OverlayOptions(formats = DiagramFormats.all))

        assertEquals(ConstructHistories.all.keys, summary.workflows.map { it.workflowType }.toSet())
        assertEquals(emptyList(), summary.unmatched)
        for (workflow in summary.workflows) {
            assertEquals(0, workflow.coverage.events.unmapped, workflow.workflowType)
            for (file in workflow.files) assertTrue(Files.size(out.resolve(file.file)) > 0, file.file)
        }
    }

    @Test
    fun `a history of a workflow type without a document is laid over the one document for every such type`() {
        val histories = Files.createDirectories(dir.resolve("histories"))
        Files.writeString(histories.resolve("greeting.json"), HistoryJson.encode(ConstructHistories.all.getValue("dynamic-Fallback").getValue("noted")))
        Files.writeString(histories.resolve("retries.json"), HistoryJson.encode(ConstructHistories.all.getValue("Retries").getValue("at-once")))
        val out = dir.resolve("out")
        val summary = OverlayDirectory.write(Fixtures.constructIr, histories, out, OverlayOptions(formats = listOf(DiagramFormats.MERMAID)))

        assertEquals(emptyList(), summary.unmatched)
        assertEquals(listOf("Retries", "dynamic-Fallback"), summary.workflows.map { it.workflowType })
        // A workflow type with a document of its own is laid over that one.
        assertEquals(listOf<String?>(null), summary.workflows.first().executions.map { it.workflowType })
        val fallback = summary.workflows.last()
        assertEquals(listOf<String?>("Greeting"), fallback.executions.map { it.workflowType })
        assertEquals(0, fallback.coverage.events.unmapped)

        // The index says which workflow type each such history is of; the other workflows have no such column.
        val index = Files.readString(out.resolve(summary.index))
        assertContains(index, "| History | Workflow type | Workflow id | Status |")
        assertContains(index, "| `greeting.json` | `Greeting` | `w-1` |")
        assertContains(index, "| `retries.json` | `w-1` |")
        // The diagram of the execution is called after the workflow type that ran.
        assertContains(Files.readString(out.resolve("executions/greeting.md")), "# Greeting: w-1")
    }

    @Test
    fun `without one document for every such type the history is left out`() {
        val histories = Files.createDirectories(dir.resolve("histories"))
        Files.writeString(histories.resolve("greeting.json"), HistoryJson.encode(ConstructHistories.all.getValue("dynamic-Fallback").getValue("noted")))

        // No such document.
        val none = OverlayDirectory.write(Fixtures.regionExitIr, histories, dir.resolve("none"), OverlayOptions(formats = listOf(DiagramFormats.MERMAID)))
        assertEquals(listOf("no workflow document for this workflow type"), none.unmatched.map { it.reason })

        // Two of them: nothing says which class ran.
        val ir = Files.createDirectories(dir.resolve("ir"))
        val document = Fixtures.construct("dynamic-Fallback")
        Files.writeString(ir.resolve("dynamic-Fallback.ir.json"), IrJson.encode(document))
        Files.writeString(ir.resolve("dynamic-Other.ir.json"), IrJson.encode(document.copy(workflowType = "dynamic-Other", implementation = "constructs.Other")))
        val two = OverlayDirectory.write(ir, histories, dir.resolve("two"), OverlayOptions(formats = listOf(DiagramFormats.MERMAID)))
        assertEquals(emptyList(), two.workflows)
        assertEquals(
            listOf("no workflow document for this workflow type, and several for every type without one: dynamic-Fallback.ir.json, dynamic-Other.ir.json"),
            two.unmatched.map { it.reason },
        )
    }
}
