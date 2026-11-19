package io.github.iw.durableatlas.overlay

import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import io.github.iw.durableatlas.overlay.coverage.CoverageJson
import io.github.iw.durableatlas.overlay.diagram.OverlayDirectory
import io.github.iw.durableatlas.overlay.diagram.OverlayOptions
import io.github.iw.durableatlas.overlay.diagram.OverlaySummary
import io.github.iw.durableatlas.overlay.history.HistoryJson
import io.github.iw.durableatlas.overlay.history.WorkerVersion
import io.github.iw.durableatlas.overlay.testing.FakeTemporalServer
import io.github.iw.durableatlas.overlay.testing.Fixtures
import io.github.iw.durableatlas.overlay.version.AmbiguousCommitException
import io.github.iw.durableatlas.overlay.version.VersionMap
import io.github.iw.durableatlas.overlay.version.VersionMapEntry
import io.github.iw.durableatlas.render.DiagramFormats
import io.github.iw.durableatlas.render.DiagramLink
import io.temporal.api.enums.v1.EventType
import io.temporal.api.history.v1.History
import io.temporal.common.WorkflowExecutionHistory
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

/**
 * Histories laid over the documents of the commit that ran them. The histories are those of the
 * sample repository, marked as workers of a version would have left them; the documents of the
 * two commits are the same but for the commit they name.
 */
class OverlayByCommitTest {
    @TempDir
    lateinit var dir: Path

    private val ir: Path get() = dir.resolve("ir")
    private val histories: Path get() = dir.resolve("histories")
    private val out: Path get() = dir.resolve("out")
    private val mermaid = OverlayOptions(formats = listOf(DiagramFormats.MERMAID))

    private val first = "4f2a9c1"
    private val second = "9b7d3e0"
    private val map = VersionMap(
        versions = listOf(
            VersionMapEntry("orders", "build-a", first),
            VersionMapEntry("orders", "build-b", second),
            // Any deployment with this build id.
            VersionMapEntry(null, "build-c", second),
        ),
    )

    /** The documents of [workflowTypes] as the documents of [commit], in the directory of its name. */
    private fun documents(commit: String, vararg workflowTypes: String) {
        val directory = Files.createDirectories(ir.resolve(commit))
        for (type in workflowTypes) {
            Files.writeString(directory.resolve("$type.ir.json"), IrJson.encode(Fixtures.sample(type).copy(commit = commit)))
        }
    }

    private fun saved(name: String): History =
        WorkflowExecutionHistory.fromJson(Files.readString(Fixtures.sampleHistories.resolve("$name.json"))).history

    private fun history(file: String, history: History) {
        Files.createDirectories(histories)
        Files.writeString(histories.resolve(file), HistoryJson.encode(history))
    }

    private fun ranBy(name: String, buildId: String, deployment: String = "orders", from: Int = 0): History =
        FakeTemporalServer.ranBy(saved(name), deployment, buildId, from)

    /** The saved history [name] as a 1.32 server records workers that name their build with `WorkerOptions.setBuildId` alone. */
    @Suppress("DEPRECATION")
    private fun stamped(name: String, buildId: String): History =
        History.newBuilder().addAllEvents(
            saved(name).eventsList.map { event ->
                if (event.eventType != EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) return@map event
                event.toBuilder().apply { workflowTaskCompletedEventAttributesBuilder.workerVersionBuilder.setBuildId(buildId) }.build()
            },
        ).build()

    private fun overlay(versions: VersionMap = map, options: OverlayOptions = mermaid, target: Path = out): OverlaySummary =
        OverlayDirectory.write(ir, histories, target, options, versions)

    private fun contents(directory: Path = out): Map<String, String> =
        Files.walk(directory).use { files ->
            files.filter { it.isRegularFile() }.toList().associate { it.relativeTo(directory).toString() to Files.readString(it) }
        }.toSortedMap()

    /** Histories of two versions, one that changed hands, one of an unknown version and one without a version. */
    private fun sample() {
        documents(first, "OrderWorkflow", "TimerWorkflow", "LoopWorkflow")
        documents(second, "OrderWorkflow", "LoopWorkflow")
        history("order-a.json", ranBy("OrderWorkflow", "build-a"))
        history("order-b.json", ranBy("OrderWorkflow.payment-declined", "build-b"))
        history("order-moved.json", FakeTemporalServer.ranBy(ranBy("OrderWorkflow.payment-timeout", "build-a"), "orders", "build-b", from = 3))
        history("loop-c.json", ranBy("LoopWorkflow", "build-c", deployment = "billing"))
        history("timer-b.json", ranBy("TimerWorkflow", "build-b"))
        history("await-x.json", ranBy("AwaitWorkflow", "build-x"))
        history("timer-plain.json", saved("TimerWorkflow"))
    }

