package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.EdgeKind
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.testing.SilentHistories
import io.github.iw.durableatlas.overlay.testing.SvgFile
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.badge
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.dimmed
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.highlighted
import io.github.iw.durableatlas.overlay.testing.SvgFile.Companion.title
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The end of a handler that the SVG diagram draws, because a wait leads there when its time is
 * up: what executions took of it shows on the pill and on the ways into it, in the coverage
 * and on the path of each execution.
 */
class HandlerEndOverlayTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the pill for the end of a handler is counted, and the way an execution took into it is on its path`() {
        val ir = Files.createDirectories(dir.resolve("ir"))
        val histories = Files.createDirectories(dir.resolve("histories"))
        val out = dir.resolve("out")
        val document = Fixtures.silent("Guarded")
        Files.writeString(ir.resolve("Guarded.ir.json"), IrJson.encode(document))
        // One execution in which the update got the lock in time, and one in which its time was up.
        for (way in listOf("freed", "timed-out")) {
            Files.writeString(histories.resolve("$way.json"), HistoryJson.encode(SilentHistories.all.getValue("Guarded").getValue(way)))
        }

        val summary = OverlayDirectory.write(ir, histories, out)

        val timeout = document.edges.single { it.kind == EdgeKind.TIMEOUT }
        val wait = timeout.from
        val end = timeout.to
        val store = document.nodes.single { it.name == "Store" }.id
        val workflow = summary.workflows.single()
        assertEquals(listOf(0, 0), workflow.executions.map { it.events.unmapped })
        assertEquals(1, workflow.coverage.edges.single { it.from == wait && it.to == end }.hits)
        assertEquals(1, workflow.coverage.edges.single { it.from == store && it.to == end }.hits)
        assertEquals(2, workflow.coverage.nodes.single { it.id == end }.hits)

        // The update returned in both executions, once by each way.
        val coverage = SvgFile(Files.readString(out.resolve("Guarded.coverage.svg")))
        assertEquals("×2", coverage.node(end).badge())
        assertEquals("TERMINAL: Return — ×2", coverage.node(end).title())
        assertFalse(coverage.edge(wait, end, "timeout").dimmed)
        assertEquals("lock.tryLock() → Return: timeout — ×1", coverage.edge(wait, end, "timeout").title())
        assertEquals("Store → Return — ×1", coverage.edge(store, end).title())

        // Each execution shows the way it took, and greys the other.
        val late = SvgFile(Files.readString(out.resolve("executions/timed-out.svg")))
        assertTrue(late.edge(wait, end, "timeout").highlighted)
        assertTrue(late.node(end).highlighted)
        assertTrue(late.node(store).dimmed)
        assertTrue(late.edge(store, end).dimmed)
        val inTime = SvgFile(Files.readString(out.resolve("executions/freed.svg")))
        assertTrue(inTime.edge(wait, end, "timeout").dimmed)
        assertTrue(inTime.edge(store, end).highlighted)
        assertTrue(inTime.node(end).highlighted)

        // Nothing moved: every overlay is the plain diagram, restyled.
        val drawn = SvgFile(Files.readString(out.resolve("Guarded.svg")))
        listOf(coverage, late, inTime).forEach { assertEquals(drawn.layout, it.layout) }
    }
}
