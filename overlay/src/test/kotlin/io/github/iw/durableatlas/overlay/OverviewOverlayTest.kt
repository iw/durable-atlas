package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.Detail
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.diagram.OverlaySummary
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.Histories
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Histories laid over an overview and over the full document of the same workflow. An overview
 * has no node for a call behind which a step is only possible, and every step it has is the
 * node of the full document: an execution is counted on the same nodes in both. Where it took a
 * step that has no node, the overlay of the overview says that a call which was left out may
 * hide it.
 */
class OverviewOverlayTest {
    @TempDir
    lateinit var dir: Path

    private val full: IrDocument = Fixtures.shop("Helped")
    private val overview: IrDocument = Fixtures.shopOverview("Helped")

    private fun overlay(name: String, document: IrDocument, history: Histories): Pair<Path, OverlaySummary> {
        val ir = Files.createDirectories(dir.resolve(name).resolve("ir"))
        val recorded = Files.createDirectories(dir.resolve(name).resolve("histories"))
        Files.writeString(ir.resolve("Helped.ir.json"), IrJson.encode(document))
        Files.writeString(recorded.resolve("ran.json"), HistoryJson.encode(history.build()))
        val out = dir.resolve(name).resolve("out")
        return out to OverlayDirectory.write(ir, recorded, out)
    }

    private fun ran(vararg activities: String): Histories =
        Histories("Helped").apply {
            activities.forEach { ran(it) }
            completed()
        }

    @Test
    fun `an execution is counted on the same steps in an overview as in the full document`() {
        assertEquals(Detail.OVERVIEW, overview.detail)
        assertEquals(3, overview.leftOutCalls.size)
        assertEquals(3, full.nodes.count { it.kind == NodeKind.UNKNOWN })
        val (out, brief) = overlay("overview", overview, ran("Reserve", "Charge"))
        val (_, whole) = overlay("full", full, ran("Reserve", "Charge"))

        val counted = brief.workflows.single()
        assertEquals(3, counted.leftOutCalls)
        assertEquals(0, counted.coverage.events.unmapped)
        assertEquals(whole.workflows.single().coverage.events.mapped, counted.coverage.events.mapped)
        // Every node of the overview was reached, and each is a node of the full document.
        assertEquals(overview.nodes.size, counted.coverage.hitNodes)
        val steps = overview.nodes.map { it.id }.toSet()
        val hits = whole.workflows.single().coverage.nodes.filter { it.id in steps }.associate { it.id to it.hits }
        assertEquals(hits, counted.coverage.nodes.associate { it.id to it.hits })
        // Every step has its node, so no call that was left out is brought up as what may hide one.
        for (file in listOf("index.md", "Helped.coverage.md", "executions/ran.md")) {
            assertFalse("may lie behind" in Files.readString(out.resolve(file)), file)
        }
        // The drawing itself still says that calls were left out, as the plain diagram does.
        assertContains(Files.readString(out.resolve("Helped.coverage.svg")), "3 calls that could not be read are not drawn")
        assertContains(Files.readString(out.resolve("Helped.coverage.md")), "| Calls left out | 3 |")
    }

    @Test
    fun `a step without a node may lie behind a call that an overview left out, and the overlay says so`() {
        // The execution ran an activity between the two that have nodes: behind the helper, for all the analysis knows.
        val (out, brief) = overlay("overview", overview, ran("Reserve", "Audit", "Charge"))

        assertEquals(1, brief.workflows.single().coverage.events.unmapped)
        val behind = "A step without a node may lie behind one of the 3 calls that this overview leaves out."
        assertContains(Files.readString(out.resolve("index.md")), "Events without a node:\n\n- `ran.json`, event 4: `ActivityTaskScheduled` `Audit`\n\n$behind The IR file of the workflow lists them.")
        assertContains(Files.readString(out.resolve("Helped.coverage.md")), "> $behind")
        assertContains(Files.readString(out.resolve("executions/ran.md")), "> Event 4, ActivityTaskScheduled Audit, has no node in this diagram.\n\n> $behind")
        assertContains(Files.readString(out.resolve("executions/ran.svg")), "may lie behind one of the 3 calls")

        // Over the full document the same event is said to lie behind the calls that have their nodes there.
        val (whole, _) = overlay("full", full, ran("Reserve", "Audit", "Charge"))
        val index = Files.readString(whole.resolve("index.md"))
        assertContains(index, "- `ran.json`, event 4: `ActivityTaskScheduled` `Audit`; may lie behind ")
        assertFalse("this overview" in index)
    }
}