    @Test
    fun `every history is laid over the documents of the commit its version was built from`() {
        sample()

        val summary = overlay()

        assertEquals(listOf(first, second), summary.commits.map { it.commit })
        val a = summary.commits[0]
        assertEquals(first, a.directory)
        assertEquals("$first/index.md", a.index)
        assertEquals(listOf(WorkerVersion("orders", "build-a")), a.versions)
        assertEquals(0, a.unversioned)
        assertEquals(listOf("OrderWorkflow"), a.workflows.map { it.workflowType })
        assertEquals(listOf("order-a.json"), a.workflows.single().executions.map { it.history })

        val b = summary.commits[1]
        assertEquals(listOf(WorkerVersion("billing", "build-c"), WorkerVersion("orders", "build-b")), b.versions)
        assertEquals(listOf("LoopWorkflow", "OrderWorkflow"), b.workflows.map { it.workflowType })
        assertEquals(listOf("order-b.json", "order-moved.json"), b.workflows[1].executions.map { it.history })

        // All of it again, in one list, with the file names as seen from the output directory.
        assertEquals(listOf(first to "OrderWorkflow", second to "LoopWorkflow", second to "OrderWorkflow"), summary.workflows.map { it.commit to it.workflowType })
        val order = summary.workflows[2]
        assertEquals("$second/OrderWorkflow.ir.json", order.irFile)
        assertEquals("$second/OrderWorkflow.coverage.json", order.coverageFile)
        assertEquals(listOf(DiagramLink("mermaid", "$second/OrderWorkflow.coverage.md")), order.files)
        assertEquals(listOf(DiagramLink("mermaid", "$second/executions/order-b.md")), order.executions[0].files)
        assertNull(order.diagramFile)

        // Each commit has a directory like the one an overlay without a version map writes.
        assertEquals(
            listOf(
                "$first/OrderWorkflow.coverage.json", "$first/OrderWorkflow.coverage.md", "$first/executions/order-a.md", "$first/index.md",
                "$second/LoopWorkflow.coverage.json", "$second/LoopWorkflow.coverage.md", "$second/OrderWorkflow.coverage.json",
                "$second/OrderWorkflow.coverage.md", "$second/executions/loop-c.md", "$second/executions/order-b.md",
                "$second/executions/order-moved.md", "$second/index.md", "index.md",
            ),
            contents().keys.toList(),
        )
        // The coverage is that of the commit's own document.
        assertEquals(first, CoverageJson.decode(contents().getValue("$first/OrderWorkflow.coverage.json")).commit)
        val later = CoverageJson.decode(contents().getValue("$second/OrderWorkflow.coverage.json"))
        assertEquals(second, later.commit)
        assertEquals(2, later.executions)
        assertEquals(0, later.events.unmapped)
    }

    @Test
    fun `an execution that changed hands is laid over the commit of the version that ran it last`() {
        sample()

        val moved = overlay().commits[1].workflows.single { it.workflowType == "OrderWorkflow" }.executions.single { it.history == "order-moved.json" }

        assertEquals(listOf(WorkerVersion("orders", "build-a"), WorkerVersion("orders", "build-b")), moved.workerVersions)
        assertEquals(WorkerVersion("orders", "build-b"), moved.workerVersion)
        // Nothing of it is lost: the path is the one the saved history takes.
        assertEquals(0, moved.events.unmapped)
        assertContains(contents().getValue("$second/executions/order-moved.md"), "orders:build-a, then orders:build-b")
        assertContains(contents().getValue("$second/index.md"), "| `order-moved.json` | – | `orders:build-a`, then `orders:build-b` | completed |")
    }

    @Test
    fun `histories of unknown versions, without a version or without documents are reported and left out`() {
        sample()

        val summary = overlay()

        assertEquals(
            listOf(
                "await-x.json" to "worker version orders:build-x is not in the version map",
                "timer-b.json" to "no workflow document for this workflow type",
                "timer-plain.json" to "the history names no worker version, and the version map has no commit for such histories",
            ),
            summary.unmatched.map { it.file to it.reason },
        )
        assertEquals(listOf("AwaitWorkflow", "TimerWorkflow", "TimerWorkflow"), summary.unmatched.map { it.workflowType })
        // The page of the commit names what it could not place itself; the page above it names everything.
        assertContains(contents().getValue("$second/index.md"), "- `timer-b.json`, workflow type `TimerWorkflow`: no workflow document for this workflow type")
        assertFalse("await-x.json" in contents().getValue("$second/index.md"))
    }

