package io.github.iw.durableatlas.analyser

import io.github.iw.durableatlas.analyser.code.SourceBackend
import io.github.iw.durableatlas.analyser.discovery.Discovery
import io.github.iw.durableatlas.analyser.discovery.TemporalDiscovery
import io.github.iw.durableatlas.analyser.graph.WorkflowGraphs
import io.github.iw.durableatlas.ir.IrDocument
import io.github.iw.durableatlas.ir.IrJson
import io.github.iw.durableatlas.ir.ModelMode
import io.github.iw.durableatlas.ir.testing.GoldenFiles
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the last step of a region says about where it goes. A step that says nothing goes on to
 * the end of its region: of a loop, round again. A flow edge from it to a node outside the region
 * says that it leaves for good, so it gets no way round; an edge for a failure or a timeout that
 * is dealt with outside says nothing of the kind. Wherever a step can do both, and wherever a
 * branch ends the round with one arm, the way to the end of the region is written out.
 *
 * Each test gives the whole graph of one workflow of [RegionExitSources], without ids: a node is
 * its kind and its line, as [IrStructure] writes it. The documents are kept as golden files as
 * well, for the tests of what is drawn from them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegionExitExtractionTest {
    private lateinit var backend: SourceBackend
    private lateinit var discovery: Discovery
    private lateinit var documents: List<IrDocument>

    @BeforeAll
    fun open(@TempDir directory: Path) {
        val sources = SyntheticSources(directory)
        RegionExitSources.FILES.forEach { (name, text) -> sources.file(":app", "exits/$name.kt", text) }
        backend = sources.open()
        discovery = TemporalDiscovery.discover(backend)
        documents = WorkflowGraphs(backend, FIXED_COMMIT, ModelMode.DEGRADED).extract(discovery)
    }

    @AfterAll
    fun close() {
        backend.close()
    }

    /** The nodes and edges of a workflow, without ids. */
    private fun graph(workflowType: String): List<String> =
        documents.single { it.workflowType == workflowType }.outline().filterNot { it.startsWith("entry point ") }

    @Test
    fun `every document is well formed and gives the golden IR`() {
        assertEquals(RegionExitSources.FILES.keys - "Tasks", documents.map { it.workflowType }.toSet())
        for (document in documents) {
            assertWellFormed(document)
            GoldenFiles.assertMatches("ir/exits/${document.workflowType}.ir.json", IrJson.encode(document))
        }
    }

    @Test
    fun `reading everything an entry point reaches gives the graph that sparing what holds nothing gives`() {
        assertEquals(documents.sumOf { it.mainFlow().nodes.size }, assertSparingChangesNothing(backend, discovery))
    }

    @Test
    fun `a branch that ends the round with one arm and breaks with the other says both`() {
        assertEquals(
            listOf(
                "node activity@21: name Prepare, parent loop@20",
                "node activity@24: name Report",
                "node branch@22: label id == \"last\", parent loop@20",
                "node end(main)",
                "node loop@20: label for each id",
                "node start@19",
                "edge activity@21 -> branch@22",
                "edge activity@24 -> end(main)",
                // `break`: out of the loop, to what follows it.
                "edge branch@22 -> activity@24 [yes]",
                // Nothing follows the `if`, so the other arm is the end of the round. It is not left unsaid.
                "edge branch@22 -> loop@20 [no]",
                "edge loop@20 -> activity@24",
                "edge start@19 -> loop@20",
            ),
            graph("BranchBreak"),
        )
    }

    @Test
    fun `the same with a return, which leads to the end of the workflow`() {
        assertEquals(
            listOf(
                "node activity@21: name Prepare, parent loop@20",
                "node activity@24: name Report",
                "node branch@22: label id == \"last\", parent loop@20",
                "node end(main)",
                "node loop@20: label for each id",
                "node start@19",
                "edge activity@21 -> branch@22",
                "edge activity@24 -> end(main)",
                "edge branch@22 -> end(main) [yes]",
                "edge branch@22 -> loop@20 [no]",
                "edge loop@20 -> activity@24",
                "edge start@19 -> loop@20",
            ),
            graph("BranchReturn"),
        )
    }

    @Test
    fun `with a continue both arms end the round`() {
        assertEquals(
            listOf(
                "node activity@21: name Prepare, parent loop@20",
                "node activity@24: name Report",
                "node branch@22: label id == \"skip\", parent loop@20",
                "node end(main)",
                "node loop@20: label for each id",
                "node start@19",
                "edge activity@21 -> branch@22",
                "edge activity@24 -> end(main)",
                "edge branch@22 -> loop@20 [no]",
                "edge branch@22 -> loop@20 [yes]",
                "edge loop@20 -> activity@24",
                "edge start@19 -> loop@20",
            ),
            graph("BranchContinue"),
        )
    }

    @Test
    fun `every arm of a when says where it goes`() {
        assertEquals(
            listOf(
                "node activity@21: name Prepare, parent loop@20",
                "node activity@26: name Work, parent loop@20",
                "node activity@30: name Report",
                "node branch@22: label id, parent loop@20",
                "node end(main)",
                "node loop@20: label for each id",
                "node start@19",
                "edge activity@21 -> branch@22",
                "edge activity@30 -> end(main)",
                // The arm with a step: the step is the last of the round, and nothing needs saying for it.
                "edge branch@22 -> activity@26 [\"work\"]",
                // `break`, `return`, `continue`, and the arm that does nothing.
                "edge branch@22 -> activity@30 [\"last\"]",
                "edge branch@22 -> end(main) [\"quit\"]",
                "edge branch@22 -> loop@20 [\"skip\"]",
                "edge branch@22 -> loop@20 [else]",
                "edge loop@20 -> activity@30",
                "edge start@19 -> loop@20",
            ),
            graph("WhenArms"),
        )
    }

    @Test
    fun `a step that a break follows leads out of the loop and nowhere else`() {
        assertEquals(
            listOf(
                "node activity@21: name Prepare, parent loop@20",
                "node activity@23: name Report, parent loop@20",
                "node activity@27: name CleanUp",
                "node branch@22: label id == \"last\", parent loop@20",
                "node end(main)",
                "node loop@20: label for each id",
                "node start@19",
                "edge activity@21 -> branch@22",
                // The report is the last step of its round and of the loop: its one edge leaves the loop.
                "edge activity@23 -> activity@27",
                "edge activity@27 -> end(main)",
                "edge branch@22 -> activity@23 [yes]",
                "edge branch@22 -> loop@20 [no]",
                "edge loop@20 -> activity@27",
                "edge start@19 -> loop@20",
            ),
            graph("NotifyBreak"),
        )
    }

    @Test
    fun `a step that a return follows leads to the end of the workflow and nowhere else`() {
        assertEquals(
            listOf(
                "node activity@21: name Work, parent loop@20",
                "node activity@24: name Report",
                "node end(main)",
                "node loop@20: label for each id",
                "node start@19",
                "edge activity@21 -> end(main)",
                "edge activity@24 -> end(main)",
                "edge loop@20 -> activity@24",
                "edge start@19 -> loop@20",
            ),
            graph("StepReturn"),
        )
    }

    @Test
    fun `a step that a throw follows leads to the failure and nowhere else`() {
        assertEquals(
            listOf(
                "node activity@21: name Prepare, parent loop@20",
                "node activity@23: name Report, parent loop@20",
                "node branch@22: label id == \"bad\", parent loop@20",
                "node end(main)",
                "node fail@24: name IllegalStateException",
                "node loop@20: label for each id",
                "node start@19",
                "edge activity@21 -> branch@22",
                "edge activity@23 -> fail@24",
                "edge branch@22 -> activity@23 [yes]",
                "edge branch@22 -> loop@20 [no]",
                "edge loop@20 -> end(main)",
                "edge start@19 -> loop@20",
            ),
            graph("NotifyThrow"),
        )
    }

    @Test
    fun `a failure that is caught and passed over ends the round, and so does the step when it does not fail`() {
        assertEquals(
            listOf(
                "node activity@22: name Work, parent loop@20",
                "node activity@28: name Prepare, parent loop@27",
                "node activity@31: name CleanUp",
                "node end(main)",
                "node loop@20: label for each id",
                "node loop@27: label for each id",
                "node start@19",
                // The clause holds nothing: the failure leads on to the end of the round.
                "edge activity@22 -(exception)-> loop@20 [ActivityFailure]",
                // That edge alone would say the step goes round only when it fails, so its plain way round is written too.
                "edge activity@22 -> loop@20",
                // A failure that is dealt with outside the loop says nothing about the step otherwise: its way round is implied.
                "edge activity@28 -(exception)-> activity@31 [ActivityFailure]",
                "edge activity@31 -> end(main)",
                "edge loop@20 -> loop@27",
                "edge loop@27 -> end(main)",
                "edge start@19 -> loop@20",
            ),
            graph("CaughtFailure"),
        )
    }

    @Test
    fun `an await that leaves the loop when it times out goes round under the name of its other outcome`() {
        assertEquals(
            listOf(
                "node activity@26: name Prepare, parent loop@25",
                "node activity@29: name Report",
                "node await@27: timeout PT5M, reads answered, label answered, parent loop@25",
                "node end(main)",
                "node end(signal-answer)",
                "node loop@25: label for each id",
                "node start@24",
                "node start@32",
                "edge activity@26 -> await@27",
                "edge activity@29 -> end(main)",
                "edge await@27 -(timeout)-> activity@29",
                "edge await@27 -> loop@25 [answered]",
                "edge entry point signal-answer -(wakes)-> await@27",
                "edge loop@25 -> activity@29",
                "edge start@24 -> loop@25",
                "edge start@32 -> end(signal-answer)",
            ),
            graph("AwaitTimeout"),
        )
    }

    @Test
    fun `a step beside a lane that a break follows leaves the parallel region and the loop around it`() {
        assertEquals(
            listOf(
                "node activity@21: name Check, parent parallel@21",
                "node activity@22: name Prepare, parent parallel@21",
                "node activity@24: name Report, parent parallel@21",
                "node activity@29: name CleanUp",
                "node branch@23: label id == \"last\", parent parallel@21",
                "node end(main)",
                "node loop@20: label for each id",
                "node parallel@21: parent loop@20",
                "node start@19",
                "edge activity@22 -> branch@23",
                // Out of the lane and out of the loop in one edge: the lane does not end where the others join.
                "edge activity@24 -> activity@29",
                "edge activity@29 -> end(main)",
                "edge branch@23 -> activity@24 [yes]",
                // The arm that waits for the other lane is the end of this one.
                "edge branch@23 -> parallel@21 [no]",
                "edge loop@20 -> activity@29",
                "edge start@19 -> loop@20",
            ),
            graph("LaneBreak"),
        )
    }
}
