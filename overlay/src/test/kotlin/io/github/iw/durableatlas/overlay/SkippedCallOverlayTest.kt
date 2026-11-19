package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.NodeKind
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
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
 * Histories laid over the documents of a degraded model that left calls out as calls into
 * libraries: such a document is read like any other, and where an execution took a step that
 * has no node, the overlay says that one of those calls may hide it.
 */
class SkippedCallOverlayTest {
    @TempDir
    lateinit var dir: Path

    private fun overlay(file: String, histories: Map<String, Histories>): Path {
        val ir = Files.createDirectories(dir.resolve("ir"))
        val recorded = Files.createDirectories(dir.resolve("histories"))
        Files.writeString(ir.resolve("$file.ir.json"), IrJson.encode(Fixtures.call(file)))
        for ((name, history) in histories) Files.writeString(recorded.resolve("$name.json"), HistoryJson.encode(history.build()))
        return dir.resolve("out").also { OverlayDirectory.write(ir, recorded, it) }
    }

    @Test
    fun `an execution lies on a document that left calls out as on any other, and nothing is said of them`() {
        val document = Fixtures.call("Logged")
        assertEquals(6, document.skippedCalls.size)
        val ran = Histories("Logged").apply {
            ran("One")
            ran("Two")
            completed()
        }
        val ir = Files.createDirectories(dir.resolve("ir"))
        val recorded = Files.createDirectories(dir.resolve("histories"))
        Files.writeString(ir.resolve("Logged.ir.json"), IrJson.encode(document))
        Files.writeString(recorded.resolve("ran.json"), HistoryJson.encode(ran.build()))

        val summary = OverlayDirectory.write(ir, recorded, dir.resolve("out"))

        val workflow = summary.workflows.single()
        assertEquals(6, workflow.skippedCalls)
        assertEquals(0, workflow.coverage.events.unmapped)
        assertEquals(document.nodes.size, workflow.coverage.hitNodes)
        // Every step has its node, so no call that was left out is brought up as what may hide one.
        for (file in listOf("index.md", "Logged.coverage.md", "executions/ran.md")) {
            assertFalse("may lie behind" in Files.readString(dir.resolve("out").resolve(file)), file)
        }
        // The drawing itself still says that calls were left out, as the plain diagram does.
        assertContains(Files.readString(dir.resolve("out").resolve("Logged.coverage.svg")), "6 calls that did not resolve are not drawn")
        assertContains(Files.readString(dir.resolve("out").resolve("Logged.coverage.md")), "| Skipped calls | 6 |")
    }

    @Test
    fun `a step without a node may lie behind a call that was left out, and the overlay says so`() {
        // The execution ran an activity that the document has no node for.
        val out = overlay(
            "Logged",
            mapOf(
                "audited" to Histories("Logged").apply {
                    ran("One")
                    ran("Audit")
                    ran("Two")
                    completed()
                },
            ),
        )

        val behind = "A step without a node may lie behind one of the 6 calls that did not resolve and are not drawn."
        assertContains(Files.readString(out.resolve("index.md")), "Events without a node:\n\n- `audited.json`, event 4: `ActivityTaskScheduled` `Audit`\n\n$behind The IR file of the workflow lists them.")
        assertContains(Files.readString(out.resolve("Logged.coverage.md")), "> $behind")
        assertContains(Files.readString(out.resolve("executions/audited.md")), "> Event 4, ActivityTaskScheduled Audit, has no node in this diagram.\n\n> $behind")
        assertContains(Files.readString(out.resolve("executions/audited.svg")), "may lie behind one of the 6 calls")
    }

    @Test
    fun `a step that a kept call may be is said to lie behind that call`() {
        // The stub's interface did not resolve, so the activity it runs has an unknown node and no name to be matched by.
        val document = Fixtures.call("Served")
        val kept = document.nodes.single { it.kind == NodeKind.UNKNOWN }
        val out = overlay(
            "Served",
            mapOf(
                "charged" to Histories("Served").apply {
                    ran("Charge")
                    completed()
                },
            ),
        )

        val index = Files.readString(out.resolve("index.md"))
        assertContains(index, "- `charged.json`, event 2: `ActivityTaskScheduled` `Charge`; may lie behind `${kept.id}`")
        // The document left two calls out as well: either may hide a step, and both are said.
        assertContains(index, "A step without a node may lie behind one of the 2 calls that did not resolve and are not drawn.")
    }
}