    @Test
    fun `histories without a version go to the commit the map names for them`() {
        sample()

        val summary = overlay(map.copy(unversioned = first))

        val a = summary.commits[0]
        assertEquals(listOf("OrderWorkflow", "TimerWorkflow"), a.workflows.map { it.workflowType })
        assertEquals(1, a.unversioned)
        assertEquals(listOf(WorkerVersion("orders", "build-a")), a.versions)
        assertEquals(listOf("timer-plain.json"), a.workflows[1].executions.map { it.history })
        assertEquals(listOf("await-x.json", "timer-b.json"), summary.unmatched.map { it.file })
    }

    @Test
    fun `a history whose workers named their build alone goes to the entry for that build id`() {
        documents(first, "OrderWorkflow")
        documents(second, "LoopWorkflow")
        history("loop-c.json", stamped("LoopWorkflow", "build-c"))
        history("order-a.json", stamped("OrderWorkflow", "build-a"))

        val summary = overlay()

        // An entry that names a deployment is not for workers that named none.
        assertEquals(listOf("order-a.json" to "worker version build-a is not in the version map"), summary.unmatched.map { it.file to it.reason })
        val commit = summary.commits.single()
        assertEquals(second, commit.commit)
        assertEquals(listOf(WorkerVersion(null, "build-c")), commit.versions)
        assertEquals(0, commit.unversioned)
        assertContains(contents().getValue("$second/index.md"), "| `loop-c.json` | – | `build-c` |")
    }

    @Test
    fun `a commit without a directory of documents leaves its histories out`() {
        sample()
        val elsewhere = map.copy(versions = map.versions + VersionMapEntry("orders", "build-x", "0000000"), unversioned = "1111111")

        val summary = overlay(elsewhere)

        assertEquals(
            listOf(
                "await-x.json" to "the version map sends worker version orders:build-x to commit 0000000, which has no directory of workflow documents",
                "timer-b.json" to "no workflow document for this workflow type",
                "timer-plain.json" to
                    "the version map sends histories without a worker version to commit 1111111, which has no directory of workflow documents",
            ),
            summary.unmatched.map { it.file to it.reason },
        )
        assertFalse(out.resolve("0000000").exists())
    }

    @Test
    fun `a commit the map abbreviates is the directory that begins with it, and is reported by that directory's name`() {
        val full = "4f2a9c1d07e35b8a6c2f9e0d1b4a7c3e5f6a8b90"
        documents(full, "OrderWorkflow", "TimerWorkflow")
        documents(second, "OrderWorkflow")
        history("order-a.json", ranBy("OrderWorkflow", "build-a"))
        history("order-b.json", ranBy("OrderWorkflow.payment-declined", "build-b"))
        history("timer-c.json", ranBy("TimerWorkflow", "build-c"))
        history("timer-plain.json", saved("TimerWorkflow"))
        // The same commit written three ways, and one written as its directory is called.
        val abbreviated = VersionMap(
            versions = listOf(
                VersionMapEntry("orders", "build-a", first),
                VersionMapEntry("orders", "build-b", second),
                VersionMapEntry(null, "build-c", full),
            ),
            unversioned = "4f2a",
        )

        val summary = overlay(abbreviated)

        assertEquals(listOf(full, second), summary.commits.map { it.commit })
        val commit = summary.commits[0]
        assertEquals(full, commit.directory)
        assertEquals("$full/index.md", commit.index)
        assertEquals(listOf(WorkerVersion("orders", "build-a"), WorkerVersion("orders", "build-c")), commit.versions)
        assertEquals(1, commit.unversioned)
        assertEquals(listOf("OrderWorkflow", "TimerWorkflow"), commit.workflows.map { it.workflowType })
        assertEquals(listOf("timer-c.json", "timer-plain.json"), commit.workflows[1].executions.map { it.history })
        assertEquals(listOf(full, full, second), summary.workflows.map { it.commit })
        assertEquals("$full/OrderWorkflow.coverage.json", summary.workflows[0].coverageFile)
        assertEquals(emptyList(), summary.unmatched)
        // Nothing is written under the name the map used.
        assertEquals(setOf(full, second, "index.md"), contents().keys.map { it.substringBefore('/') }.toSet())
        assertEquals(full, CoverageJson.decode(contents().getValue("$full/OrderWorkflow.coverage.json")).commit)
        assertContains(contents().getValue("index.md"), "| `$full` | `orders:build-a`, `orders:build-c`, no version |")
    }

    @Test
    fun `a directory called what the map writes goes before the directories that begin with it`() {
        documents(first, "OrderWorkflow")
        documents("${first}d07e", "OrderWorkflow")
        documents("${first}ffff", "OrderWorkflow")
        history("order-a.json", ranBy("OrderWorkflow", "build-a"))

        val summary = overlay()

        assertEquals(listOf(first), summary.commits.map { it.commit })
        assertEquals(first, CoverageJson.decode(contents().getValue("$first/OrderWorkflow.coverage.json")).commit)
    }

    @Test
    fun `a commit that several directories begin with is refused, and nothing is written`() {
        documents("${first}d07e", "OrderWorkflow")
        documents("${first}ffff", "OrderWorkflow")
        documents(second, "OrderWorkflow")
        // No history is sent to the commit in doubt: the map is wrong all the same.
        history("order-b.json", ranBy("OrderWorkflow.payment-declined", "build-b"))

        val refused = assertFailsWith<AmbiguousCommitException> { overlay() }

        assertEquals(first, refused.commit)
        assertEquals(listOf("${first}d07e", "${first}ffff"), refused.candidates)
        assertEquals(
            "the version map names commit $first, which several directories in $ir begin with: ${first}d07e, ${first}ffff; " +
                "the map has to write more of the commit",
            refused.message,
        )
        assertFalse(out.exists())
    }

    @Test
    fun `the page above the commits lists them and what was left out`() {
        sample()

        overlay(map.copy(unversioned = first))

        GoldenFiles.assertMatches("by-commit/index.md", contents().getValue("index.md"))
    }

    @Test
    fun `SVG overlays are drawn on the plain diagram of their own commit, and two runs write the same bytes`() {
        sample()

        val summary = overlay(options = OverlayOptions())

        val order = summary.workflows.first { it.commit == second && it.workflowType == "OrderWorkflow" }
        assertEquals("$second/OrderWorkflow.svg", order.diagramFile)
        val files = contents()
        assertTrue("$first/OrderWorkflow.svg" in files && "$second/OrderWorkflow.svg" in files)
        assertTrue("$second/executions/order-moved.svg" in files)
        // The commit is part of what the diagram shows, so the two plain diagrams differ.
        assertContains(files.getValue("$second/OrderWorkflow.svg"), second)
        assertFalse(first in files.getValue("$second/OrderWorkflow.svg"))

        overlay(options = OverlayOptions())
        assertEquals(files, contents())
        overlay(options = OverlayOptions(), target = dir.resolve("again"))
        assertEquals(files, contents(dir.resolve("again")))
    }

    @Test
    fun `the workflow types of all commits are counted as one run, each named with the start of its commit`() {
        sample()
        val heard = ArrayList<String>()
        val progress = object : io.github.iw.durableatlas.render.DrawingProgress {
            override fun drawn(
                step: io.github.iw.durableatlas.render.DrawingStep,
                elapsed: java.time.Duration,
                missing: List<io.github.iw.durableatlas.render.MissingDiagram>,
                simplified: List<io.github.iw.durableatlas.render.SimplifiedDiagram>,
            ) {
                heard += "${step.number}/${step.count} ${step.name}: ${step.executions} executions"
            }
        }
        // A commit under its full name, as `run` writes it.
        val long = "9b7d3e0c41a2f5b6d7e8f9a0b1c2d3e4f5a6b7c8"
        Files.move(ir.resolve(second), ir.resolve(long))

        overlay(options = OverlayOptions(formats = listOf(DiagramFormats.MERMAID), progress = progress))

        assertEquals(listOf("1/3 4f2a9c1/OrderWorkflow: 1 executions", "2/3 9b7d3e0/LoopWorkflow: 1 executions", "3/3 9b7d3e0/OrderWorkflow: 2 executions"), heard)
    }

    @Test
    fun `with nothing to lay over anything only the page is written`() {
        Files.createDirectories(ir)
        history("timer-plain.json", saved("TimerWorkflow"))

        val summary = overlay()

        assertEquals(emptyList(), summary.commits)
        assertEquals(emptyList(), summary.workflows)
        assertEquals(listOf("index.md"), contents().keys.toList())
        assertContains(contents().getValue("index.md"), "0 executions laid over the workflows of 0 commits.")
    }
}
